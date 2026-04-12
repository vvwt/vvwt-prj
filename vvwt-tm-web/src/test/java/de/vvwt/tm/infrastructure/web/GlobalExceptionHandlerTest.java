package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.NoSuchElementException;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link GlobalExceptionHandler} (AC1, AC9, E05S03).
 *
 * <p>Uses a full {@link SpringBootTest} context (MOCK web env) with an extra
 * {@link TestController} to trigger each exception type. The test controller is
 * registered via {@link TestRestControllerConfig}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>AC1: {@link org.springframework.web.bind.MethodArgumentNotValidException} → 400</li>
 *   <li>AC1: {@link NoSuchElementException} → 404</li>
 *   <li>AC1: {@link ConflictException} → 409</li>
 *   <li>AC1: {@link Exception} → 500 without stack trace in body</li>
 *   <li>AC9: all responses include {@code messageKey} field</li>
 * </ul>
 *
 * @see GlobalExceptionHandler
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story E05S03</a>
 */
@SpringBootTest(
        classes = {
                TournamentManagerApplication.class,
                GlobalExceptionHandlerTest.TestAdminCredentials.class,
                GlobalExceptionHandlerTest.TestRestControllerConfig.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    static final String TEST_PASSWORD = "ExHandlerTest11CC";

    @Autowired
    private MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // AC1 — 400: validation error with field-level details
    // -------------------------------------------------------------------------

    @Test
    void validationError_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/test-exhandler/validate")
                        .with(httpBasic(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.messageKey").value("error.validation"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].messageKey").value("error.validation.field"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/test-exhandler/validate"));
    }

    // -------------------------------------------------------------------------
    // AC1 — 404: entity not found
    // -------------------------------------------------------------------------

    @Test
    void notFoundException_returns404() throws Exception {
        mockMvc.perform(get("/test-exhandler/notfound")
                        .with(httpBasic(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.messageKey").value("error.notFound"))
                .andExpect(jsonPath("$.path").value("/test-exhandler/notfound"));
    }

    // -------------------------------------------------------------------------
    // AC1 — 409: domain conflict
    // -------------------------------------------------------------------------

    @Test
    void conflictException_returns409() throws Exception {
        mockMvc.perform(get("/test-exhandler/conflict")
                        .with(httpBasic(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.messageKey").value("error.conflict"))
                .andExpect(jsonPath("$.path").value("/test-exhandler/conflict"));
    }

    // -------------------------------------------------------------------------
    // AC1 — 500: unexpected error, no stack trace in body
    // -------------------------------------------------------------------------

    @Test
    void unexpectedException_returns500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/test-exhandler/unexpected")
                        .with(httpBasic(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.messageKey").value("error.internal"))
                .andExpect(jsonPath("$.message").value(
                        "An unexpected error occurred. Please contact the administrator."))
                .andExpect(jsonPath("$.message").value(not(containsString("RuntimeException"))))
                .andExpect(jsonPath("$.path").value("/test-exhandler/unexpected"));
    }

    // -------------------------------------------------------------------------
    // Test REST controller configuration
    // -------------------------------------------------------------------------

    @TestConfiguration
    static class TestRestControllerConfig {
        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    /**
     * Minimal REST controller registered only during testing that deliberately throws
     * each exception type handled by {@link GlobalExceptionHandler}.
     */
    @RestController
    static class TestController {

        @PostMapping("/test-exhandler/validate")
        void validate(@Valid @RequestBody ValidatedRequest request) {
            // no-op — validation error thrown by Spring MVC before reaching here
        }

        @GetMapping("/test-exhandler/notfound")
        void notFound() {
            throw new NoSuchElementException("Test entity not found");
        }

        @GetMapping("/test-exhandler/conflict")
        void conflict() {
            throw new ConflictException("Test conflict — single active tournament allowed (DEC-5)");
        }

        @GetMapping("/test-exhandler/unexpected")
        void unexpected() {
            throw new RuntimeException("Internal implementation detail — must not appear in response");
        }

        static class ValidatedRequest {
            @NotBlank
            private String name;

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
        }
    }

    // -------------------------------------------------------------------------
    // Test configuration — predictable admin password
    // -------------------------------------------------------------------------

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
