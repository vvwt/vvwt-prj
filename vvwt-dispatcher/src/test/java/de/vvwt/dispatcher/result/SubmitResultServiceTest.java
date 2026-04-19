package de.vvwt.dispatcher.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.vvwt.dispatcher.cache.ResultsCacheService;
import de.vvwt.dispatcher.identity.KeyRegistration;
import de.vvwt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.dispatcher.job.JobRecord;
import de.vvwt.dispatcher.job.JobRepository;
import de.vvwt.dispatcher.packet.PacketRecord;
import de.vvwt.dispatcher.packet.PacketRepository;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubmitResultService} business logic (E01S08 AC3–AC11).
 *
 * <p>No database access — all repositories are mocked.
 */
class SubmitResultServiceTest {

    private PacketRepository packetRepo;
    private JobRepository jobRepo;
    private KeyRegistrationRepository keyRepo;
    private LateResultRepository lateResultRepo;
    private ResultsCacheService cacheService;
    private SubmitResultService service;

    private UUID workerKeyId;
    private UUID packetId;
    private UUID jobId;
    private KeyPair keyPair;
    private KeyRegistration workerKey;

    @BeforeEach
    void setup() throws Exception {
        packetRepo = mock(PacketRepository.class);
        jobRepo = mock(JobRepository.class);
        keyRepo = mock(KeyRegistrationRepository.class);
        lateResultRepo = mock(LateResultRepository.class);
        cacheService = mock(ResultsCacheService.class);
        service =
                new SubmitResultService(packetRepo, jobRepo, keyRepo, lateResultRepo, cacheService);

        workerKeyId = UUID.randomUUID();
        packetId = UUID.randomUUID();
        jobId = UUID.randomUUID();

        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = keyPair.getPublic().getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(spki, spki.length - 32, raw, 0, 32);
        workerKey = new KeyRegistration(workerKeyId, "worker", raw, Instant.now(), null);

        when(keyRepo.findById(workerKeyId)).thenReturn(Optional.of(workerKey));
    }

    // -------------------------------------------------------------------------
    // AC3: First valid result accepted
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC3: first valid result — packet assigned to this worker → ACCEPTED_FIRST")
    void process_firstResult_accepted() throws Exception {
        PacketRecord packet = assignedPacket();
        when(packetRepo.findById(packetId)).thenReturn(Optional.of(packet));
        when(packetRepo.save(any())).thenReturn(packet);

        // Job with one packet — finalization: all done, pending=0, assigned=0
        JobRecord job = stubJobForFinalization(jobId);
        when(packetRepo.countByJobIdAndStatus(jobId, "pending")).thenReturn(0L);
        when(packetRepo.countByJobIdAndStatus(jobId, "assigned")).thenReturn(0L);
        // After acceptFirstResult, the packet is done
        when(packetRepo.findDonePacketsByJobId(jobId)).thenReturn(List.of(packet));

        SubmitResultRequest req = buildRequest(1.0);
        SubmitResultResponse response = service.process(req);

        assertThat(response.accepted()).isTrue();
        assertThat(response.firstResult()).isTrue();
        assertThat(response.latentlyLogged()).isNull();
    }

    @Test
    @DisplayName("AC3: packet transitions to done after first result accepted")
    void process_firstResult_packetStatusSetToDone() throws Exception {
        PacketRecord packet = assignedPacket();
        when(packetRepo.findById(packetId)).thenReturn(Optional.of(packet));
        when(packetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        stubJobForFinalization(jobId);
        when(packetRepo.countByJobIdAndStatus(jobId, "pending")).thenReturn(0L);
        when(packetRepo.countByJobIdAndStatus(jobId, "assigned")).thenReturn(0L);
        when(packetRepo.findDonePacketsByJobId(jobId)).thenReturn(List.of(packet));

        service.process(buildRequest(1.0));

        assertThat(packet.getStatus()).isEqualTo("done");
        assertThat(packet.getFirstBestRank()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC4 / AC5: Late result handling
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC4: late result matching first → logged, response latentlyLogged=true")
    void process_lateResult_matching_logged() throws Exception {
        PacketRecord packet = donePacket(42L, 1.5);
        when(packetRepo.findById(packetId)).thenReturn(Optional.of(packet));

        SubmitResultRequest req = buildRequest(1.5, 42L);
        SubmitResultResponse response = service.process(req);

        assertThat(response.accepted()).isTrue();
        assertThat(response.firstResult()).isFalse();
        assertThat(response.latentlyLogged()).isTrue();
        verify(lateResultRepo).save(any(LateResult.class));
    }

    @Test
    @DisplayName("AC5: late result diverging → logged + WARN (matchesFirst=false)")
    void process_lateResult_diverging_logsWarn() throws Exception {
        PacketRecord packet = donePacket(42L, 1.5); // first result: rank=42, score=1.5
        when(packetRepo.findById(packetId)).thenReturn(Optional.of(packet));

        // Late result: different score
        SubmitResultRequest req = buildRequest(9.9, 99L);
        SubmitResultResponse response = service.process(req);

        assertThat(response.accepted()).isTrue();
        assertThat(response.firstResult()).isFalse();
        assertThat(response.latentlyLogged()).isTrue();

        // Verify the late result row was saved with matchesFirst=false
        verify(lateResultRepo).save(argThat((LateResult lr) -> !lr.isMatchesFirst()));
    }

    // -------------------------------------------------------------------------
    // AC6: Assignment check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC6: packet assigned to different worker → 409 NotAssignedException")
    void process_notAssignedToThisWorker_throws409() throws Exception {
        PacketRecord packet = new PacketRecord(packetId, jobId, 0, 100);
        packet.assign(
                UUID.randomUUID(),
                Instant.now(),
                Instant.now().plusSeconds(300)); // different worker
        when(packetRepo.findById(packetId)).thenReturn(Optional.of(packet));

        SubmitResultRequest req = buildRequest(1.0);

        assertThatThrownBy(() -> service.process(req))
                .isInstanceOf(SubmitResultService.NotAssignedException.class);
    }

    @Test
    @DisplayName(
            "AC6: packet in pending state (never assigned to anyone) → 409 NotAssignedException")
    void process_packetPending_throws409() throws Exception {
        PacketRecord packet = new PacketRecord(packetId, jobId, 0, 100); // status=pending
        when(packetRepo.findById(packetId)).thenReturn(Optional.of(packet));

        SubmitResultRequest req = buildRequest(1.0);

        assertThatThrownBy(() -> service.process(req))
                .isInstanceOf(SubmitResultService.NotAssignedException.class);
    }

    // -------------------------------------------------------------------------
    // AC7: Deadline check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC7: result submitted after deadline → 410 DeadlineExceededException")
    void process_afterDeadline_throws410() throws Exception {
        PacketRecord packet = new PacketRecord(packetId, jobId, 0, 100);
        // Assign with a past deadline
        packet.assign(
                workerKeyId,
                Instant.now().minusSeconds(600),
                Instant.now().minusSeconds(300)); // deadline 5 minutes ago
        when(packetRepo.findById(packetId)).thenReturn(Optional.of(packet));

        SubmitResultRequest req = buildRequest(1.0);

        assertThatThrownBy(() -> service.process(req))
                .isInstanceOf(SubmitResultService.DeadlineExceededException.class)
                .satisfies(
                        ex ->
                                assertThat(
                                                ((SubmitResultService.DeadlineExceededException) ex)
                                                        .getDeadline())
                                        .isNotNull());
    }

    @Test
    @DisplayName("AC7: result submitted before deadline → accepted")
    void process_beforeDeadline_accepted() throws Exception {
        PacketRecord packet = assignedPacket(); // deadline is 5 minutes in the future
        when(packetRepo.findById(packetId)).thenReturn(Optional.of(packet));
        when(packetRepo.save(any())).thenReturn(packet);

        stubJobForFinalization(jobId);
        when(packetRepo.countByJobIdAndStatus(jobId, "pending")).thenReturn(0L);
        when(packetRepo.countByJobIdAndStatus(jobId, "assigned")).thenReturn(0L);
        when(packetRepo.findDonePacketsByJobId(jobId)).thenReturn(List.of(packet));

        SubmitResultResponse response = service.process(buildRequest(1.0));

        assertThat(response.firstResult()).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC11: Idempotency / duplicate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC11: duplicate — same worker, same result → duplicate=true response")
    void process_duplicate_sameWorkerSameResult() throws Exception {
        // First worker already submitted rank=42, score=1.5
        PacketRecord packet = donePacket(42L, 1.5);
        // Make firstWorkerKeyId match the submitting worker
        packet.acceptFirstResult(workerKeyId, 42L, 1.5); // re-accept to set firstWorkerKeyId
        when(packetRepo.findById(packetId)).thenReturn(Optional.of(packet));

        SubmitResultRequest req = buildRequest(1.5, 42L);
        SubmitResultResponse response = service.process(req);

        assertThat(response.duplicate()).isTrue();
        assertThat(response.latentlyLogged()).isTrue();
        // Duplicate should NOT create a late_results row
        verify(lateResultRepo, never()).save(any());
    }

    @Test
    @DisplayName("AC11: unknown packetId → 404 PacketNotFoundException")
    void process_unknownPacketId_throws404() throws Exception {
        when(packetRepo.findById(packetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.process(buildRequest(1.0)))
                .isInstanceOf(SubmitResultService.PacketNotFoundException.class);
    }

    @Test
    @DisplayName("AC11: missing required field bestRank → 400 BadRequestException")
    void process_missingBestRank_throws400() {
        SubmitResultRequest req =
                new SubmitResultRequest(
                        packetId, jobId, null, "1.0", 1000L, 500L, workerKeyId, "sig");

        assertThatThrownBy(() -> service.process(req))
                .isInstanceOf(SubmitResultService.BadRequestException.class)
                .hasMessageContaining("bestRank");
    }

    @Test
    @DisplayName("AC11: non-parseable bestScore → 400 BadRequestException")
    void process_invalidBestScore_throws400() {
        SubmitResultRequest req =
                new SubmitResultRequest(
                        packetId, jobId, 42L, "NOT_A_DOUBLE", 1000L, 500L, workerKeyId, "sig");

        assertThatThrownBy(() -> service.process(req))
                .isInstanceOf(SubmitResultService.BadRequestException.class);
    }

    // -------------------------------------------------------------------------
    // AC9: Job finalization
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC9: two-packet job — last packet done triggers finalization (lowest score wins)")
    void process_lastPacket_triggersFinalization() throws Exception {
        // Two packets: one already done (score=2.0, rank=10), one being submitted (score=1.0,
        // rank=5)
        PacketRecord packet1 = donePacket(10L, 2.0); // already done before this submission
        PacketRecord packet2 = assignedPacket(); // the one being submitted (still assigned)

        when(packetRepo.findById(packetId)).thenReturn(Optional.of(packet2));
        // When save is called, packet2 will have been mutated to done by acceptFirstResult
        when(packetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // No more pending or assigned after submission
        when(packetRepo.countByJobIdAndStatus(jobId, "pending")).thenReturn(0L);
        when(packetRepo.countByJobIdAndStatus(jobId, "assigned")).thenReturn(0L);
        // After acceptFirstResult, packet2 status="done", firstBestRank=5, firstBestScore=1.0
        when(packetRepo.findDonePacketsByJobId(jobId)).thenReturn(List.of(packet1, packet2));

        JobRecord job = stubJobForFinalization(jobId);

        service.process(buildRequest(1.0, 5L));

        // Cache must be written with best result (score=1.0, rank=5)
        verify(cacheService).write(any(), anyInt(), anyInt(), eq(5L), eq(1.0), anyInt(), eq(jobId));
        assertThat(job.getStatus()).isEqualTo("done");
    }

    @Test
    @DisplayName("AC9: tie-break — same score, lower rank wins")
    void process_finalization_tieBrakeByRank() throws Exception {
        // Two already-done packets, both score=1.0 but different ranks
        PacketRecord p1 = donePacket(10L, 1.0);
        PacketRecord p2 = donePacket(5L, 1.0); // lower rank = winner

        // submittedPacket is still assigned — process() will accept it with rank=8, score=1.0
        // but p2 (rank=5) should still win the finalization
        PacketRecord submittedPacket = assignedPacket();
        when(packetRepo.findById(packetId)).thenReturn(Optional.of(submittedPacket));
        when(packetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(packetRepo.countByJobIdAndStatus(jobId, "pending")).thenReturn(0L);
        when(packetRepo.countByJobIdAndStatus(jobId, "assigned")).thenReturn(0L);
        when(packetRepo.findDonePacketsByJobId(jobId)).thenReturn(List.of(p1, p2, submittedPacket));

        stubJobForFinalization(jobId);

        // Submit with rank=8 and score=1.0 — submittedPacket will be done with rank=8
        service.process(buildRequest(1.0, 8L));

        // Should write rank=5 (lowest rank among score=1.0 packets: p2 rank=5 < submittedPacket
        // rank=8 < p1 rank=10)
        verify(cacheService).write(any(), anyInt(), anyInt(), eq(5L), eq(1.0), anyInt(), eq(jobId));
    }

    @Test
    @DisplayName("AC10: deterministic finalization — same inputs always produce same result")
    void process_finalization_deterministic() throws Exception {
        // Build a list of already-done packets with fixed results
        PacketRecord p1 = donePacket(10L, 2.0);
        PacketRecord p2 = donePacket(5L, 1.0); // best: lowest score, lowest rank
        PacketRecord p3 = donePacket(7L, 1.5);

        // submittedPacket will be accepted with rank=20, score=3.0 — not the winner
        PacketRecord submittedPacket = assignedPacket();
        when(packetRepo.findById(packetId)).thenReturn(Optional.of(submittedPacket));
        when(packetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(packetRepo.countByJobIdAndStatus(jobId, "pending")).thenReturn(0L);
        when(packetRepo.countByJobIdAndStatus(jobId, "assigned")).thenReturn(0L);
        when(packetRepo.findDonePacketsByJobId(jobId))
                .thenReturn(List.of(p1, p2, p3, submittedPacket));

        stubJobForFinalization(jobId);

        // Submit with rank=20, score=3.0 — global best remains p2 (rank=5, score=1.0)
        service.process(buildRequest(3.0, 20L));
        // Deterministic: p2 wins with score=1.0, rank=5
        verify(cacheService).write(any(), anyInt(), anyInt(), eq(5L), eq(1.0), anyInt(), eq(jobId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PacketRecord assignedPacket() {
        PacketRecord p = new PacketRecord(packetId, jobId, 0, 100);
        p.assign(workerKeyId, Instant.now(), Instant.now().plusSeconds(300));
        return p;
    }

    private PacketRecord donePacket(long rank, double score) {
        PacketRecord p = new PacketRecord(UUID.randomUUID(), jobId, 0, 100);
        p.acceptFirstResult(UUID.randomUUID(), rank, score);
        return p;
    }

    private JobRecord stubJobForFinalization(UUID jId) {
        JobRecord job =
                new JobRecord(
                        jId,
                        UUID.randomUUID(),
                        1,
                        "{}",
                        "{\"rowCount\":5}",
                        new byte[32],
                        "ready",
                        Instant.now());
        job.setPacketCount(2);
        when(jobRepo.findById(jId)).thenReturn(Optional.of(job));
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing()
                .when(cacheService)
                .write(any(), anyInt(), anyInt(), anyLong(), anyDouble(), anyInt(), any());
        return job;
    }

    private SubmitResultRequest buildRequest(double score) throws Exception {
        return buildRequest(score, 42L);
    }

    private SubmitResultRequest buildRequest(double score, long rank) throws Exception {
        long perms = 1_000_000L;
        byte[] canonical =
                SubmitResultService.buildCanonicalBytes72(
                        packetId, jobId, rank, score, perms, workerKeyId);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonical);
        String sig = Base64.getEncoder().encodeToString(signer.sign());

        return new SubmitResultRequest(
                packetId,
                jobId,
                rank,
                Double.toString(score),
                perms,
                500_000_000L,
                workerKeyId,
                sig);
    }
}
