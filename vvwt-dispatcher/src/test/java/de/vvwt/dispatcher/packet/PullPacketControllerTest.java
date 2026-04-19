package de.vvwt.dispatcher.packet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.dispatcher.audit.AuditService;
import de.vvwt.dispatcher.job.JobRecord;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code @WebMvcTest} slice for {@link PullPacketController} (AC3–AC11).
 *
 * <p>Tests:
 *
 * <ul>
 *   <li>AC5: 200 response shape (packetId, jobId, jobDef, rankFrom, rankTo, deadline)
 *   <li>AC6: 204 No Content when no pending packets
 *   <li>AC4: 401 on stale nonce, 403 on wrong role
 *   <li>AC9: 400 on malformed payload
 *   <li>AC10: audit service called on every outcome
 * </ul>
 *
 * <p>{@link PullPacketService} is mocked; authentication/DB logic tested separately.
 */
@WebMvcTest(PullPacketController.class)
class PullPacketControllerTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private PullPacketService pullPacketService;

    @MockitoBean private AuditService auditService;

    // -------------------------------------------------------------------------
    // AC5: 200 OK with correct response body shape
    // -------------------------------------------------------------------------

    @Test
    void pullPacket_success_returns200WithExpectedShape() throws Exception {
        UUID workerKeyId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant deadline = Instant.now().plusSeconds(300);

        PacketRecord packet = new PacketRecord(packetId, jobId, 0L, 1000L);
        packet.assign(workerKeyId, Instant.now());

        // Build a minimal JobRecord via reflection workaround — use canonical JSON
        String canonicalJson = "{\"rowCount\":3,\"avatarCount\":6,\"rows\":[[0,1],[2,3],[4,5]]}";
        JobRecord job =
                new JobRecord(
                        jobId,
                        UUID.randomUUID(),
                        1,
                        "{}",
                        canonicalJson,
                        new byte[32],
                        "ready",
                        Instant.now());

        when(pullPacketService.execute(any(), anyString(), anyString()))
                .thenReturn(new PullPacketService.PullResult.Assigned(packet, job, deadline));

        PullPacketRequest req =
                new PullPacketRequest(workerKeyId, "sigBase64", Instant.now().toString());
        mockMvc.perform(
                        post("/pull-packet")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packetId").value(packetId.toString()))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.rankFrom").value(0))
                .andExpect(jsonPath("$.rankTo").value(1000))
                .andExpect(jsonPath("$.deadline").isNotEmpty())
                .andExpect(jsonPath("$.jobDef.rowCount").value(3));

        verify(auditService).log(any(), anyString(), any(), anyString(), any(int.class));
    }

    // -------------------------------------------------------------------------
    // AC6: 204 No Content on empty queue
    // -------------------------------------------------------------------------

    @Test
    void pullPacket_noWork_returns204() throws Exception {
        UUID workerKeyId = UUID.randomUUID();
        when(pullPacketService.execute(any(), anyString(), anyString()))
                .thenReturn(new PullPacketService.PullResult.NoWork());

        PullPacketRequest req = new PullPacketRequest(workerKeyId, "sig", Instant.now().toString());
        mockMvc.perform(
                        post("/pull-packet")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(auditService).log(any(), anyString(), any(), anyString(), any(int.class));
    }

    // -------------------------------------------------------------------------
    // AC4: 401 on stale nonce
    // -------------------------------------------------------------------------

    @Test
    void pullPacket_staleNonce_returns401WithErrorCode() throws Exception {
        UUID workerKeyId = UUID.randomUUID();
        when(pullPacketService.execute(any(), anyString(), anyString()))
                .thenThrow(
                        new PullPacketService.UnauthorizedException(
                                "stale-or-future-timestamp", "nonce is stale"));

        PullPacketRequest req = new PullPacketRequest(workerKeyId, "sig", "2020-01-01T00:00:00Z");
        mockMvc.perform(
                        post("/pull-packet")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("stale-or-future-timestamp"));

        verify(auditService).log(any(), anyString(), any(), anyString(), any(int.class));
    }

    // -------------------------------------------------------------------------
    // AC4: 403 on wrong role
    // -------------------------------------------------------------------------

    @Test
    void pullPacket_submitterRole_returns403() throws Exception {
        UUID workerKeyId = UUID.randomUUID();
        when(pullPacketService.execute(any(), anyString(), anyString()))
                .thenThrow(new PullPacketService.ForbiddenException("wrong role"));

        PullPacketRequest req = new PullPacketRequest(workerKeyId, "sig", Instant.now().toString());
        mockMvc.perform(
                        post("/pull-packet")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        verify(auditService).log(any(), anyString(), any(), anyString(), any(int.class));
    }

    // -------------------------------------------------------------------------
    // AC9: 400 on malformed payload
    // -------------------------------------------------------------------------

    @Test
    void pullPacket_malformedPayload_returns400() throws Exception {
        UUID workerKeyId = UUID.randomUUID();
        when(pullPacketService.execute(any(), anyString(), anyString()))
                .thenThrow(
                        new IllegalArgumentException(
                                "signedNonce is not a valid ISO-8601 instant"));

        PullPacketRequest req = new PullPacketRequest(workerKeyId, "sig", "not-a-timestamp");
        mockMvc.perform(
                        post("/pull-packet")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        verify(auditService).log(any(), anyString(), any(), anyString(), any(int.class));
    }
}
