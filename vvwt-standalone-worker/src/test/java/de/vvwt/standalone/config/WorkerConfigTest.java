package de.vvwt.standalone.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkerConfig} — validation and defaults. Implements Story E01S05 AC1 test
 * checkpoint 1.
 */
class WorkerConfigTest {

    @Test
    void constructor_validMinimalArgs_succeeds() {
        WorkerConfig cfg =
                new WorkerConfig(
                        Paths.get("/tmp/data"),
                        "https://dispatcher.example.com",
                        50,
                        null,
                        30,
                        false);
        assertThat(cfg.dataDir()).isEqualTo(Paths.get("/tmp/data"));
        assertThat(cfg.dispatcherUrl()).isEqualTo("https://dispatcher.example.com");
        assertThat(cfg.maxCpuPercent()).isEqualTo(50);
        assertThat(cfg.idlePollSeconds()).isEqualTo(30);
        assertThat(cfg.logFormatJson()).isFalse();
    }

    @Test
    void constructor_stripsTrailingSlash() {
        WorkerConfig cfg =
                new WorkerConfig(
                        Paths.get("/tmp/data"),
                        "https://dispatcher.example.com/",
                        50,
                        null,
                        30,
                        false);
        assertThat(cfg.dispatcherUrl()).isEqualTo("https://dispatcher.example.com");
    }

    @Test
    void constructor_nullDataDir_throws() {
        assertThatThrownBy(
                        () ->
                                new WorkerConfig(
                                        null,
                                        "https://dispatcher.example.com",
                                        50,
                                        null,
                                        30,
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataDir");
    }

    @Test
    void constructor_nullDispatcherUrl_throws() {
        assertThatThrownBy(
                        () -> new WorkerConfig(Paths.get("/tmp/data"), null, 50, null, 30, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dispatcherUrl");
    }

    @Test
    void constructor_blankDispatcherUrl_throws() {
        assertThatThrownBy(
                        () -> new WorkerConfig(Paths.get("/tmp/data"), "   ", 50, null, 30, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dispatcherUrl");
    }

    @Test
    void constructor_maxCpuPercent0_throws() {
        assertThatThrownBy(
                        () ->
                                new WorkerConfig(
                                        Paths.get("/tmp/data"),
                                        "https://dispatcher.example.com",
                                        0,
                                        null,
                                        30,
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCpuPercent");
    }

    @Test
    void constructor_maxCpuPercent101_throws() {
        assertThatThrownBy(
                        () ->
                                new WorkerConfig(
                                        Paths.get("/tmp/data"),
                                        "https://dispatcher.example.com",
                                        101,
                                        null,
                                        30,
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCpuPercent");
    }

    @Test
    void constructor_maxCpuPercent1_valid() {
        WorkerConfig cfg =
                new WorkerConfig(
                        Paths.get("/tmp/data"),
                        "https://dispatcher.example.com",
                        1,
                        null,
                        30,
                        false);
        assertThat(cfg.maxCpuPercent()).isEqualTo(1);
    }

    @Test
    void constructor_maxCpuPercent100_valid() {
        WorkerConfig cfg =
                new WorkerConfig(
                        Paths.get("/tmp/data"),
                        "https://dispatcher.example.com",
                        100,
                        null,
                        30,
                        false);
        assertThat(cfg.maxCpuPercent()).isEqualTo(100);
    }

    @Test
    void constructor_idlePollSeconds0_throws() {
        assertThatThrownBy(
                        () ->
                                new WorkerConfig(
                                        Paths.get("/tmp/data"),
                                        "https://dispatcher.example.com",
                                        50,
                                        null,
                                        0,
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idlePollSeconds");
    }
}
