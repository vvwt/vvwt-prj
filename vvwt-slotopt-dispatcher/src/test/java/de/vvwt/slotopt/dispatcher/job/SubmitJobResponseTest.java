// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.result.OptimumResult;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubmitJobResponse}.
 *
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S07 + E60S04). Written before
 * production class.
 *
 * <p>Story: E37S07 + E60S04; AC-SUBMIT-JOB-DTOs; AC-TEST-CACHE-HIT-RESPONSE-USABLE
 */
class SubmitJobResponseTest {

    @Test
    void constructorPreservesFields() {
        UUID jobId = UUID.randomUUID();
        Instant submittedAt = Instant.now();

        SubmitJobResponse response = new SubmitJobResponse(jobId, submittedAt);

        assertThat(response.jobId()).isEqualTo(jobId);
        assertThat(response.submittedAt()).isEqualTo(submittedAt);
        assertThat(response.cacheHit()).isFalse();
        assertThat(response.cachedResult()).isNull();
    }

    @Test
    void fullConstructor_cacheHit_withOptimum() {
        // AC-TEST-CACHE-HIT-RESPONSE-USABLE: cachedResult is reachable from response
        UUID jobId = UUID.randomUUID();
        OptimumResult optimum = new OptimumResult(1, 42.5);
        SubmitJobResponse response = new SubmitJobResponse(jobId, null, true, optimum);

        assertThat(response.cacheHit()).isTrue();
        assertThat(response.cachedResult()).isNotNull();
        assertThat(response.cachedResult().bestRank()).isEqualTo(1);
        assertThat(response.cachedResult().bestScore()).isEqualTo(42.5);
    }
}
