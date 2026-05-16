// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubmitJobResponse}.
 *
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S07). Written before production
 * class.
 *
 * <p>Story: E37S07; AC-SUBMIT-JOB-DTOs
 */
class SubmitJobResponseTest {

    @Test
    void constructorPreservesFields() {
        UUID jobId = UUID.randomUUID();
        Instant submittedAt = Instant.now();

        SubmitJobResponse response = new SubmitJobResponse(jobId, submittedAt);

        assertThat(response.jobId()).isEqualTo(jobId);
        assertThat(response.submittedAt()).isEqualTo(submittedAt);
    }
}
