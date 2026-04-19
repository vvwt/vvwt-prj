package de.vvwt.standalone.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Picocli command that parses CLI arguments and an optional {@code --config} properties file, then
 * produces a validated {@link WorkerConfig}.
 *
 * <p>Precedence (highest wins): explicit CLI args > config file values > built-in defaults.
 *
 * <p>Implements Story E01S05 AC1.
 */
@Command(
        name = "optimizer-worker",
        description =
                "VVW standalone headless worker — pulls and scores slot-optimization packets.",
        mixinStandardHelpOptions = true)
public class WorkerConfigLoader implements Callable<Integer> {

    // -------------------------------------------------------------------------
    // CLI options (AC1)
    // -------------------------------------------------------------------------

    @Option(
            names = {"--data-dir"},
            description = "Directory for keypair and state files (default: platform-specific).",
            paramLabel = "<path>")
    private String dataDirArg;

    @Option(
            names = {"--dispatcher-url"},
            description = "Base URL of the dispatcher service (required unless in --config file).",
            paramLabel = "<url>")
    private String dispatcherUrlArg;

    @Option(
            names = {"--max-cpu-percent"},
            description = "Maximum CPU utilisation percentage, 1–100 (default: 50).",
            paramLabel = "<percent>")
    private Integer maxCpuPercentArg;

    @Option(
            names = {"--name"},
            description = "Human-readable label used in audit logs (optional).",
            paramLabel = "<name>")
    private String nameArg;

    @Option(
            names = {"--idle-poll-seconds"},
            description = "Seconds to sleep when no packet is available (default: 30).",
            paramLabel = "<seconds>")
    private Integer idlePollSecondsArg;

    @Option(
            names = {"--config"},
            description =
                    "Path to a .properties file that provides default values for all options.",
            paramLabel = "<path>")
    private String configFileArg;

    @Option(
            names = {"--log-format"},
            description =
                    "Log format: 'json' for structured JSON logs, anything else for plain text.",
            paramLabel = "<format>")
    private String logFormatArg;

    /** Resolved config — set by {@link #buildConfig()}. */
    private WorkerConfig resolvedConfig;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Invoked by picocli when the command runs. Builds and validates the config. Returns exit code
     * 0 on success; the caller uses {@link #getResolvedConfig()}.
     */
    @Override
    public Integer call() {
        resolvedConfig = buildConfig();
        return 0;
    }

    /**
     * Returns the validated {@link WorkerConfig} after a successful {@link #call()}.
     *
     * @return non-null config
     * @throws IllegalStateException if called before {@link #call()} or if parsing failed
     */
    public WorkerConfig getResolvedConfig() {
        if (resolvedConfig == null) {
            throw new IllegalStateException(
                    "Config not yet resolved — call() has not been invoked successfully");
        }
        return resolvedConfig;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Merges config-file defaults with CLI args and applies built-in defaults. */
    WorkerConfig buildConfig() {
        Properties fileProps = new Properties();
        if (configFileArg != null && !configFileArg.isBlank()) {
            fileProps = loadPropertiesFile(configFileArg);
        }

        String dispatcherUrl = coalesce(dispatcherUrlArg, fileProps.getProperty("dispatcher-url"));
        if (dispatcherUrl == null || dispatcherUrl.isBlank()) {
            throw new CommandLine.ParameterException(
                    new CommandLine(this),
                    "--dispatcher-url is required (or set dispatcher-url in --config file)");
        }

        String dataDirStr = coalesce(dataDirArg, fileProps.getProperty("data-dir"));
        Path dataDir = dataDirStr != null ? Paths.get(dataDirStr) : defaultDataDir();

        int maxCpuPercent =
                resolveInt(
                        maxCpuPercentArg,
                        fileProps.getProperty("max-cpu-percent"),
                        WorkerConfig.DEFAULT_MAX_CPU_PERCENT);

        int idlePollSeconds =
                resolveInt(
                        idlePollSecondsArg,
                        fileProps.getProperty("idle-poll-seconds"),
                        WorkerConfig.DEFAULT_IDLE_POLL_SECONDS);

        String name = coalesce(nameArg, fileProps.getProperty("name"));

        boolean logFormatJson =
                "json"
                        .equalsIgnoreCase(
                                coalesce(logFormatArg, fileProps.getProperty("log-format")));

        return new WorkerConfig(
                dataDir, dispatcherUrl, maxCpuPercent, name, idlePollSeconds, logFormatJson);
    }

    private static Properties loadPropertiesFile(String path) {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config file: " + path, e);
        }
        return props;
    }

    /**
     * Platform-appropriate default data directory: Linux/macOS: {@code
     * ~/.local/share/optimizer-worker} Windows: {@code %APPDATA%\optimizer-worker}
     */
    static Path defaultDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                return Paths.get(appData, "optimizer-worker");
            }
        }
        return Paths.get(System.getProperty("user.home"), ".local", "share", "optimizer-worker");
    }

    private static String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static int resolveInt(Integer cliValue, String fileValue, int defaultValue) {
        if (cliValue != null) {
            return cliValue;
        }
        if (fileValue != null && !fileValue.isBlank()) {
            return Integer.parseInt(fileValue.trim());
        }
        return defaultValue;
    }
}
