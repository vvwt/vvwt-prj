package de.vvwt.dispatcher.result;

import de.vvwt.dispatcher.identity.KeyRegistration;
import de.vvwt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.dispatcher.job.JobRecord;
import de.vvwt.dispatcher.job.JobRepository;
import de.vvwt.dispatcher.packet.PacketRecord;
import de.vvwt.dispatcher.packet.PacketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@code POST /submit-result} and {@code GET /jobs/{jobId}}
 * (E01S08 AC1–AC12).
 *
 * <p>Uses H2 in-memory database (PostgreSQL compatibility mode) and full Spring context.
 * Tests the full HTTP request/response cycle including persistence.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubmitResultIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired KeyRegistrationRepository keyRepo;
    @Autowired JobRepository jobRepo;
    @Autowired PacketRepository packetRepo;
    @Autowired ResultAuditRepository resultAuditRepo;
    @Autowired LateResultRepository lateResultRepo;

    private UUID workerKeyId;
    private KeyPair keyPair;
    private UUID packetId;
    private UUID jobId;

    @BeforeEach
    void setup() throws Exception {
        // Clean up before each test
        lateResultRepo.deleteAll();
        resultAuditRepo.deleteAll();
        packetRepo.deleteAll();
        jobRepo.deleteAll();
        keyRepo.deleteAll();

        // Generate Ed25519 keypair
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = keyPair.getPublic().getEncoded();
        byte[] raw  = new byte[32];
        System.arraycopy(spki, spki.length - 32, raw, 0, 32);

        workerKeyId = UUID.randomUUID();
        KeyRegistration key = new KeyRegistration(workerKeyId, "worker", raw, Instant.now(), null);
        keyRepo.save(key);

        // Create a job
        jobId = UUID.randomUUID();
        JobRecord job = new JobRecord(jobId, UUID.randomUUID(), 3,
                "{}", "{\"rowCount\":3}", new byte[32], "ready", Instant.now());
        job.setPacketCount(1);
        jobRepo.save(job);

        // Create a packet assigned to the worker
        packetId = UUID.randomUUID();
        PacketRecord packet = new PacketRecord(packetId, jobId, 0, 6);
        packet.assign(workerKeyId, Instant.now(), Instant.now().plusSeconds(300));
        packetRepo.save(packet);
    }

    // -------------------------------------------------------------------------
    // AC3: First valid result accepted
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC3: first valid signed result → 200 {accepted:true, firstResult:true}")
    void submitResult_firstResult_200() throws Exception {
        String body = buildRequestJson(42L, 1.5);

        mockMvc.perform(post("/submit-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted", is(true)))
                .andExpect(jsonPath("$.firstResult", is(true)));

        // Verify packet is done
        PacketRecord updated = packetRepo.findById(packetId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("done");
        assertThat(updated.getFirstBestRank()).isEqualTo(42L);

        // Verify audit row was created
        assertThat(resultAuditRepo.count()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // AC4: Late result
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC4: late result for already-done packet → 200 {firstResult:false, latentlyLogged:true}")
    void submitResult_lateResult_200_logged() throws Exception {
        // Accept first result directly
        PacketRecord packet = packetRepo.findById(packetId).orElseThrow();
        packet.acceptFirstResult(workerKeyId, 42L, 1.5);
        packetRepo.save(packet);

        // Submit again as different worker
        UUID otherWorkerId = UUID.randomUUID();
        KeyPair otherKp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] otherSpki = otherKp.getPublic().getEncoded();
        byte[] otherRaw  = new byte[32];
        System.arraycopy(otherSpki, otherSpki.length - 32, otherRaw, 0, 32);
        keyRepo.save(new KeyRegistration(otherWorkerId, "worker", otherRaw, Instant.now(), null));

        String body = buildRequestJson(42L, 1.5, otherWorkerId, otherKp);

        mockMvc.perform(post("/submit-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted", is(true)))
                .andExpect(jsonPath("$.firstResult", is(false)))
                .andExpect(jsonPath("$.latentlyLogged", is(true)));

        assertThat(lateResultRepo.count()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // AC6: Assignment check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC6: not assigned to this worker → 409")
    void submitResult_notAssigned_409() throws Exception {
        // Create an unassigned packet
        UUID otherPacketId = UUID.randomUUID();
        PacketRecord pending = new PacketRecord(otherPacketId, jobId, 6, 12);
        packetRepo.save(pending);

        String body = buildRequestJsonForPacket(otherPacketId, 42L, 1.5, workerKeyId, keyPair);

        mockMvc.perform(post("/submit-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("not-assigned-to-this-worker")));
    }

    // -------------------------------------------------------------------------
    // AC7: Deadline check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC7: result after deadline → 410")
    void submitResult_afterDeadline_410() throws Exception {
        // Reassign packet with a past deadline
        PacketRecord packet = packetRepo.findById(packetId).orElseThrow();
        // Manually set a past deadline by creating a new packet
        packetRepo.delete(packet);
        PacketRecord expiredPacket = new PacketRecord(packetId, jobId, 0, 6);
        expiredPacket.assign(workerKeyId, Instant.now().minusSeconds(600),
                Instant.now().minusSeconds(300));
        packetRepo.save(expiredPacket);

        String body = buildRequestJson(42L, 1.5);

        mockMvc.perform(post("/submit-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error", is("deadline-exceeded")))
                .andExpect(jsonPath("$.deadline", notNullValue()));
    }

    // -------------------------------------------------------------------------
    // AC8: Audit logging
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC8: every call (including rejected) creates an audit log entry")
    void submitResult_rejected_auditLogged() throws Exception {
        // Unknown packetId → 404, must still log
        String body = buildRequestJsonForPacket(UUID.randomUUID(), 42L, 1.5, workerKeyId, keyPair);

        mockMvc.perform(post("/submit-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        assertThat(resultAuditRepo.count()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // AC11: Error handling
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC11: malformed JSON → 400")
    void submitResult_malformedJson_400() throws Exception {
        mockMvc.perform(post("/submit-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC11: unknown packetId → 404")
    void submitResult_unknownPacketId_404() throws Exception {
        String body = buildRequestJsonForPacket(UUID.randomUUID(), 42L, 1.5, workerKeyId, keyPair);

        mockMvc.perform(post("/submit-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC11: duplicate submission (same worker, same result) → 200 {duplicate:true}")
    void submitResult_duplicate_200() throws Exception {
        // First accept
        PacketRecord packet = packetRepo.findById(packetId).orElseThrow();
        packet.acceptFirstResult(workerKeyId, 42L, 1.5);
        packetRepo.save(packet);

        // Same worker submits same result again → duplicate
        String body = buildRequestJson(42L, 1.5);

        mockMvc.perform(post("/submit-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate", is(true)))
                .andExpect(jsonPath("$.latentlyLogged", is(true)));

        // No late_results row for duplicates
        assertThat(lateResultRepo.count()).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // AC12: Job status endpoint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC12: GET /jobs/{jobId} returns correct packet counts")
    void getJobStatus_returnsCounts() throws Exception {
        mockMvc.perform(get("/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId", is(jobId.toString())))
                .andExpect(jsonPath("$.packetsAssigned", is(1)))
                .andExpect(jsonPath("$.packetsPending", is(0)))
                .andExpect(jsonPath("$.packetsCompleted", is(0)));
    }

    @Test
    @DisplayName("AC12: GET /jobs/{jobId} after finalization — finalResult non-null")
    void getJobStatus_afterFinalization_finalResultPresent() throws Exception {
        // Accept first result — triggers finalization (1 packet job)
        String body = buildRequestJson(42L, 1.5);

        mockMvc.perform(post("/submit-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("done")))
                .andExpect(jsonPath("$.finalResult.rank", is(42)))
                .andExpect(jsonPath("$.finalResult.score").exists());
    }

    @Test
    @DisplayName("AC12: GET /jobs/{unknown} → 404")
    void getJobStatus_unknownJob_404() throws Exception {
        mockMvc.perform(get("/jobs/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String buildRequestJson(long rank, double score) throws Exception {
        return buildRequestJson(rank, score, workerKeyId, keyPair);
    }

    private String buildRequestJson(long rank, double score,
                                     UUID keyId, KeyPair kp) throws Exception {
        return buildRequestJsonForPacket(packetId, rank, score, keyId, kp);
    }

    private String buildRequestJsonForPacket(UUID pId, long rank, double score,
                                              UUID keyId, KeyPair kp) throws Exception {
        long perms = 720L;
        byte[] canonical = SubmitResultService.buildCanonicalBytes72(
                pId, jobId, rank, score, perms, keyId);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(kp.getPrivate());
        signer.update(canonical);
        String sig = Base64.getEncoder().encodeToString(signer.sign());

        return String.format("""
                {
                  "packetId": "%s",
                  "jobId": "%s",
                  "bestRank": %d,
                  "bestScore": "%s",
                  "permutationsScored": %d,
                  "wallClockNanos": 500000000,
                  "workerKeyId": "%s",
                  "signature": "%s"
                }""",
                pId, jobId, rank, Double.toString(score), perms, keyId, sig);
    }
}
