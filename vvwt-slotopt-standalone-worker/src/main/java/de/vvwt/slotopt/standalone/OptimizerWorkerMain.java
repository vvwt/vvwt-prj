package de.vvwt.slotopt.standalone;

import de.vvwt.slotopt.standalone.internal.DefaultWorkerConfigLoader;
import picocli.CommandLine.Command;

/**
 * Entry point for the vvwt-slotopt-standalone-worker process.
 *
 * <p>This class is the Picocli {@code @Command} skeleton for the standalone worker per E41S02 /
 * AC-OPTIMIZER-WORKER-MAIN. At E41S02, {@link #main(String[])} loads the configuration via {@link
 * WorkerConfigLoader} and exits 0 after successful config load — the runtime loop (WorkerLoop,
 * CpuThrottle, StructuredLogger) is added by E41S05.
 *
 * <p>Actual option parsing (all {@code @Option} annotations) lives in {@link
 * de.vvwt.slotopt.standalone.internal.DefaultWorkerConfigLoader}, which carries the Picocli
 * {@code @Command} definition. {@code OptimizerWorkerMain.main()} constructs a {@link
 * WorkerConfigLoader} and delegates configuration loading to it.
 *
 * @see WorkerConfigLoader
 * @see de.vvwt.slotopt.standalone.internal.DefaultWorkerConfigLoader
 */
@Command(
        name = "vvwt-slotopt-standalone-worker",
        mixinStandardHelpOptions = true,
        version = "vvwt-slotopt-standalone-worker E41S02",
        description =
                "VVW Slot-Opt Standalone Worker — headless compute process. Registers with the"
                        + " dispatcher, pulls packets, and submits signed results.")
public class OptimizerWorkerMain {

    /**
     * Main entry point. Delegates to {@link WorkerConfigLoader#load(String[])} to parse and
     * validate CLI arguments, then exits 0 after successful config load.
     *
     * <p>At E41S02: exits cleanly after config load (placeholder for E41S05 runtime loop entry).
     * Missing required args or invalid {@code --signing-algorithm} cause the loader to throw, which
     * propagates here.
     *
     * @param args CLI arguments forwarded from the JVM launcher
     */
    public static void main(String[] args) {
        WorkerConfigLoader loader = new DefaultWorkerConfigLoader();
        // Load and validate configuration — throws on missing required args or invalid algorithm.
        // E41S02: config loaded successfully. E41S05 replaces the next line with WorkerLoop.run().
        WorkerConfig config = loader.load(args);
        // config intentionally used to avoid "unused variable" linting issues; future story
        // passes it to WorkerLoop.
        assert config != null : "WorkerConfigLoader must return non-null WorkerConfig";
    }
}
