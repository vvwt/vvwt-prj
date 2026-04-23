package de.vvwt.standalone;

import de.vvwt.standalone.config.WorkerConfig;
import de.vvwt.standalone.config.WorkerConfigLoader;
import de.vvwt.standalone.crypto.ResultSigner;
import de.vvwt.standalone.http.DispatcherClient;
import de.vvwt.standalone.http.DispatcherException;
import de.vvwt.standalone.log.StructuredLogger;
import de.vvwt.standalone.runtime.CpuThrottle;
import de.vvwt.standalone.runtime.WorkerLoop;
import de.vvwt.worker.identity.WorkerKeyManager;
import de.vvwt.worker.identity.internal.DefaultWorkerKeyManager;
import de.vvwt.worker.score.VarietyScorer;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Entry point for the standalone headless optimizer worker.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>Parse CLI + config file via {@link WorkerConfigLoader} (AC1)
 *   <li>Warn if dispatcher URL uses plain HTTP (AC10)
 *   <li>Initialize keypair via {@link WorkerKeyManager} (AC2)
 *   <li>Register public key with dispatcher if first run (AC2)
 *   <li>Enter the main pull → solve → submit loop via {@link WorkerLoop} (AC3)
 * </ol>
 *
 * <p>Exit codes follow sysexits.h:
 *
 * <ul>
 *   <li>0 — clean stop
 *   <li>75 (EX_TEMPFAIL) — transient failures; supervisor should restart
 *   <li>78 (EX_CONFIG) — permanent error; manual intervention required
 * </ul>
 *
 * <p>Implements Story E01S05 AC1–AC10.
 */
@Command(
        name = "optimizer-worker",
        description = "VVW standalone headless worker.",
        mixinStandardHelpOptions = true,
        versionProvider = OptimizerWorkerMain.VersionProvider.class)
public class OptimizerWorkerMain {

    private static final Logger ROOT_LOGGER = LoggerFactory.getLogger(OptimizerWorkerMain.class);

    /** Exit code: permanent configuration error (sysexits.h EX_CONFIG). */
    public static final int EXIT_CONFIG = 78;

    /** Exit code: transient failure — supervisor should restart (sysexits.h EX_TEMPFAIL). */
    public static final int EXIT_TEMPFAIL = 75;

    // -------------------------------------------------------------------------
    // Main
    // -------------------------------------------------------------------------

    /**
     * Application entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        WorkerConfigLoader loader = new WorkerConfigLoader();
        CommandLine cmd = new CommandLine(loader);

        int parseResult;
        try {
            parseResult = cmd.execute(args);
        } catch (CommandLine.ParameterException e) {
            System.err.println("Error: " + e.getMessage());
            cmd.usage(System.err);
            System.exit(EXIT_CONFIG);
            return;
        }

        if (parseResult != 0) {
            // --help or --version was printed by picocli; exit cleanly
            System.exit(parseResult);
            return;
        }

        WorkerConfig config;
        try {
            config = loader.getResolvedConfig();
        } catch (Exception e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(EXIT_CONFIG);
            return;
        }

        int exitCode = run(config);
        System.exit(exitCode);
    }

    /**
     * Runs the worker with the provided configuration. Package-visible for integration testing.
     *
     * @param config validated worker configuration
     * @return process exit code
     */
    static int run(WorkerConfig config) {
        StructuredLogger log = new StructuredLogger(ROOT_LOGGER, config.logFormatJson());

        // AC8: log effective config at startup
        log.info(
                "startup",
                "Optimizer worker starting",
                "dispatcherUrl",
                config.dispatcherUrl(),
                "dataDir",
                config.dataDir(),
                "maxCpuPercent",
                config.maxCpuPercent(),
                "idlePollSeconds",
                config.idlePollSeconds(),
                "name",
                config.name() != null ? config.name() : "(unset)",
                "logFormat",
                config.logFormatJson() ? "json" : "plain");

        // AC10: warn on plain HTTP
        if (config.dispatcherUrl().startsWith("http://")) {
            log.warn(
                    "insecure-url",
                    "Dispatcher URL uses plain HTTP — communication is unencrypted",
                    "dispatcherUrl",
                    config.dispatcherUrl());
        }

        // AC2: initialize keypair
        WorkerKeyManager keyManager;
        try {
            keyManager = new DefaultWorkerKeyManager(config.dataDir(), ROOT_LOGGER);
        } catch (Exception e) {
            log.error(
                    "keypair-error",
                    "Failed to initialize keypair: " + e.getMessage(),
                    "dataDir",
                    config.dataDir());
            return EXIT_CONFIG;
        }

        // AC2: register if needed
        DispatcherClient dispatcherClient = new DispatcherClient(config.dispatcherUrl());
        UUID workerKeyId;
        try {
            workerKeyId = registerIfNeeded(keyManager, dispatcherClient, config, log);
        } catch (DispatcherException ex) {
            if (ex.isClientError()) {
                log.error(
                        "registration-rejected",
                        "Dispatcher rejected key registration — exiting (EX_CONFIG)",
                        "statusCode",
                        ex.getStatusCode(),
                        "responseBody",
                        ex.getResponseBody());
                return EXIT_CONFIG;
            }
            // Network or 5xx during registration — propagate as transient failure
            log.error(
                    "registration-failed",
                    "Failed to register with dispatcher — exiting (EX_TEMPFAIL)",
                    "statusCode",
                    ex.getStatusCode(),
                    "error",
                    ex.getMessage());
            return EXIT_TEMPFAIL;
        } catch (IOException e) {
            log.error("registration-io-error", "I/O error during registration: " + e.getMessage());
            return EXIT_TEMPFAIL;
        }

        // AC3–AC7: main loop
        ResultSigner resultSigner = new ResultSigner(keyManager);
        CpuThrottle cpuThrottle = new CpuThrottle(config.maxCpuPercent());
        WorkerLoop loop =
                new WorkerLoop(
                        config, dispatcherClient, resultSigner, cpuThrottle, log, workerKeyId);

        return loop.run();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Registers the worker key with the dispatcher on first run. On subsequent runs (key file
     * already exists before startup), checks if the key is already registered by attempting
     * registration — the dispatcher is idempotent (200 on match).
     *
     * <p>Per AC2: on first run, generate + register. On subsequent runs, skip re-registration (the
     * dispatcher already knows the public key). The implementation calls register-key only on the
     * first session; subsequent startups detect the existing key file and skip the call.
     *
     * @return the worker key UUID assigned by the dispatcher
     */
    private static UUID registerIfNeeded(
            WorkerKeyManager keyManager,
            DispatcherClient dispatcherClient,
            WorkerConfig config,
            StructuredLogger log)
            throws DispatcherException, IOException {
        // Check if keypair was freshly generated (no .registered marker) or pre-existing
        boolean registrationMarkerExists =
                config.dataDir().resolve(".registered").toFile().exists();

        if (registrationMarkerExists) {
            // Read the stored key ID
            String storedKeyId = readRegisteredKeyId(config);
            if (storedKeyId != null) {
                log.info(
                        "registration-skipped",
                        "Key already registered, skipping re-registration",
                        "keyId",
                        storedKeyId);
                return UUID.fromString(storedKeyId);
            }
        }

        // Register (first run or missing marker)
        log.info("registering", "Registering worker key with dispatcher");
        byte[] publicKeyBytes = keyManager.getPublicKeyBytes();
        DispatcherClient.RegisterResult result =
                dispatcherClient.registerKey(publicKeyBytes, config.name());

        log.info(
                "registered",
                "Worker key registered successfully",
                "keyId",
                result.keyId(),
                "alreadyRegistered",
                result.alreadyRegistered());

        // Persist the registration marker
        writeRegisteredKeyId(config, result.keyId().toString());

        return result.keyId();
    }

    private static String readRegisteredKeyId(WorkerConfig config) throws IOException {
        java.nio.file.Path markerPath = config.dataDir().resolve(".registered");
        if (!markerPath.toFile().exists()) {
            return null;
        }
        String content = java.nio.file.Files.readString(markerPath).strip();
        return content.isEmpty() ? null : content;
    }

    private static void writeRegisteredKeyId(WorkerConfig config, String keyId) throws IOException {
        java.nio.file.Path markerPath = config.dataDir().resolve(".registered");
        java.nio.file.Files.writeString(markerPath, keyId);
    }

    // -------------------------------------------------------------------------
    // Version provider (AC9)
    // -------------------------------------------------------------------------

    /**
     * Provides version information for {@code --version} flag (AC9).
     *
     * <p>Prints: worker-lib version, SCORE_FN_VERSION, JVM version, OS info.
     */
    static final class VersionProvider implements CommandLine.IVersionProvider {

        @Override
        public String[] getVersion() {
            String workerLibVersion = getWorkerLibVersion();
            String scoreFnVersion = String.valueOf(VarietyScorer.SCORE_FN_VERSION);
            String jvmVersion = System.getProperty("java.version", "unknown");
            String jvmVendor = System.getProperty("java.vendor", "unknown");
            String osName = System.getProperty("os.name", "unknown");
            String osVersion = System.getProperty("os.version", "unknown");
            String osArch = System.getProperty("os.arch", "unknown");

            return new String[] {
                "optimizer-worker",
                "  worker-lib version : " + workerLibVersion,
                "  SCORE_FN_VERSION   : " + scoreFnVersion,
                "  JVM               : " + jvmVersion + " (" + jvmVendor + ")",
                "  OS                : " + osName + " " + osVersion + " (" + osArch + ")"
            };
        }

        private static String getWorkerLibVersion() {
            // Read from MANIFEST.MF Implementation-Version if present
            try {
                String v = VarietyScorer.class.getPackage().getImplementationVersion();
                return (v != null && !v.isBlank()) ? v : "1.0.0-SNAPSHOT";
            } catch (Exception e) {
                return "1.0.0-SNAPSHOT";
            }
        }
    }
}
