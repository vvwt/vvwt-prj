package de.vvwt.slotopt.standalone.bootstrap.internal;

import de.vvwt.slotopt.standalone.WorkerConfig;
import de.vvwt.slotopt.standalone.bootstrap.AlgorithmValidationResult;
import de.vvwt.slotopt.standalone.bootstrap.AlgorithmValidator;
import de.vvwt.slotopt.standalone.bootstrap.BootstrapException;
import de.vvwt.slotopt.standalone.bootstrap.BootstrapService;
import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import de.vvwt.slotopt.worker.runtime.AnnouncedAlgorithmsResponse;
import de.vvwt.slotopt.worker.runtime.DispatcherClient;
import de.vvwt.slotopt.worker.runtime.DispatcherException;
import de.vvwt.slotopt.worker.runtime.RegisterKeyRequest;
import de.vvwt.slotopt.worker.runtime.RegisterKeyResponse;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link BootstrapService}.
 *
 * <p>Orchestrates the bootstrap phase:
 *
 * <ol>
 *   <li>Emits {@code worker_started} INFO event.
 *   <li>Calls {@code GET /api/algorithms} via {@link DispatcherClient}.
 *   <li>Emits {@code algorithms_announced} INFO event.
 *   <li>Validates algorithm via {@link AlgorithmValidator} (DEC-43 D2/D3 + DEC-48).
 *   <li>Emits optional {@code algorithm_deprecation_warning} + stderr line if future deprecation.
 *   <li>Emits {@code algorithm_picked} INFO event.
 *   <li>Registers keypair via {@link DispatcherClient#registerKey}.
 *   <li>Emits {@code key_registered} INFO event.
 * </ol>
 *
 * <p>The {@code eventConsumer} constructor parameter allows test injection of an event sink. In
 * production, events are also emitted via slf4j INFO logger. The consumer receives structured event
 * strings formatted as key=value pairs for assertability in tests.
 *
 * <p>DEC-35-by-analogy: implementation in {@code bootstrap.internal}; public interface {@link
 * BootstrapService} in {@code bootstrap}.
 *
 * <p>Story: E41S04 AC-OBSERVABILITY-EVENTS-BOOTSTRAP, AC-EXIT-CODE-BOOTSTRAP,
 * AC-DEC43-D3-ADMIN-WARNING-SURFACE.
 */
public class DefaultBootstrapService implements BootstrapService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultBootstrapService.class);

    private final DispatcherClient dispatcherClient;
    private final Consumer<String> eventConsumer;

    /**
     * Constructs a new {@code DefaultBootstrapService} for production use.
     *
     * @param dispatcherClient the HTTP client for dispatcher communication
     */
    public DefaultBootstrapService(DispatcherClient dispatcherClient) {
        this(dispatcherClient, event -> {});
    }

    /**
     * Constructs a new {@code DefaultBootstrapService} with an injectable event consumer for
     * testability.
     *
     * @param dispatcherClient the HTTP client for dispatcher communication
     * @param eventConsumer receives structured event strings (key=value pairs) for each emitted
     *     observability event; may be used to capture events in tests
     */
    public DefaultBootstrapService(
            DispatcherClient dispatcherClient, Consumer<String> eventConsumer) {
        this.dispatcherClient = dispatcherClient;
        this.eventConsumer = eventConsumer;
    }

    /** {@inheritDoc} */
    @Override
    public UUID run(WorkerConfig config) throws BootstrapException {
        // Step 1: worker_started
        emitInfo("worker_started", "configHash=" + System.identityHashCode(config));

        // Step 2: fetch announced algorithms
        AnnouncedAlgorithmsResponse algorithmsResponse;
        try {
            algorithmsResponse = dispatcherClient.fetchAnnouncedAlgorithms();
        } catch (DispatcherException e) {
            throw new BootstrapException(
                    ExitCode.DISPATCHER_UNREACHABLE,
                    "dispatcher unreachable: " + e.getMessage(),
                    e);
        }

        // Step 3: algorithms_announced
        String algorithmIds =
                algorithmsResponse.algorithms().stream()
                        .map(a -> a.algorithmId())
                        .reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b);
        emitInfo(
                "algorithms_announced",
                "count="
                        + algorithmsResponse.algorithms().size()
                        + " algorithm_ids="
                        + algorithmIds);

        // Step 4-5: validate algorithm (DEC-43 D2/D3 + DEC-48)
        AlgorithmValidationResult validationResult =
                AlgorithmValidator.validate(config.signingAlgorithm(), algorithmsResponse);

        // Step 5b: D3 deprecation warning surface (if applicable)
        if (validationResult.hasDeprecationWarning()) {
            String migrationTargets = validationResult.recommendedMigrationCommaSeparated();
            String deprecationDateStr = validationResult.deprecationDate().toString();

            // D3 WARN-level structured log event (AC-DEC43-D3-ADMIN-WARNING-SURFACE)
            String warningEvent =
                    "algorithm_deprecation_warning"
                            + " algorithm="
                            + config.signingAlgorithm()
                            + " deprecation_date="
                            + deprecationDateStr
                            + " recommended_migration="
                            + migrationTargets;
            emitWarn(warningEvent);

            // D3 stderr line (AC-DEC43-D3-ADMIN-WARNING-SURFACE, Brief D-7 option ii)
            System.err.println(
                    "WARNING: Signature algorithm '"
                            + config.signingAlgorithm()
                            + "' will be deprecated on "
                            + deprecationDateStr
                            + ". Recommended migration targets: "
                            + migrationTargets
                            + ". Please plan migration before deadline.");
        }

        // Step 6: algorithm_picked
        String depDateStr =
                validationResult.deprecationDate() != null
                        ? validationResult.deprecationDate().toString()
                        : "null";
        emitInfo(
                "algorithm_picked",
                "algorithm_id=" + config.signingAlgorithm() + " deprecation_date=" + depDateStr);

        // Step 7: generate keypair + register
        // Keypair generation: worker-lib WorkerKeyManager (E41S03 ResultSigner dependency)
        // For E41S04: generate a new UUID for workerId and use a dummy 32-byte public key
        // (the actual keypair generation via WorkerKeyManager is wired in E41S05 when the full
        // runtime context including keyDir is available)
        UUID workerId = UUID.randomUUID();
        byte[] dummyPublicKey = new byte[32]; // placeholder; real keypair in E41S05 wiring

        RegisterKeyRequest registerRequest =
                new RegisterKeyRequest(
                        workerId, "worker", config.signingAlgorithm(), dummyPublicKey);
        RegisterKeyResponse registerResponse;
        try {
            registerResponse = dispatcherClient.registerKey(registerRequest);
        } catch (DispatcherException e) {
            if (e.getHttpStatus() == 410) {
                throw new BootstrapException(
                        ExitCode.REGISTRATION_REJECTED_DEPRECATED,
                        "register-key rejected: algorithm deprecated at registration time (HTTP"
                                + " 410)",
                        e);
            }
            throw new BootstrapException(
                    ExitCode.DISPATCHER_UNREACHABLE,
                    "dispatcher unreachable during registration: " + e.getMessage(),
                    e);
        }

        // Step 8: key_registered
        UUID assignedWorkerId = registerResponse.workerId();
        emitInfo(
                "key_registered",
                "workerId=" + assignedWorkerId + " algorithm_id=" + config.signingAlgorithm());

        return assignedWorkerId;
    }

    private void emitInfo(String event, String fields) {
        String structured = event + " " + fields;
        LOG.info(structured);
        eventConsumer.accept(structured);
    }

    private void emitWarn(String structured) {
        LOG.warn(structured);
        eventConsumer.accept(structured);
    }
}
