package de.vvwt.slotopt.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkerConfig} record.
 *
 * <p>TDD Iron Law (DEC-22): written in RED state before WorkerConfig class exists. Tests authored
 * RED-first per AC-TDD-RED-FIRST-EVIDENCE (E41S02).
 *
 * <p>Same-package test (DEC-36): located in {@code de.vvwt.slotopt.standalone} — may reference
 * record fields directly.
 */
class WorkerConfigTest {

    @Test
    @DisplayName("WorkerConfig record holds all required fields with correct types")
    void workerConfig_allFields_constructedCorrectly() {
        // per AC-WORKER-CONFIG-RECORD (E41S02) + E37S02 spec § (c) CLI Argument Schema
        URI dispatcherUrl = URI.create("http://localhost:8080");
        Path keyDir = Path.of("/tmp/keys");
        Duration pollInterval = Duration.ofSeconds(30); // default per E37S02 spec § (c)
        String signingAlgorithm = "Ed25519"; // DEC-43 D4 V1 set
        Duration httpTimeout = Duration.ofSeconds(30);
        int cpuThrottlePercent = 50; // default per E01S05 AC1
        String workerName = "test-worker"; // optional per E01S05 AC1
        String logFormat = "text"; // default per E01S05 AC8

        WorkerConfig config =
                new WorkerConfig(
                        dispatcherUrl,
                        keyDir,
                        pollInterval,
                        signingAlgorithm,
                        httpTimeout,
                        cpuThrottlePercent,
                        workerName,
                        logFormat);

        assertThat(config.dispatcherUrl()).isEqualTo(dispatcherUrl);
        assertThat(config.keyDir()).isEqualTo(keyDir);
        assertThat(config.pollInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.signingAlgorithm()).isEqualTo("Ed25519");
        assertThat(config.httpTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.cpuThrottlePercent()).isEqualTo(50);
        assertThat(config.workerName()).isEqualTo("test-worker");
        assertThat(config.logFormat()).isEqualTo("text");
    }

    @Test
    @DisplayName("WorkerConfig record equality by value")
    void workerConfig_valueEquality() {
        // per E37S02 spec § (c) — record equality is structural
        URI url = URI.create("https://dispatcher.example.com");
        Path keyDir = Path.of("/keys");
        WorkerConfig c1 =
                new WorkerConfig(
                        url,
                        keyDir,
                        Duration.ofSeconds(30),
                        "Ed25519",
                        Duration.ofSeconds(10),
                        50,
                        null,
                        "text");
        WorkerConfig c2 =
                new WorkerConfig(
                        url,
                        keyDir,
                        Duration.ofSeconds(30),
                        "Ed25519",
                        Duration.ofSeconds(10),
                        50,
                        null,
                        "text");

        assertThat(c1).isEqualTo(c2);
    }

    @Test
    @DisplayName("WorkerConfig dispatcherUrl is required (URI type enforced)")
    void workerConfig_dispatcherUrl_isUriType() {
        // per AC-WORKER-CONFIG-RECORD: dispatcherUrl typed as java.net.URI (not String)
        WorkerConfig config =
                new WorkerConfig(
                        URI.create("https://example.com/dispatcher"),
                        Path.of("/keys"),
                        Duration.ofSeconds(30),
                        "Ed25519",
                        Duration.ofSeconds(30),
                        50,
                        null,
                        "text");

        assertThat(config.dispatcherUrl())
                .isInstanceOf(URI.class)
                .hasToString("https://example.com/dispatcher");
    }
}
