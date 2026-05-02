package de.vvwt.slotopt.dispatcher.result;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.slotopt.dispatcher.crypto.InvalidSignatureException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring MVC test slice for {@link SubmitResultController}.
 *
 * <p>DEC-36: mocks {@link SubmitResultService} (public interface), never {@code
 * DefaultSubmitResultService}.
 *
 * <p>RED-first per DEC-22 / AC-MOCKMVC-CONTROLLER-TESTS (E37S09): written before controller exists.
 *
 * <p>Story: E37S09; AC-SUBMIT-RESULT-CONTROLLER; AC-MOCKMVC-CONTROLLER-TESTS; DEC-36
 */
@WebMvcTest(
        value = SubmitResultController.class,
        excludeAutoConfiguration = {
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class
        })
class SubmitResultControllerSliceTest {

    @Autowired private MockMvc mockMvc;

    /** DEC-36: mock the public interface, not DefaultSubmitResultService. */
    @MockitoBean private SubmitResultService submitResultService;

    private static final String VALID_REQUEST_JSON =
            """
            {
              "packetId": "%s",
              "workerId": "%s",
              "algorithm": "Ed25519",
              "signature": "AAAA",
              "resultPayloadJson": "{\\"bestRank\\":42}"
            }
            """;

    @Test
    void submitResult_accepted_returns200WithAcceptedTrue() throws Exception {
        UUID packetId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        when(submitResultService.submit(any(), anyString()))
                .thenReturn(new SubmitResultResponse(true, null));

        mockMvc.perform(
                        post("/api/submit-result")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_REQUEST_JSON.formatted(packetId, workerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void submitResult_algorithmMismatch_returns400() throws Exception {
        UUID packetId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        when(submitResultService.submit(any(), anyString()))
                .thenThrow(
                        new AlgorithmMismatchException(
                                "registered algorithm Ed25519 does not match submitted ML-DSA-65"));

        mockMvc.perform(
                        post("/api/submit-result")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_REQUEST_JSON.formatted(packetId, workerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void submitResult_unknownWorker_returns401() throws Exception {
        UUID packetId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        when(submitResultService.submit(any(), anyString()))
                .thenThrow(new UnknownWorkerException("worker not registered: " + workerId));

        mockMvc.perform(
                        post("/api/submit-result")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_REQUEST_JSON.formatted(packetId, workerId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submitResult_invalidSignature_returns401() throws Exception {
        UUID packetId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        when(submitResultService.submit(any(), anyString()))
                .thenThrow(new InvalidSignatureException("signature verification failed"));

        mockMvc.perform(
                        post("/api/submit-result")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_REQUEST_JSON.formatted(packetId, workerId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submitResult_malformedJson_returns400() throws Exception {
        mockMvc.perform(
                        post("/api/submit-result")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ not valid }"))
                .andExpect(status().isBadRequest());
    }
}
