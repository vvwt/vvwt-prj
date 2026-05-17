package de.vvwt.slotopt.standalone;

import de.vvwt.slotopt.standalone.bootstrap.BootstrapException;
import de.vvwt.slotopt.standalone.bootstrap.internal.DefaultBootstrapService;
import de.vvwt.slotopt.standalone.internal.DefaultWorkerConfigLoader;
import de.vvwt.slotopt.standalone.log.StructuredLogger;
import de.vvwt.slotopt.standalone.log.internal.DefaultStructuredLogger;
import de.vvwt.slotopt.standalone.runtime.CpuThrottle;
import de.vvwt.slotopt.standalone.runtime.WorkerLoop;
import de.vvwt.slotopt.standalone.runtime.WorkerLoopException;
import de.vvwt.slotopt.standalone.runtime.internal.DefaultCpuThrottle;
import de.vvwt.slotopt.standalone.runtime.internal.DefaultWorkerLoop;
import de.vvwt.slotopt.worker.identity.WorkerKeyCorruptException;
import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.identity.internal.DefaultWorkerKeyManager;
import de.vvwt.slotopt.worker.runtime.ComputeStep;
import de.vvwt.slotopt.worker.runtime.DispatcherClient;
import de.vvwt.slotopt.worker.runtime.ResultSigner;
import de.vvwt.slotopt.worker.runtime.internal.DefaultComputeStep;
import de.vvwt.slotopt.worker.runtime.internal.DefaultDispatcherClient;
import de.vvwt.slotopt.worker.runtime.internal.DefaultResultSigner;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

/**
 * Entry point for the vvwt-slotopt-standalone-worker process.
 *
 * <p>This class is the Picocli {@code @Command} skeleton for the standalone worker. The {@link
 * #main(String[])} method:
 *
 * <ol>
 *   <li>Loads and validates configuration via {@link WorkerConfigLoader}.
 *   <li>Constructs the worker infrastructure: {@link WorkerKeyManager}, {@link DispatcherClient},
 *       {@link ResultSigner}, {@link ComputeStep}, {@link StructuredLogger}, {@link CpuThrottle}.
 *   <li>Runs the bootstrap phase via {@link DefaultBootstrapService} to validate the signing
 *       algorithm and register the worker's public key with the dispatcher.
 *   <li>Registers the JVM shutdown hook (relocated from {@code DefaultWorkerLoop} constructor per
 *       E63S01 DEC-70 fix — the hook belongs to the application lifecycle, not the compute loop).
 *   <li>Runs the runtime polling loop via {@link DefaultWorkerLoop}.
 *   <li>Exits with the appropriate exit code: 0 on graceful shutdown, or the code carried by {@link
 *       BootstrapException} / {@link WorkerLoopException} on error.
 * </ol>
 *
 * <p>Actual option parsing lives in {@link
 * de.vvwt.slotopt.standalone.internal.DefaultWorkerConfigLoader}.
 *
 * <p>Story: E41S02 AC-OPTIMIZER-WORKER-MAIN; E41S05 AC-EXIT-CODE-RUNTIME wiring; E63S01 re-wire
 * onto shared runtime library + JVM shutdown hook relocation.
 *
 * @see WorkerConfigLoader
 * @see de.vvwt.slotopt.standalone.internal.DefaultWorkerConfigLoader
 */
@Command(
        name = "vvwt-slotopt-standalone-worker",
        mixinStandardHelpOptions = true,
        version = "vvwt-slotopt-standalone-worker E63S01",
        description =
                "VVW Slot-Opt Standalone Worker — headless compute process. Registers with the"
                        + " dispatcher, pulls packets, and submits signed results.")
public class OptimizerWorkerMain {

    /**
     * Main entry point.
     *
     * <p>Delegates to {@link WorkerConfigLoader#load(String[])} to parse and validate CLI
     * arguments, then runs bootstrap followed by the runtime loop. Calls {@link System#exit(int)}
     * with the appropriate exit code on completion or error.
     *
     * @param args CLI arguments forwarded from the JVM launcher
     */
    public static void main(String[] args) {
        WorkerConfigLoader loader = new DefaultWorkerConfigLoader();
        WorkerConfig config = loader.load(args);

        // Construct key manager — initializes keypair from keyDir (creates if necessary)
        WorkerKeyManager workerKeyManager;
        try {
            workerKeyManager =
                    new DefaultWorkerKeyManager(
                            config.keyDir(),
                            config.signingAlgorithm(),
                            LoggerFactory.getLogger(DefaultWorkerKeyManager.class));
        } catch (WorkerKeyCorruptException e) {
            System.err.println(
                    "ERROR: Worker key file is corrupt — manual intervention required: "
                            + e.getMessage());
            System.exit(78); // EX_CONFIG — operator must fix
            return; // unreachable; satisfies compiler
        } catch (IOException e) {
            System.err.println("ERROR: Failed to initialize worker key manager: " + e.getMessage());
            System.exit(75); // EX_TEMPFAIL — supervisor should retry
            return; // unreachable; satisfies compiler
        }

        // Construct HTTP client, result signer, and compute step
        // (dispatcher HTTP client + signing moved to shared vvwt-slotopt-worker-runtime library)
        DispatcherClient dispatcherClient =
                new DefaultDispatcherClient(config.dispatcherUrl(), config.httpTimeout());
        ResultSigner resultSigner =
                new DefaultResultSigner(workerKeyManager, config.signingAlgorithm());
        ComputeStep computeStep =
                new DefaultComputeStep(dispatcherClient, resultSigner, config.signingAlgorithm());

        // Bootstrap phase: validate algorithm + register key → obtain workerId.
        // Pass workerKeyManager so that the real Ed25519 public key is used in registration
        // (E60S05: completes the wiring that was deferred from E41S05).
        UUID workerId;
        try {
            workerId =
                    new DefaultBootstrapService(dispatcherClient, event -> {}, workerKeyManager)
                            .run(config);
        } catch (BootstrapException e) {
            System.err.println("ERROR: Bootstrap failed: " + e.getMessage());
            System.exit(e.getExitCode());
            return; // unreachable
        }

        // Construct runtime infrastructure
        StructuredLogger logger = new DefaultStructuredLogger();
        CpuThrottle cpuThrottle = new DefaultCpuThrottle();

        // Runtime polling loop (JVM shutdown hook relocated here from DefaultWorkerLoop — E63S01)
        DefaultWorkerLoop workerLoop =
                new DefaultWorkerLoop(computeStep, config, cpuThrottle, logger, workerId);

        // AC-GRACEFUL-SHUTDOWN: register JVM shutdown hook here (not in DefaultWorkerLoop
        // constructor) so the hook belongs to the application lifecycle (DEC-70 fix)
        Runtime.getRuntime()
                .addShutdownHook(new Thread(workerLoop::requestShutdown, "worker-shutdown-hook"));

        WorkerLoop loop = workerLoop;
        try {
            loop.run();
            System.exit(0);
        } catch (WorkerLoopException e) {
            System.err.println("ERROR: Worker loop terminated: " + e.getMessage());
            System.exit(e.getExitCode());
        }
    }
}
