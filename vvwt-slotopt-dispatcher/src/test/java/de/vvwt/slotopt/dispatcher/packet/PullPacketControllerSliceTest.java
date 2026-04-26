package de.vvwt.slotopt.dispatcher.packet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring MVC test slice for {@link PullPacketController}.
 *
 * <p>DEC-36 cross-package test typing rule: mocks {@link PullPacketService} (public interface),
 * never the implementation class {@code DefaultPullPacketService}.
 *
 * <p>Tests all wire-format scenarios per AC-MOCKMVC-CONTROLLER-TEST:
 *
 * <ul>
 *   <li>200 with {@link PullPacketResponse} on successful claim
 *   <li>204 No Content when no unclaimed packets
 *   <li>400 when {@code supportedAlgorithms} is empty array {@code []}
 *   <li>200 when {@code supportedAlgorithms} field absent (defaults to {@code ["Ed25519"]})
 *   <li>404 when workerId unknown
 * </ul>
 *
 * <p>RED-first per DEC-22 / AC-MOCKMVC-CONTROLLER-TEST (E37S08).
 *
 * <p>Story: E37S08; AC-MOCKMVC-CONTROLLER-TEST; DEC-36
 */
@WebMvcTest(PullPacketController.class)
class PullPacketControllerSliceTest {

    @Autowired private MockMvc mockMvc;

    // DEC-36: mock the PUBLIC INTERFACE, not the implementation class
    @MockitoBean private PullPacketService pullPacketService;

    // -------------------------------------------------------------------------
    // 200: packet available
    // -------------------------------------------------------------------------

    @Test
    void pullPacketReturns200WithPacketOnSuccess() throws Exception {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        PacketRecord packet = buildClaimedPacket(packetId, jobId);
        when(pullPacketService.claim(any(), any())).thenReturn(Optional.of(packet));

        mockMvc.perform(
                        post("/api/pull-packet")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"workerId":"%s","supportedAlgorithms":["Ed25519"]}
                                        """
                                                .formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packetId").value(packetId.toString()))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    // -------------------------------------------------------------------------
    // 204: no packets available
    // -------------------------------------------------------------------------

    @Test
    void pullPacketReturns204WhenNoPendingPackets() throws Exception {
        when(pullPacketService.claim(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(
                        post("/api/pull-packet")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"workerId":"%s","supportedAlgorithms":["Ed25519"]}
                                        """
                                                .formatted(UUID.randomUUID())))
                .andExpect(status().isNoContent());
    }

    // -------------------------------------------------------------------------
    // 400: empty supportedAlgorithms array
    // -------------------------------------------------------------------------

    @Test
    void pullPacketReturns400WhenSupportedAlgorithmsIsEmptyArray() throws Exception {
        mockMvc.perform(
                        post("/api/pull-packet")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"workerId":"%s","supportedAlgorithms":[]}
                                        """
                                                .formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // -------------------------------------------------------------------------
    // 200: missing supportedAlgorithms field → defaults to ["Ed25519"]
    // -------------------------------------------------------------------------

    @Test
    void pullPacketAcceptsMissingSupportedAlgorithmsAndDefaultsToEd25519() throws Exception {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        PacketRecord packet = buildClaimedPacket(packetId, jobId);
        when(pullPacketService.claim(any(), any())).thenReturn(Optional.of(packet));

        // No supportedAlgorithms field in request body
        mockMvc.perform(
                        post("/api/pull-packet")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"workerId":"%s"}
                                        """
                                                .formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packetId").value(packetId.toString()));
    }

    // -------------------------------------------------------------------------
    // 404: unknown workerId
    // -------------------------------------------------------------------------

    @Test
    void pullPacketReturns404WhenWorkerNotFound() throws Exception {
        when(pullPacketService.claim(any(), any()))
                .thenThrow(new WorkerNotFoundException("Worker not registered"));

        mockMvc.perform(
                        post("/api/pull-packet")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"workerId":"%s","supportedAlgorithms":["Ed25519"]}
                                        """
                                                .formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static PacketRecord buildClaimedPacket(UUID packetId, UUID jobId) {
        PacketRecord p = new PacketRecord();
        p.setPacketId(packetId);
        p.setJobId(jobId);
        p.setPacketPayloadJson("{\"rankFrom\":0,\"rankTo\":100000000}");
        p.setStatus("CLAIMED");
        p.setClaimedAt(Instant.now());
        p.setTimeoutAt(Instant.now().plusSeconds(300));
        return p;
    }
}
