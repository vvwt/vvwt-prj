package de.vvwt.slotopt.dispatcher.crypto;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring MVC test slice for {@link AlgorithmAnnouncementController}.
 *
 * <p>RED-first per DEC-22 Iron Law / AC-TDD-RED-FIRST-EVIDENCE / AC-ANNOUNCEMENT-CONTROLLER.
 * Written before {@link AlgorithmAnnouncementController} exists — compilation fails until Step 4b.
 *
 * <p>DEC-36 cross-package test typing rule: this test is in the {@code crypto} package (different
 * from {@code crypto.internal}), so it mocks {@link AlgorithmAnnouncementService} (the public
 * interface), NOT {@code DefaultAlgorithmAnnouncementService}.
 *
 * <p>Tests:
 *
 * <ul>
 *   <li>GET /api/algorithms → 200 with list (AC-ANNOUNCEMENT-CONTROLLER)
 *   <li>Empty list → 200 with JSON [] body (AC-EMPTY-VERIFIER-LIST controller side)
 *   <li>Service throws RuntimeException → 500 without internal detail leakage
 *       (AC-SERVICE-EXCEPTION-MAPPING)
 * </ul>
 *
 * <p>Story: E40S02
 */
@WebMvcTest(AlgorithmAnnouncementController.class)
class AlgorithmAnnouncementControllerSliceTest {

    @Autowired private MockMvc mockMvc;

    // DEC-36: mock the PUBLIC INTERFACE, not the implementation class
    @MockitoBean private AlgorithmAnnouncementService algorithmAnnouncementService;

    // -------------------------------------------------------------------------
    // 200 with list — AC-ANNOUNCEMENT-CONTROLLER
    // -------------------------------------------------------------------------

    @Test
    void getAlgorithmsReturns200WithAlgorithmList() throws Exception {
        AnnouncedAlgorithm ed25519 = new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null);
        when(algorithmAnnouncementService.announcedAlgorithms()).thenReturn(List.of(ed25519));

        mockMvc.perform(get("/api/algorithms").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].algorithm_id").value("Ed25519"))
                .andExpect(jsonPath("$[0].display_name").value("Ed25519"))
                .andExpect(jsonPath("$[0].deprecation_date").isEmpty())
                .andExpect(jsonPath("$[0].parameters").isEmpty());
    }

    // -------------------------------------------------------------------------
    // 200 with empty list — AC-EMPTY-VERIFIER-LIST (controller side)
    // -------------------------------------------------------------------------

    @Test
    void getAlgorithmsReturns200WithEmptyListWhenNoVerifiers() throws Exception {
        when(algorithmAnnouncementService.announcedAlgorithms()).thenReturn(List.of());

        mockMvc.perform(get("/api/algorithms").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // -------------------------------------------------------------------------
    // 500 without internal detail — AC-SERVICE-EXCEPTION-MAPPING
    // -------------------------------------------------------------------------

    @Test
    void serviceExceptionReturns500WithoutInternalDetail() throws Exception {
        when(algorithmAnnouncementService.announcedAlgorithms())
                .thenThrow(new RuntimeException("synthetic"));

        mockMvc.perform(get("/api/algorithms").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(
                        result -> {
                            String body = result.getResponse().getContentAsString();
                            // Must not leak internal exception detail
                            assertBodyDoesNotContain(body, "synthetic");
                            assertBodyDoesNotContain(body, "RuntimeException");
                        });
    }

    private static void assertBodyDoesNotContain(String body, String forbidden) {
        if (body.contains(forbidden)) {
            throw new AssertionError(
                    "Response body contains forbidden string '" + forbidden + "': " + body);
        }
    }
}
