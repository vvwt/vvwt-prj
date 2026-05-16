// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone;

import de.vvwt.slotopt.standalone.bootstrap.BootstrapException;
import de.vvwt.slotopt.standalone.bootstrap.internal.DefaultBootstrapService;
import de.vvwt.slotopt.standalone.crypto.ResultSigner;
import de.vvwt.slotopt.standalone.crypto.internal.DefaultResultSigner;
import de.vvwt.slotopt.standalone.http.DispatcherClient;
import de.vvwt.slotopt.standalone.http.internal.DefaultDispatcherClient;
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
 *       {@link ResultSigner}, {@link StructuredLogger}, {@link CpuThrottle}.
 *   <li>Runs the bootstrap phase via {@link DefaultBootstrapService} to validate the signing
 *       algorithm and register the worker's public key with the dispatcher.
 *   <li>Runs the runtime polling loop via {@link DefaultWorkerLoop}.
 *   <li>Exits with the appropriate exit code: 0 on graceful shutdown, or the code carried by {@link
 *       BootstrapException} / {@link WorkerLoopException} on error.
 * </ol>
 *
 * <p>Actual option parsing lives in {@link
 * de.vvwt.slotopt.standalone.internal.DefaultWorkerConfigLoader}.
 *
 * <p>Story: E41S02 AC-OPTIMIZER-WORKER-MAIN; E41S05 AC-EXIT-CODE-RUNTIME wiring.
 *
 * @see WorkerConfigLoader
 * @see de.vvwt.slotopt.standalone.internal.DefaultWorkerConfigLoader
 */
@Command(
        name = "vvwt-slotopt-standalone-worker",
        mixinStandardHelpOptions = true,
        version = "vvwt-slotopt-standalone-worker E41S05",
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

        // Construct HTTP client and result signer
        DispatcherClient dispatcherClient =
                new DefaultDispatcherClient(config.dispatcherUrl(), config.httpTimeout());
        ResultSigner resultSigner = new DefaultResultSigner(workerKeyManager, config);

        // Bootstrap phase: validate algorithm + register key → obtain workerId
        UUID workerId;
        try {
            workerId = new DefaultBootstrapService(dispatcherClient).run(config);
        } catch (BootstrapException e) {
            System.err.println("ERROR: Bootstrap failed: " + e.getMessage());
            System.exit(e.getExitCode());
            return; // unreachable
        }

        // Construct runtime infrastructure
        StructuredLogger logger = new DefaultStructuredLogger();
        CpuThrottle cpuThrottle = new DefaultCpuThrottle();

        // Runtime polling loop
        WorkerLoop workerLoop =
                new DefaultWorkerLoop(
                        dispatcherClient,
                        resultSigner,
                        workerKeyManager,
                        config,
                        cpuThrottle,
                        logger,
                        workerId);
        try {
            workerLoop.run();
            System.exit(0);
        } catch (WorkerLoopException e) {
            System.err.println("ERROR: Worker loop terminated: " + e.getMessage());
            System.exit(e.getExitCode());
        }
    }
}
