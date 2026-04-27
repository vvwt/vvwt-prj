package de.vvwt.tm.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.tm.certificate.CertificateTemplateFormatException;
import de.vvwt.tm.certificate.CertificateTemplateSizeException;
import de.vvwt.tm.certificate.CertificateTemplateStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RED-first tests for Phase 3 certificate-exception handler methods in {@link
 * GlobalExceptionHandler} (E36S08, AC-PHASE3-RED-FIRST-PER-METHOD, AC-DEC41-FRESH-RED-FIRST-TESTS,
 * AC-C11-BEHAVIORAL-EQUIVALENCE).
 *
 * <p>Authored fresh per DEC-41 §3 clause (1): CertificateExceptionAdvice had no standalone test
 * files (behavior tested indirectly via CertificateTemplateControllerIT). These are NEW tests.
 *
 * <p>Uses standalone {@link MockMvcBuilders#standaloneSetup} — same pattern as {@link
 * GlobalExceptionHandlerTest}. Wires exactly {@link GlobalExceptionHandler} + {@link
 * TestCertificateThrowingController}.
 *
 * <h2>HTTP mapping (AC-C11-BEHAVIORAL-EQUIVALENCE — from deleted CertificateExceptionAdvice)</h2>
 *
 * <ul>
 *   <li>{@link CertificateTemplateFormatException} → 400 Bad Request, messageKey {@code
 *       error.certificateTemplate.format}
 *   <li>{@link CertificateTemplateSizeException} → 400 Bad Request, messageKey {@code
 *       error.certificateTemplate.tooLarge}
 *   <li>{@link CertificateTemplateStorageException} → 500 Internal Server Error, messageKey {@code
 *       error.certificateTemplate.storage}
 * </ul>
 *
 * @see GlobalExceptionHandler
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-41">DEC-41 — RED-first hierarchy clause 1</a>
 * @see <a href="E36S08">E36S08 — Phase 3 certificate exception absorption</a>
 */
@DisplayName("GlobalExceptionHandler — E36S08 Phase 3 certificate exception mapping")
class GlobalExceptionHandlerCertificateTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new TestCertificateThrowingController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    // =========================================================================
    // Phase 3d: CertificateTemplateFormatException → 400
    // =========================================================================

    @Test
    @DisplayName(
            "CertificateTemplateFormatException maps to HTTP 400 with messageKey"
                    + " error.certificateTemplate.format")
    void certTemplateFormat_mapsTo400() throws Exception {
        mockMvc.perform(get("/test-cert/format").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.messageKey").value("error.certificateTemplate.format"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // =========================================================================
    // Phase 3e: CertificateTemplateSizeException → 400
    // =========================================================================

    @Test
    @DisplayName(
            "CertificateTemplateSizeException maps to HTTP 400 with messageKey"
                    + " error.certificateTemplate.tooLarge")
    void certTemplateSize_mapsTo400() throws Exception {
        mockMvc.perform(get("/test-cert/size").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.messageKey").value("error.certificateTemplate.tooLarge"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // =========================================================================
    // Phase 3f: CertificateTemplateStorageException → 500
    // =========================================================================

    @Test
    @DisplayName(
            "CertificateTemplateStorageException maps to HTTP 500 with messageKey"
                    + " error.certificateTemplate.storage")
    void certTemplateStorage_mapsTo500() throws Exception {
        mockMvc.perform(get("/test-cert/storage").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.messageKey").value("error.certificateTemplate.storage"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // =========================================================================
    // Minimal test-only controller
    // =========================================================================

    @RestController
    @RequestMapping("/test-cert")
    static class TestCertificateThrowingController {

        @GetMapping("/format")
        void throwCertFormat() {
            throw new CertificateTemplateFormatException("unsupported certificate template format");
        }

        @GetMapping("/size")
        void throwCertSize() {
            throw new CertificateTemplateSizeException("certificate template exceeds maximum size");
        }

        @GetMapping("/storage")
        void throwCertStorage() {
            throw new CertificateTemplateStorageException(
                    "certificate template storage failure", new RuntimeException("io error"));
        }
    }
}
