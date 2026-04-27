package de.vvwt.tm.infoportal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.vvwt.tm.infoportal.InfoPortalPublisherService.PublisherStatus;
import de.vvwt.tm.infoportal.InfoPortalPublisherService.PublisherStatus.DeprecationSeverity;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for {@link InfoPortalStatusController} — AC1 (DEC-22 RED-first), AC4, AC10.
 *
 * <p>DEC-22 Iron Law: written RED-first before production class exists.
 *
 * <p>Uses standalone MockMvc (no Spring Boot context) following the pattern established in
 * {@code AdminSpaControllerTest}.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09 AC1, AC4, AC10</a>
 */
class InfoPortalStatusControllerTest {

    private InfoPortalPublisherService publisherService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        publisherService = mock(InfoPortalPublisherService.class);
        InfoPortalStatusController controller = new InfoPortalStatusController(publisherService);
        // Configure Jackson with JavaTimeModule so LocalDate serializes as ISO string
        // (not array), matching the production JacksonConfig behaviour.
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    // -------------------------------------------------------------------------
    // AC1 — controller class existence (DEC-22 RED-first)
    // -------------------------------------------------------------------------

    @Test
    void getStatus_returns200() throws Exception {
        PublisherStatus status = new PublisherStatus();
        when(publisherService.getStatus()).thenReturn(status);

        mockMvc.perform(get("/api/info-portal/status"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // AC4 — deprecation warning surface in status response
    // -------------------------------------------------------------------------

    @Test
    void getStatus_withDeprecationWarning_includesAlgorithmAndDate() throws Exception {
        PublisherStatus status = new PublisherStatus();
        status.setDeprecationWarning(
                "ed25519",
                LocalDate.of(2026, 7, 1),
                DeprecationSeverity.LOW);
        when(publisherService.getStatus()).thenReturn(status);

        mockMvc.perform(get("/api/info-portal/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecationWarning.algorithmId").value("ed25519"))
                .andExpect(jsonPath("$.deprecationWarning.deprecationDate").value("2026-07-01"))
                .andExpect(jsonPath("$.deprecationWarning.severity").value("LOW"));
    }

    @Test
    void getStatus_withHighSeverityDeprecation_includesHighSeverity() throws Exception {
        PublisherStatus status = new PublisherStatus();
        status.setDeprecationWarning(
                "ed25519",
                LocalDate.now().plusDays(10),
                DeprecationSeverity.HIGH);
        when(publisherService.getStatus()).thenReturn(status);

        mockMvc.perform(get("/api/info-portal/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecationWarning.severity").value("HIGH"));
    }

    @Test
    void getStatus_withNoDeprecationWarning_returnsNullWarningField() throws Exception {
        PublisherStatus status = new PublisherStatus();
        when(publisherService.getStatus()).thenReturn(status);

        mockMvc.perform(get("/api/info-portal/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecationWarning").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // AC10 — error state fields in status response
    // -------------------------------------------------------------------------

    @Test
    void getStatus_whenAlgorithmDeprecatedHardStop_reflectsInResponse() throws Exception {
        PublisherStatus status = new PublisherStatus();
        status.setAlgorithmDeprecatedHardStop(true);
        when(publisherService.getStatus()).thenReturn(status);

        mockMvc.perform(get("/api/info-portal/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithmDeprecatedHardStop").value(true));
    }

    @Test
    void getStatus_whenRegistrationError_reflectsInResponse() throws Exception {
        PublisherStatus status = new PublisherStatus();
        status.setRegistrationError(true);
        when(publisherService.getStatus()).thenReturn(status);

        mockMvc.perform(get("/api/info-portal/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationError").value(true));
    }
}
