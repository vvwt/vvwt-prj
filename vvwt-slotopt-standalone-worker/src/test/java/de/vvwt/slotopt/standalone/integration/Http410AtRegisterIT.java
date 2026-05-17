// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.integration;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test: HTTP 410 at register-key (race-condition path).
 *
 * <p>Simulates the rare race-condition where the algorithm's deprecation deadline crosses between
 * the announcement query and the register-key call:
 *
 * <ul>
 *   <li>GET /api/algorithms returns Ed25519 with a future {@code deprecation_date} → algorithm
 *       appears valid at announcement time
 *   <li>POST /api/register-key returns HTTP 410 Gone → dispatcher enforces DEC-43 D3 server-side
 *       (algorithm deprecated at registration time)
 * </ul>
 *
 * <p>Per E41S04 AC-EXIT-CODE-BOOTSTRAP "registration_rejected_deprecated" — the worker logs ERROR
 * and exits with {@link ExitCode#REGISTRATION_REJECTED_DEPRECATED} (78 = EX_CONFIG).
 *
 * <p>Note: HTTP 410 at submit-result is NOT a DEC-43-specified path — DEC-43 D3 explicitly states
 * "Signature verifications for pre-existing registrations using a now-deprecated algorithm continue
 * to succeed." The 410 deprecation enforcement is at register-key only.
 *
 * <p>Story: E41S06 AC-INTEGRATION-TEST-HTTP-410-AT-REGISTER.
 */
class Http410AtRegisterIT {

    private DispatcherStub stub;
    private WorkerLauncher launcher;

    @BeforeEach
    void setUp() throws java.io.IOException {
        stub = new DispatcherStub();
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    @DisplayName(
            "AC-INTEGRATION-TEST-HTTP-410-AT-REGISTER: worker exits EX_CONFIG (78) when"
                    + " register-key returns HTTP 410")
    void http410AtRegister_registrationRejected_exitsWithExitCode78() {
        // Algorithm announced with future deprecation_date (race-condition: appears valid)
        LocalDate futureDate = LocalDate.now().plusMonths(3);
        stub.stubRegisterKeyReturns410(futureDate);
        launcher = new WorkerLauncher(stub.baseUri());

        WorkerRunResult result = launcher.launch();

        // Worker must exit with EX_CONFIG (78) = registration_rejected_deprecated
        assertThat(result.exitCode())
                .as(
                        "Worker must exit 78 (EX_CONFIG) when register-key returns HTTP 410;"
                                + " stderr: "
                                + result.stderr())
                .isEqualTo(ExitCode.REGISTRATION_REJECTED_DEPRECATED);

        // Stderr must contain an error message about registration rejection
        assertThat(result.stderr())
                .as("Stderr must contain error message about registration rejection")
                .containsIgnoringCase("register");
    }
}
