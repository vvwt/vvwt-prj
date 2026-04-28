package de.vvwt.slotopt.standalone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the updated {@link OptimizerWorkerMain} class with Picocli {@code @Command} skeleton
 * and delegation to {@link WorkerConfigLoader}.
 *
 * <p>TDD Iron Law (DEC-22): written RED before OptimizerWorkerMain's {@code @Command} annotation
 * and {@code main()} delegation exist. Per AC-TDD-RED-FIRST-EVIDENCE (E41S02).
 *
 * <p>Same-package test: in {@code de.vvwt.slotopt.standalone} — may reference the class directly.
 *
 * <p>Note on exit-code testing: {@code OptimizerWorkerMain.main()} must NOT call {@code
 * System.exit()} for valid-args invocations (exits 0 per AC-OPTIMIZER-WORKER-MAIN). Tests verify
 * this via {@code assertThatCode(() -> OptimizerWorkerMain.main(args)).doesNotThrowAnyException()}.
 */
class OptimizerWorkerMainTest {

    @Test
    @DisplayName("OptimizerWorkerMain.main() with valid args exits 0 (no exception thrown)")
    void main_validArgs_exitsZero() {
        // per AC-OPTIMIZER-WORKER-MAIN: valid args → config loaded, exits 0 after config load
        // At E41S02, main() loads config then exits cleanly (placeholder for E41S05 runtime).
        assertThatCode(
                        () ->
                                OptimizerWorkerMain.main(
                                        new String[] {
                                            "--dispatcher-url", "https://dispatcher.example.com",
                                            "--key-dir", "/tmp/keys"
                                        }))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("OptimizerWorkerMain class has a static main(String[]) method")
    void main_methodExists() {
        // per AC-OPTIMIZER-WORKER-MAIN: main(String[] args) entry point must exist
        assertThatCode(
                        () -> {
                            java.lang.reflect.Method m =
                                    OptimizerWorkerMain.class.getMethod("main", String[].class);
                            assertThat(java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                                    .isTrue();
                            assertThat(m.getReturnType()).isEqualTo(void.class);
                        })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "OptimizerWorkerMain.main() with invalid --signing-algorithm throws"
                    + " IllegalArgumentException")
    void main_invalidSigningAlgorithm_propagatesException() {
        // per AC-SIGNING-ALGORITHM-FLAG: invalid algorithm rejected before dispatcher contact
        // main() propagates the IllegalArgumentException from WorkerConfigLoader
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                OptimizerWorkerMain.main(
                                        new String[] {
                                            "--dispatcher-url", "https://dispatcher.example.com",
                                            "--key-dir", "/tmp/keys",
                                            "--signing-algorithm", "ML-DSA-65"
                                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ML-DSA-65")
                .hasMessageContaining("DEC-43");
    }

    @Test
    @DisplayName("OptimizerWorkerMain context-load test (E41S01) still passes with @Command update")
    void main_classIsLoadable_e41s01Regression() {
        // Regression guard: AC-OPTIMIZER-WORKER-MAIN states "The first context-load test from
        // E41S01 now turns GREEN as OptimizerWorkerMain class exists." — still holds after update.
        assertThatCode(() -> Class.forName("de.vvwt.slotopt.standalone.OptimizerWorkerMain"))
                .doesNotThrowAnyException();
    }
}
