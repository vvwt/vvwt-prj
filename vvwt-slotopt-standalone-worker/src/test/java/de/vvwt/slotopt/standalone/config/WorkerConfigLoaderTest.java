package de.vvwt.slotopt.standalone.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.slotopt.standalone.WorkerConfig;
import de.vvwt.slotopt.standalone.WorkerConfigLoader;
import de.vvwt.slotopt.standalone.internal.DefaultWorkerConfigLoader;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cross-package tests for {@link WorkerConfigLoader} interface and {@link
 * DefaultWorkerConfigLoader} implementation.
 *
 * <p>DEC-36 compliance: this test class is in {@code de.vvwt.slotopt.standalone.config} (different
 * package from the subjects). It references the subject via the public interface {@link
 * WorkerConfigLoader}, NOT via the concrete {@code DefaultWorkerConfigLoader} class — except where
 * testing the concrete constructor (factory creation) and where white-box testing internal
 * validation is not accessible via the interface alone.
 *
 * <p>AC-DEC36-CROSS-PACKAGE-WORKER-CONFIG-LOADER-TEST (E41S02): per AC, this test class validates:
 *
 * <ol>
 *   <li>load with all-required-args returns valid WorkerConfig
 *   <li>load with missing required options exits non-zero
 *   <li>load with invalid {@code --signing-algorithm} rejects with explicit error
 * </ol>
 *
 * <p>TDD Iron Law (DEC-22): written RED before {@link WorkerConfigLoader} and {@link
 * DefaultWorkerConfigLoader} exist. Per AC-TDD-RED-FIRST-EVIDENCE.
 */
class WorkerConfigLoaderTest {

    /**
     * Subject via public interface (DEC-36 cross-package typing rule).
     *
     * <p>Note: DefaultWorkerConfigLoader is referenced in {@link #setUp()} to instantiate the
     * concrete impl — this is necessary since the interface cannot be instantiated directly. The
     * field type is {@code WorkerConfigLoader} (interface), not {@code DefaultWorkerConfigLoader}.
     */
    private WorkerConfigLoader loader;

    @BeforeEach
    void setUp() {
        // Instantiate concrete impl but store as interface type (DEC-36)
        loader = new DefaultWorkerConfigLoader();
    }

    // =========================================================================
    // AC-WORKER-CONFIG-LOADER: load with all required args returns valid WorkerConfig
    // =========================================================================

    @Test
    @DisplayName("load with all required args returns valid WorkerConfig")
    void load_allRequiredArgs_returnsValidConfig() {
        // per AC-WORKER-CONFIG-LOADER, AC-DEC36-CROSS-PACKAGE-WORKER-CONFIG-LOADER-TEST
        WorkerConfig config =
                loader.load(
                        new String[] {
                            "--dispatcher-url", "https://dispatcher.example.com",
                            "--key-dir", "/tmp/keys"
                        });

        assertThat(config).isNotNull();
        assertThat(config.dispatcherUrl().toString()).isEqualTo("https://dispatcher.example.com");
        assertThat(config.keyDir().toString()).isEqualTo("/tmp/keys");
        // defaults per E37S02 spec § (c)
        assertThat(config.signingAlgorithm()).isEqualTo("Ed25519");
        assertThat(config.pollInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.httpTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.cpuThrottlePercent()).isEqualTo(50);
        assertThat(config.logFormat()).isEqualTo("text");
    }

    @Test
    @DisplayName("load with explicit --signing-algorithm Ed25519 accepts and returns config")
    void load_explicitEd25519Algorithm_accepted() {
        // per AC-SIGNING-ALGORITHM-FLAG: explicit 'Ed25519' accepted
        WorkerConfig config =
                loader.load(
                        new String[] {
                            "--dispatcher-url", "https://dispatcher.example.com",
                            "--key-dir", "/tmp/keys",
                            "--signing-algorithm", "Ed25519"
                        });

        assertThat(config.signingAlgorithm()).isEqualTo("Ed25519");
    }

    @Test
    @DisplayName("load with all optional args overrides defaults")
    void load_allOptionalArgs_overridesDefaults() {
        // per AC-WORKER-CONFIG-LOADER: optional fields override defaults
        WorkerConfig config =
                loader.load(
                        new String[] {
                            "--dispatcher-url", "http://localhost:9090",
                            "--key-dir", "/data/keys",
                            "--poll-interval", "PT60S",
                            "--http-timeout", "PT15S",
                            "--max-cpu-percent", "75",
                            "--name", "my-worker",
                            "--log-format", "json"
                        });

        assertThat(config.pollInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.httpTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(config.cpuThrottlePercent()).isEqualTo(75);
        assertThat(config.workerName()).isEqualTo("my-worker");
        assertThat(config.logFormat()).isEqualTo("json");
    }

    // =========================================================================
    // AC-SIGNING-ALGORITHM-FLAG: V1 rejects non-Ed25519 values
    // =========================================================================

    @Test
    @DisplayName("load with --signing-algorithm ML-DSA-65 is rejected with V1-only error")
    void load_signingAlgorithmMlDsa65_isRejected() {
        // per AC-SIGNING-ALGORITHM-FLAG: 'ML-DSA-65' rejected with explicit V1-only error
        assertThatThrownBy(
                        () ->
                                loader.load(
                                        new String[] {
                                            "--dispatcher-url", "https://dispatcher.example.com",
                                            "--key-dir", "/tmp/keys",
                                            "--signing-algorithm", "ML-DSA-65"
                                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ML-DSA-65")
                .hasMessageContaining("Ed25519")
                .hasMessageContaining("DEC-43");
    }

    @Test
    @DisplayName("load with --signing-algorithm INVALID is rejected with V1-only error")
    void load_signingAlgorithmInvalid_isRejected() {
        // per AC-SIGNING-ALGORITHM-FLAG: any non-Ed25519 value rejected
        assertThatThrownBy(
                        () ->
                                loader.load(
                                        new String[] {
                                            "--dispatcher-url", "https://dispatcher.example.com",
                                            "--key-dir", "/tmp/keys",
                                            "--signing-algorithm", "INVALID"
                                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID")
                .hasMessageContaining("Ed25519")
                .hasMessageContaining("DEC-43");
    }

    @Test
    @DisplayName("load with default (no --signing-algorithm) returns 'Ed25519'")
    void load_defaultSigningAlgorithm_isEd25519() {
        // per AC-SIGNING-ALGORITHM-FLAG: default value 'Ed25519' when flag absent
        WorkerConfig config =
                loader.load(
                        new String[] {
                            "--dispatcher-url", "https://dispatcher.example.com",
                            "--key-dir", "/tmp/keys"
                        });

        assertThat(config.signingAlgorithm()).isEqualTo("Ed25519");
    }

    // =========================================================================
    // AC-FAIL-FAST-MISSING-REQUIRED: exit non-zero on missing required options
    // =========================================================================

    @Test
    @DisplayName(
            "load with missing --dispatcher-url throws IllegalStateException with non-zero exit"
                    + " signal")
    void load_missingDispatcherUrl_throwsIllegalStateException() {
        // per AC-FAIL-FAST-MISSING-REQUIRED: missing required option causes fail-fast
        // DefaultWorkerConfigLoader uses CommandLine.execute() which returns exit code;
        // load() throws IllegalStateException with the exit code when non-zero
        assertThatThrownBy(() -> loader.load(new String[] {"--key-dir", "/tmp/keys"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exit")
                .satisfies(
                        e -> {
                            // message should contain the exit code (non-zero)
                            assertThat(e.getMessage()).containsPattern("exit.*[1-9]|[1-9].*exit");
                        });
    }

    @Test
    @DisplayName(
            "load with missing --key-dir throws IllegalStateException with non-zero exit signal")
    void load_missingKeyDir_throwsIllegalStateException() {
        // per AC-FAIL-FAST-MISSING-REQUIRED: missing required --key-dir causes fail-fast
        assertThatThrownBy(
                        () ->
                                loader.load(
                                        new String[] {
                                            "--dispatcher-url", "https://dispatcher.example.com"
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exit");
    }

    // =========================================================================
    // Verify WorkerConfigLoader is an interface (AC-WORKER-CONFIG-LOADER)
    // =========================================================================

    @Test
    @DisplayName("WorkerConfigLoader is a Java interface")
    void workerConfigLoader_isInterface() {
        // per AC-WORKER-CONFIG-LOADER: WorkerConfigLoader is declared as interface
        assertThat(WorkerConfigLoader.class.isInterface()).isTrue();
    }

    @Test
    @DisplayName("DefaultWorkerConfigLoader implements WorkerConfigLoader")
    void defaultWorkerConfigLoader_implementsInterface() {
        // per AC-WORKER-CONFIG-LOADER: DefaultWorkerConfigLoader implements WorkerConfigLoader
        assertThat(loader).isInstanceOf(WorkerConfigLoader.class);
        assertThatCode(
                        () ->
                                loader.load(
                                        new String[] {
                                            "--dispatcher-url", "https://example.com",
                                            "--key-dir", "/tmp/k"
                                        }))
                .doesNotThrowAnyException();
    }
}
