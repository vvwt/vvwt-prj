package de.vvwt.standalone.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkerConfigLoader} — argument parsing and config file overlay.
 * Implements Story E01S05 AC1 test checkpoint 1.
 */
class WorkerConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void buildConfig_minimumRequiredArgs_succeeds() {
        WorkerConfigLoader loader = loaderWithArgs(
                "--dispatcher-url", "https://dispatcher.example.com"
        );
        loader.call();
        WorkerConfig cfg = loader.getResolvedConfig();

        assertThat(cfg.dispatcherUrl()).isEqualTo("https://dispatcher.example.com");
        assertThat(cfg.maxCpuPercent()).isEqualTo(WorkerConfig.DEFAULT_MAX_CPU_PERCENT);
        assertThat(cfg.idlePollSeconds()).isEqualTo(WorkerConfig.DEFAULT_IDLE_POLL_SECONDS);
    }

    @Test
    void buildConfig_allExplicitArgs_overridesDefaults() {
        WorkerConfigLoader loader = loaderWithArgs(
                "--dispatcher-url", "https://dispatcher.example.com",
                "--max-cpu-percent", "75",
                "--idle-poll-seconds", "15",
                "--name", "test-node",
                "--log-format", "json"
        );
        loader.call();
        WorkerConfig cfg = loader.getResolvedConfig();

        assertThat(cfg.maxCpuPercent()).isEqualTo(75);
        assertThat(cfg.idlePollSeconds()).isEqualTo(15);
        assertThat(cfg.name()).isEqualTo("test-node");
        assertThat(cfg.logFormatJson()).isTrue();
    }

    @Test
    void buildConfig_missingDispatcherUrl_throws() {
        WorkerConfigLoader loader = new WorkerConfigLoader();
        assertThatThrownBy(loader::buildConfig)
                .isInstanceOf(picocli.CommandLine.ParameterException.class)
                .hasMessageContaining("dispatcher-url");
    }

    @Test
    void buildConfig_configFileOverridesDefaults(@TempDir Path tmp) throws IOException {
        Path configFile = tmp.resolve("worker.properties");
        Files.writeString(configFile,
                "dispatcher-url=https://from-file.example.com\n" +
                "max-cpu-percent=30\n" +
                "idle-poll-seconds=60\n");

        WorkerConfigLoader loader = loaderWithArgs("--config", configFile.toString());
        loader.call();
        WorkerConfig cfg = loader.getResolvedConfig();

        assertThat(cfg.dispatcherUrl()).isEqualTo("https://from-file.example.com");
        assertThat(cfg.maxCpuPercent()).isEqualTo(30);
        assertThat(cfg.idlePollSeconds()).isEqualTo(60);
    }

    @Test
    void buildConfig_cliTakesPrecedenceOverFile(@TempDir Path tmp) throws IOException {
        Path configFile = tmp.resolve("worker.properties");
        Files.writeString(configFile,
                "dispatcher-url=https://from-file.example.com\n" +
                "max-cpu-percent=30\n");

        WorkerConfigLoader loader = loaderWithArgs(
                "--dispatcher-url", "https://override.example.com",
                "--max-cpu-percent", "80",
                "--config", configFile.toString()
        );
        loader.call();
        WorkerConfig cfg = loader.getResolvedConfig();

        assertThat(cfg.dispatcherUrl()).isEqualTo("https://override.example.com");
        assertThat(cfg.maxCpuPercent()).isEqualTo(80);
    }

    @Test
    void buildConfig_logFormatJson_recognizedCaseInsensitive() {
        WorkerConfigLoader loader = loaderWithArgs(
                "--dispatcher-url", "https://dispatcher.example.com",
                "--log-format", "JSON"
        );
        loader.call();
        assertThat(loader.getResolvedConfig().logFormatJson()).isTrue();
    }

    @Test
    void buildConfig_logFormatPlain_notJson() {
        WorkerConfigLoader loader = loaderWithArgs(
                "--dispatcher-url", "https://dispatcher.example.com",
                "--log-format", "plain"
        );
        loader.call();
        assertThat(loader.getResolvedConfig().logFormatJson()).isFalse();
    }

    @Test
    void defaultDataDir_isNotNull() {
        Path dataDir = WorkerConfigLoader.defaultDataDir();
        assertThat(dataDir).isNotNull();
        assertThat(dataDir.toString()).contains("optimizer-worker");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static WorkerConfigLoader loaderWithArgs(String... args) {
        WorkerConfigLoader loader = new WorkerConfigLoader();
        new picocli.CommandLine(loader).parseArgs(args);
        return loader;
    }
}
