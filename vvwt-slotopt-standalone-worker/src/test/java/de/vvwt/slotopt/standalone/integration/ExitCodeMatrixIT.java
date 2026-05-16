// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.integration;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test: exit code matrix verification per E37S02 spec § (c) "Exit Codes".
 *
 * <p>Verifies that the {@link ExitCode} constants match the spec table and that all exit code paths
 * documented in the spec are present in the implementation.
 *
 * <p>This is a unit-level structural test on the exit code constants (not a full integration test),
 * named *IT to run in the integration-test phase alongside the other ITs for organizational
 * consistency.
 *
 * <p>Exit code matrix per spec § (c):
 *
 * <ul>
 *   <li>0 — graceful shutdown
 *   <li>75 — EX_TEMPFAIL (dispatcher unreachable; supervisor should restart)
 *   <li>78 — EX_CONFIG (permanent error: algorithm not announced, algorithm deprecated past
 *       deadline, registration rejected deprecated, submit rejected deprecated)
 *   <li>130 — INTERRUPTED (thread interrupted; POSIX 128+SIGINT)
 * </ul>
 *
 * <p>Story: E41S06 AC-EXIT-CODE-MATRIX.
 */
class ExitCodeMatrixIT {

    @Test
    @DisplayName(
            "AC-EXIT-CODE-MATRIX: EX_TEMPFAIL (75) is used for dispatcher unreachable (bootstrap)")
    void exitCode_dispatcherUnreachable_bootstrap_is75() {
        assertThat(ExitCode.DISPATCHER_UNREACHABLE)
                .as("EX_TEMPFAIL (75) = dispatcher_unreachable at bootstrap")
                .isEqualTo(75);
    }

    @Test
    @DisplayName(
            "AC-EXIT-CODE-MATRIX: EX_TEMPFAIL (75) is used for dispatcher unreachable (runtime)")
    void exitCode_dispatcherUnreachable_runtime_is75() {
        assertThat(ExitCode.DISPATCHER_UNREACHABLE_RUNTIME)
                .as("EX_TEMPFAIL (75) = dispatcher_unreachable at runtime")
                .isEqualTo(75);
    }

    @Test
    @DisplayName("AC-EXIT-CODE-MATRIX: EX_CONFIG (78) is used for algorithm_not_announced")
    void exitCode_algorithmNotAnnounced_is78() {
        assertThat(ExitCode.ALGORITHM_NOT_ANNOUNCED)
                .as("EX_CONFIG (78) = algorithm_not_announced")
                .isEqualTo(78);
    }

    @Test
    @DisplayName(
            "AC-EXIT-CODE-MATRIX: EX_CONFIG (78) is used for algorithm_deprecated_past_deadline")
    void exitCode_algorithmDeprecatedPastDeadline_is78() {
        assertThat(ExitCode.ALGORITHM_DEPRECATED_PAST_DEADLINE)
                .as("EX_CONFIG (78) = algorithm_deprecated_past_deadline")
                .isEqualTo(78);
    }

    @Test
    @DisplayName("AC-EXIT-CODE-MATRIX: EX_CONFIG (78) is used for registration_rejected_deprecated")
    void exitCode_registrationRejectedDeprecated_is78() {
        assertThat(ExitCode.REGISTRATION_REJECTED_DEPRECATED)
                .as("EX_CONFIG (78) = registration_rejected_deprecated")
                .isEqualTo(78);
    }

    @Test
    @DisplayName("AC-EXIT-CODE-MATRIX: EX_CONFIG (78) is used for submit_rejected_deprecated")
    void exitCode_submitRejectedDeprecated_is78() {
        assertThat(ExitCode.SUBMIT_REJECTED_DEPRECATED)
                .as("EX_CONFIG (78) = submit_rejected_deprecated")
                .isEqualTo(78);
    }

    @Test
    @DisplayName("AC-EXIT-CODE-MATRIX: INTERRUPTED (130) is used for thread interrupt")
    void exitCode_interrupted_is130() {
        assertThat(ExitCode.INTERRUPTED)
                .as("INTERRUPTED (130) = thread interrupted (POSIX 128+SIGINT)")
                .isEqualTo(130);
    }
}
