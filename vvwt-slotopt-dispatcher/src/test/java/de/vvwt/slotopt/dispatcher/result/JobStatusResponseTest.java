// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JobStatusResponse}.
 *
 * <p>RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S09 + E60S04).
 *
 * <p>Story: E37S09 + E60S04; AC-JOB-STATUS-CONTROLLER; AC-TEST-JOB-STATUS-FINAL-RESULT;
 * AC-TEST-JOB-STATUS-BEST-SO-FAR; AC-TEST-JOB-STATUS-REPORTS-LIFECYCLE; DEC-22
 */
class JobStatusResponseTest {

    @Test
    void recordFieldsAreAccessible() {
        UUID jobId = UUID.randomUUID();
        JobStatusResponse resp = new JobStatusResponse(jobId, "RECEIVED", 10, 4, null, null);

        assertThat(resp.jobId()).isEqualTo(jobId);
        assertThat(resp.status()).isEqualTo("RECEIVED");
        assertThat(resp.totalPackets()).isEqualTo(10);
        assertThat(resp.completedPackets()).isEqualTo(4);
        assertThat(resp.finalResult()).isNull();
        assertThat(resp.bestSoFar()).isNull();
    }

    // -------------------------------------------------------------------------
    // AC-TEST-JOB-STATUS-FINAL-RESULT — E60S04 (RED-first)
    // -------------------------------------------------------------------------

    @Test
    void finalResult_isPresent_whenJobCompleted() {
        UUID jobId = UUID.randomUUID();
        OptimumResult finalResult = new OptimumResult(1, 42.5);
        JobStatusResponse resp = new JobStatusResponse(jobId, "COMPLETED", 3, 3, finalResult, null);

        assertThat(resp.finalResult()).isNotNull();
        assertThat(resp.finalResult().bestRank()).isEqualTo(1);
        assertThat(resp.finalResult().bestScore()).isEqualTo(42.5);
        assertThat(resp.bestSoFar()).isNull();
    }

    @Test
    void finalResult_isNull_whenJobNotCompleted() {
        UUID jobId = UUID.randomUUID();
        JobStatusResponse resp = new JobStatusResponse(jobId, "DECOMPOSED", 3, 1, null, null);

        assertThat(resp.finalResult()).isNull();
    }

    // -------------------------------------------------------------------------
    // AC-TEST-JOB-STATUS-BEST-SO-FAR — E60S04 (RED-first)
    // -------------------------------------------------------------------------

    @Test
    void bestSoFar_isPresent_whenSomePacketsCompleted() {
        UUID jobId = UUID.randomUUID();
        OptimumResult bestSoFar = new OptimumResult(2, 38.0);
        JobStatusResponse resp = new JobStatusResponse(jobId, "DECOMPOSED", 3, 1, null, bestSoFar);

        assertThat(resp.bestSoFar()).isNotNull();
        assertThat(resp.bestSoFar().bestRank()).isEqualTo(2);
        assertThat(resp.bestSoFar().bestScore()).isEqualTo(38.0);
        assertThat(resp.finalResult()).isNull();
    }

    @Test
    void bestSoFar_isNull_whenNoPacketsCompleted() {
        // AC-ERR-BEST-SO-FAR-NO-COMPLETED-PACKETS: HTTP 200 with null bestSoFar
        UUID jobId = UUID.randomUUID();
        JobStatusResponse resp = new JobStatusResponse(jobId, "DECOMPOSED", 3, 0, null, null);

        assertThat(resp.bestSoFar()).isNull();
        assertThat(resp.finalResult()).isNull();
    }
}
