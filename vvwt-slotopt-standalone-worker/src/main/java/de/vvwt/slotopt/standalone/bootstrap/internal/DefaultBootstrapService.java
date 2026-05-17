// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.bootstrap.internal;

import de.vvwt.slotopt.standalone.WorkerConfig;
import de.vvwt.slotopt.standalone.bootstrap.AlgorithmValidationResult;
import de.vvwt.slotopt.standalone.bootstrap.AlgorithmValidator;
import de.vvwt.slotopt.standalone.bootstrap.BootstrapException;
import de.vvwt.slotopt.standalone.bootstrap.BootstrapService;
import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
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
    private final WorkerKeyManager workerKeyManager;

    /**
     * Constructs a new {@code DefaultBootstrapService} for production use.
     *
     * @param dispatcherClient the HTTP client for dispatcher communication
     */
    public DefaultBootstrapService(DispatcherClient dispatcherClient) {
        this(dispatcherClient, event -> {}, null);
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
        this(dispatcherClient, eventConsumer, null);
    }

    /**
     * Constructs a new {@code DefaultBootstrapService} with full dependency injection.
     *
     * <p>The {@code workerKeyManager} provides the real Ed25519 public key for registration with
     * the dispatcher. When non-null, {@link WorkerKeyManager#getPublicKeyBytes()} is used in the
     * {@code POST /api/register-key} request body, ensuring that the registered public key matches
     * the key used to sign submitted results. When {@code null}, a 32-byte zero-placeholder is used
     * (legacy path retained for unit tests that mock {@code dispatcherClient.registerKey} and do
     * not assert on key bytes).
     *
     * @param dispatcherClient the HTTP client for dispatcher communication
     * @param eventConsumer receives structured event strings for each emitted observability event
     * @param workerKeyManager the key manager providing the real public key for registration; may
     *     be {@code null} in unit-test contexts that mock the dispatcher client
     */
    public DefaultBootstrapService(
            DispatcherClient dispatcherClient,
            Consumer<String> eventConsumer,
            WorkerKeyManager workerKeyManager) {
        this.dispatcherClient = dispatcherClient;
        this.eventConsumer = eventConsumer;
        this.workerKeyManager = workerKeyManager;
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

        // Step 7: register keypair with the dispatcher.
        // Use the real public key from WorkerKeyManager when available (E60S05 wiring — the
        // E41S05 comment "real keypair in E41S05 wiring" was never implemented; done here).
        // Unit tests that mock dispatcherClient.registerKey(any()) pass null workerKeyManager and
        // receive the legacy 32-zero-byte placeholder — they do not assert on key bytes.
        UUID workerId = UUID.randomUUID();
        byte[] publicKeyBytes =
                (workerKeyManager != null)
                        ? workerKeyManager.getPublicKeyBytes()
                        : new byte[32]; // legacy placeholder for unit-test contexts

        RegisterKeyRequest registerRequest =
                new RegisterKeyRequest(
                        workerId, "worker", config.signingAlgorithm(), publicKeyBytes);
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
