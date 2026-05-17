// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.e2e;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DispatcherProcessLauncher} output-capture diagnostics (E63S09).
 *
 * <p>Covers {@code AC-TEST-DEATH-FAILURE-CARRIES-OUTPUT}: when the dispatcher subprocess dies
 * before becoming ready, the {@link AssertionError} surfaced by {@link
 * DispatcherProcessLauncher#startWithCommand} must contain the subprocess's captured output so
 * the cause is identifiable from the build log without a manual re-launch.
 *
 * <h2>Failure-path mechanism</h2>
 *
 * <p>The test uses {@link DispatcherProcessLauncher#startWithCommand} to inject a controlled
 * subprocess command ({@link SentinelAndExitMain}) that prints a known sentinel line to stderr
 * and exits immediately with code 1, bypassing the real dispatcher JAR resolution. The
 * {@link DispatcherProcessLauncher} is constructed on an arbitrary free port; the subprocess
 * will never serve HTTP — it is expected to die immediately.
 */
class DispatcherProcessLauncherTest {

    /**
     * AC-TEST-DEATH-FAILURE-CARRIES-OUTPUT: subprocess dies before ready — captured output is
     * included in the failure message.
     *
     * <p>Regression guard: prior to E63S09, {@link DispatcherProcessLauncher} used {@code
     * redirectOutput(Redirect.DISCARD)}, so subprocess output was silently discarded and the
     * {@link AssertionError} contained only the generic "process died" message — the root cause
     * was invisible without a manual re-launch.
     *
     * <p>TDD: this test was written RED against the pre-fix {@link DispatcherProcessLauncher}
     * (which used DISCARD and had no {@code startWithCommand} method) and turned GREEN after
     * the {@link OutputGobbler} + {@code startWithCommand} refactor (E63S09).
     */
    @Test
    @DisplayName(
            "AC-TEST-DEATH-FAILURE-CARRIES-OUTPUT: death-before-ready failure message"
                    + " contains captured subprocess output")
    void deathBeforeReady_failureMessageContainsCapturedOutput() throws Exception {
        // Arrange: build the command for SentinelAndExitMain (prints sentinel to stderr, exits 1).
        String javaBinary = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");
        List<String> command =
                List.of(
                        javaBinary,
                        "-cp",
                        classpath,
                        SentinelAndExitMain.class.getName());

        // Use an arbitrary port; the subprocess will die before any readiness probe is attempted.
        DispatcherProcessLauncher launcher = new DispatcherProcessLauncher(0);

        // Act + Assert: startWithCommand() must throw AssertionError whose message contains the
        // sentinel line printed by SentinelAndExitMain.
        assertThatThrownBy(() -> launcher.startWithCommand(command))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(SentinelAndExitMain.SENTINEL_LINE);
    }
}
