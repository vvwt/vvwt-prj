// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.dispatcher.cache.ResultsCacheService;
import de.vvwt.slotopt.dispatcher.job.JobRecord;
import de.vvwt.slotopt.dispatcher.job.JobRepository;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import de.vvwt.slotopt.dispatcher.result.PacketResult;
import de.vvwt.slotopt.dispatcher.result.PacketResultService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultJobFinalizationService}.
 *
 * <p>Same-package test (DEC-36 § same-package white-box allowed). RED-first per DEC-22.
 *
 * <p>Story: E60S03; AC-TEST-LAST-PACKET-FINALIZES, AC-TEST-NOT-FINALIZED-BEFORE-LAST,
 * AC-TEST-AGGREGATION-GLOBAL-OPTIMUM, AC-TEST-AGGREGATION-DETERMINISTIC,
 * AC-TEST-CACHE-HOLDS-AGGREGATED-OPTIMUM, AC-ERR-FINALIZATION-IDEMPOTENT,
 * AC-ERR-CACHE-WRITE-FAILURE-ABSORBED, AC-ERR-LAST-PACKET-DEFINITION; DEC-22, DEC-36
 */
class DefaultJobFinalizationServiceTest {

    private PacketRepository packetRepository;
    private PacketResultService packetResultService;
    private JobRepository jobRepository;
    private ResultsCacheService resultsCacheService;
    private DefaultJobFinalizationService service;

    @BeforeEach
    void setUp() {
        packetRepository = mock(PacketRepository.class);
        packetResultService = mock(PacketResultService.class);
        jobRepository = mock(JobRepository.class);
        resultsCacheService = mock(ResultsCacheService.class);
        service =
                new DefaultJobFinalizationService(
                        packetRepository, packetResultService, jobRepository, resultsCacheService);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-LAST-PACKET-FINALIZES
    // When the last packet reaches RESULT_RECEIVED, job transitions DECOMPOSED → COMPLETED
    // -------------------------------------------------------------------------

    @Test
    void tryFinalizeJob_allPacketsResultReceived_jobCompletedAndCacheWritten() {
        UUID jobId = UUID.randomUUID();

        // Two packets, both RESULT_RECEIVED
        PacketRecord p1 = packet(UUID.randomUUID(), jobId, "RESULT_RECEIVED");
        PacketRecord p2 = packet(UUID.randomUUID(), jobId, "RESULT_RECEIVED");
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of(p1, p2));

        // Retained results: p1 bestScore=2.0 bestRank=10, p2 bestScore=1.0 bestRank=5
        PacketResult r1 = packetResult(p1.getPacketId(), jobId, 10, 2.0);
        PacketResult r2 = packetResult(p2.getPacketId(), jobId, 5, 1.0);
        when(packetResultService.findResultsByJobId(jobId)).thenReturn(List.of(r1, r2));

        // Job record with DECOMPOSED status
        JobRecord jobRecord = jobRecord(jobId, "DECOMPOSED");
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(jobRecord));

        service.tryFinalizeJob(jobId);

        // Job must be saved as COMPLETED
        verify(jobRepository).save(jobRecord);
        assertThat(jobRecord.getStatus()).isEqualTo("COMPLETED");

        // Cache must be written with the global optimum (p2: bestScore=1.0 bestRank=5)
        verify(resultsCacheService)
                .recordAcceptedResult(any(byte[].class), eq("default"), anyString(), eq(jobId));
    }

    // -------------------------------------------------------------------------
    // AC-TEST-NOT-FINALIZED-BEFORE-LAST
    // A job with any non-terminal packet is NOT finalized
    // -------------------------------------------------------------------------

    @Test
    void tryFinalizeJob_somePacketsNotResultReceived_noFinalization() {
        UUID jobId = UUID.randomUUID();

        // Two packets: one RESULT_RECEIVED, one UNCLAIMED
        PacketRecord p1 = packet(UUID.randomUUID(), jobId, "RESULT_RECEIVED");
        PacketRecord p2 = packet(UUID.randomUUID(), jobId, "UNCLAIMED");
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of(p1, p2));

        service.tryFinalizeJob(jobId);

        // Job must NOT be updated
        verify(jobRepository, never()).findByJobId(any());
        verify(jobRepository, never()).save(any());
        verify(resultsCacheService, never()).recordAcceptedResult(any(), any(), any(), any());
    }

    @Test
    void tryFinalizeJob_onePacketClaimed_noFinalization() {
        UUID jobId = UUID.randomUUID();

        PacketRecord p1 = packet(UUID.randomUUID(), jobId, "CLAIMED");
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of(p1));

        service.tryFinalizeJob(jobId);

        verify(jobRepository, never()).findByJobId(any());
        verify(jobRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // AC-TEST-AGGREGATION-GLOBAL-OPTIMUM
    // Global optimum = lowest bestScore; tie → lowest bestRank
    // -------------------------------------------------------------------------

    @Test
    void tryFinalizeJob_globalOptimumIsLowestBestScore_notFirstSubmitted() {
        UUID jobId = UUID.randomUUID();

        // 3 packets all RESULT_RECEIVED
        PacketRecord p1 = packet(UUID.randomUUID(), jobId, "RESULT_RECEIVED");
        PacketRecord p2 = packet(UUID.randomUUID(), jobId, "RESULT_RECEIVED");
        PacketRecord p3 = packet(UUID.randomUUID(), jobId, "RESULT_RECEIVED");
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of(p1, p2, p3));

        // p1 bestScore=5.0, p2 bestScore=3.0, p3 bestScore=1.0 (winner — not first submitted)
        PacketResult r1 = packetResult(p1.getPacketId(), jobId, 10, 5.0);
        PacketResult r2 = packetResult(p2.getPacketId(), jobId, 7, 3.0);
        PacketResult r3 = packetResult(p3.getPacketId(), jobId, 3, 1.0);
        // Return in original order (p1, p2, p3) — winner is p3 (last)
        when(packetResultService.findResultsByJobId(jobId)).thenReturn(List.of(r1, r2, r3));

        JobRecord jobRecord = jobRecord(jobId, "DECOMPOSED");
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(jobRecord));

        // Capture the JSON written to cache
        final String[] capturedPayload = new String[1];
        org.mockito.ArgumentCaptor<String> payloadCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);

        service.tryFinalizeJob(jobId);

        verify(resultsCacheService)
                .recordAcceptedResult(any(), anyString(), payloadCaptor.capture(), any());
        String payload = payloadCaptor.getValue();
        // The cache entry must reflect p3's bestScore=1.0 bestRank=3
        assertThat(payload).contains("\"bestScore\"").contains("1.0");
        assertThat(payload).contains("\"bestRank\"").contains("3");
    }

    // -------------------------------------------------------------------------
    // AC-TEST-AGGREGATION-DETERMINISTIC
    // Same results in different order → same winner
    // -------------------------------------------------------------------------

    @Test
    void tryFinalizeJob_deterministic_sameResultsDifferentOrder_sameWinner() {
        UUID jobId1 = UUID.randomUUID();
        UUID jobId2 = UUID.randomUUID();

        UUID pA = UUID.randomUUID();
        UUID pB = UUID.randomUUID();

        // Job 1: results in order A(bestScore=2.0), B(bestScore=1.0) → winner B
        {
            PacketRecord p1 = packet(pA, jobId1, "RESULT_RECEIVED");
            PacketRecord p2 = packet(pB, jobId1, "RESULT_RECEIVED");
            when(packetRepository.findByJobId(jobId1)).thenReturn(List.of(p1, p2));
            PacketResult r1 = packetResult(pA, jobId1, 10, 2.0);
            PacketResult r2 = packetResult(pB, jobId1, 5, 1.0);
            when(packetResultService.findResultsByJobId(jobId1)).thenReturn(List.of(r1, r2));
            JobRecord jr1 = jobRecord(jobId1, "DECOMPOSED");
            when(jobRepository.findByJobId(jobId1)).thenReturn(Optional.of(jr1));

            service.tryFinalizeJob(jobId1);
        }

        // Job 2: results in reversed order B(bestScore=1.0), A(bestScore=2.0) → winner still B
        {
            PacketRecord p1 = packet(pA, jobId2, "RESULT_RECEIVED");
            PacketRecord p2 = packet(pB, jobId2, "RESULT_RECEIVED");
            when(packetRepository.findByJobId(jobId2)).thenReturn(List.of(p1, p2));
            PacketResult r1 = packetResult(pA, jobId2, 10, 2.0);
            PacketResult r2 = packetResult(pB, jobId2, 5, 1.0);
            // Reversed order
            when(packetResultService.findResultsByJobId(jobId2)).thenReturn(List.of(r2, r1));
            JobRecord jr2 = jobRecord(jobId2, "DECOMPOSED");
            when(jobRepository.findByJobId(jobId2)).thenReturn(Optional.of(jr2));

            service.tryFinalizeJob(jobId2);
        }

        // Both invocations must write the same bestScore=1.0 bestRank=5
        org.mockito.ArgumentCaptor<String> captor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(resultsCacheService, org.mockito.Mockito.times(2))
                .recordAcceptedResult(any(), anyString(), captor.capture(), any());
        List<String> payloads = captor.getAllValues();
        assertThat(payloads.get(0)).isEqualTo(payloads.get(1));
    }

    // -------------------------------------------------------------------------
    // AC-ERR-FINALIZATION-IDEMPOTENT
    // Already-COMPLETED job → no re-finalization, no re-cache
    // -------------------------------------------------------------------------

    @Test
    void tryFinalizeJob_jobAlreadyCompleted_noOp() {
        UUID jobId = UUID.randomUUID();

        // All packets RESULT_RECEIVED
        PacketRecord p1 = packet(UUID.randomUUID(), jobId, "RESULT_RECEIVED");
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of(p1));

        PacketResult r1 = packetResult(p1.getPacketId(), jobId, 1, 1.0);
        when(packetResultService.findResultsByJobId(jobId)).thenReturn(List.of(r1));

        // Job already COMPLETED
        JobRecord jobRecord = jobRecord(jobId, "COMPLETED");
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(jobRecord));

        service.tryFinalizeJob(jobId);

        // Job must NOT be saved again
        verify(jobRepository, never()).save(any());
        // Cache must NOT be written again
        verify(resultsCacheService, never()).recordAcceptedResult(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // AC-ERR-CACHE-WRITE-FAILURE-ABSORBED
    // Cache write failure → job still COMPLETED, no rethrow
    // -------------------------------------------------------------------------

    @Test
    void tryFinalizeJob_cacheWriteFails_jobStillCompletedAndNoException() {
        UUID jobId = UUID.randomUUID();

        PacketRecord p1 = packet(UUID.randomUUID(), jobId, "RESULT_RECEIVED");
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of(p1));

        PacketResult r1 = packetResult(p1.getPacketId(), jobId, 1, 1.0);
        when(packetResultService.findResultsByJobId(jobId)).thenReturn(List.of(r1));

        JobRecord jobRecord = jobRecord(jobId, "DECOMPOSED");
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(jobRecord));

        // Cache write throws
        doThrow(new RuntimeException("DB failure"))
                .when(resultsCacheService)
                .recordAcceptedResult(any(), any(), any(), any());

        // Must not throw
        assertThatCode(() -> service.tryFinalizeJob(jobId)).doesNotThrowAnyException();

        // Job must still be saved as COMPLETED
        verify(jobRepository).save(jobRecord);
        assertThat(jobRecord.getStatus()).isEqualTo("COMPLETED");
    }

    // -------------------------------------------------------------------------
    // AC-ERR-LAST-PACKET-DEFINITION
    // TIMEDOUT written state is non-terminal per spec note, but since the sweeper
    // resets to UNCLAIMED, we must also treat UNCLAIMED as non-terminal.
    // -------------------------------------------------------------------------

    @Test
    void tryFinalizeJob_packetUnclaimed_notFinalTerminal() {
        UUID jobId = UUID.randomUUID();

        // One packet RESULT_RECEIVED, one UNCLAIMED (timed-out and reset)
        PacketRecord p1 = packet(UUID.randomUUID(), jobId, "RESULT_RECEIVED");
        PacketRecord p2 = packet(UUID.randomUUID(), jobId, "UNCLAIMED");
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of(p1, p2));

        service.tryFinalizeJob(jobId);

        verify(jobRepository, never()).save(any());
        verify(resultsCacheService, never()).recordAcceptedResult(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // AC-TEST-AGGREGATION-GLOBAL-OPTIMUM — tie on bestScore, lower bestRank wins
    // -------------------------------------------------------------------------

    @Test
    void tryFinalizeJob_tieOnBestScore_lowestBestRankWins() {
        UUID jobId = UUID.randomUUID();

        PacketRecord p1 = packet(UUID.randomUUID(), jobId, "RESULT_RECEIVED");
        PacketRecord p2 = packet(UUID.randomUUID(), jobId, "RESULT_RECEIVED");
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of(p1, p2));

        // Same bestScore, different bestRank — p2 has lower bestRank → winner
        PacketResult r1 = packetResult(p1.getPacketId(), jobId, 10, 1.0);
        PacketResult r2 = packetResult(p2.getPacketId(), jobId, 3, 1.0);
        when(packetResultService.findResultsByJobId(jobId)).thenReturn(List.of(r1, r2));

        JobRecord jobRecord = jobRecord(jobId, "DECOMPOSED");
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(jobRecord));

        org.mockito.ArgumentCaptor<String> captor =
                org.mockito.ArgumentCaptor.forClass(String.class);

        service.tryFinalizeJob(jobId);

        verify(resultsCacheService)
                .recordAcceptedResult(any(), anyString(), captor.capture(), any());
        String payload = captor.getValue();
        // Winner must be p2 with bestRank=3
        assertThat(payload).contains("\"bestRank\"").contains("3");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static PacketRecord packet(UUID packetId, UUID jobId, String status) {
        PacketRecord p = new PacketRecord();
        p.setPacketId(packetId);
        p.setJobId(jobId);
        p.setStatus(status);
        return p;
    }

    private static PacketResult packetResult(
            UUID packetId, UUID jobId, int bestRank, double bestScore) {
        PacketResult r = new PacketResult();
        r.setPacketId(packetId);
        r.setJobId(jobId);
        r.setBestRank(bestRank);
        r.setBestScore(bestScore);
        return r;
    }

    private static JobRecord jobRecord(UUID jobId, String status) {
        JobRecord jr = new JobRecord();
        jr.setJobId(jobId);
        jr.setStatus(status);
        // Minimal valid jobDefJson for fingerprint computation
        jr.setJobDefJson(
                "{\"jobId\":\""
                        + jobId
                        + "\",\"n\":2,"
                        + "\"canonicalPhaseDef\":{\"rowCount\":1,\"avatarCount\":2,"
                        + "\"rows\":[[0,1]]}}");
        return jr;
    }
}
