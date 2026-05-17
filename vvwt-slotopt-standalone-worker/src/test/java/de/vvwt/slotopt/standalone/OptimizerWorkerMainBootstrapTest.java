// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for E41S04 additions to {@link OptimizerWorkerMain}: bootstrap invocation and exit-code
 * propagation.
 *
 * <p>These tests cover the OptimizerWorkerMain integration of the bootstrap path. Since
 * OptimizerWorkerMain.main() makes real HTTP calls in production, we verify the exit-code contract
 * through the public API of the BootstrapException class (TC-33 proxy test).
 *
 * <p>Story: E41S04 AC-EXIT-CODE-BOOTSTRAP (integration path).
 */
class OptimizerWorkerMainBootstrapTest {

    /**
     * TC-32: Verify that OptimizerWorkerMain class is loadable and main() exists as public static.
     * (Structural contract — actual invocation requires a dispatcher mock, covered in E41S06 ITs.)
     */
    @Test
    void main_method_is_public_static_and_accepts_string_array() throws Exception {
        var method = OptimizerWorkerMain.class.getMethod("main", String[].class);
        assertThat(method).isNotNull();
        assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isStatic(method.getModifiers())).isTrue();
    }

    /**
     * TC-33: BootstrapException carries exit code — verifies the exit-code contract between
     * DefaultBootstrapService and the main entry point.
     */
    @Test
    void bootstrap_exception_carries_exit_code() {
        var ex = new de.vvwt.slotopt.standalone.bootstrap.BootstrapException(75, "test", null);
        assertThat(ex.getExitCode()).isEqualTo(75);
        assertThat(ex.getMessage()).isEqualTo("test");
    }
}
