// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.tm.tournament.exceptions.ConflictException;
import de.vvwt.tm.tournament.exceptions.ForbiddenException;
import de.vvwt.tm.tournament.exceptions.TooManyRequestsException;
import de.vvwt.tm.tournament.exceptions.TournamentNotFoundException;
import de.vvwt.tm.tournament.exceptions.UnauthorizedException;
import de.vvwt.tm.tournament.exceptions.ValidationException;
import java.sql.SQLException;
import java.util.UUID;
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
 * Integration test for {@link GlobalExceptionHandler} (E21S10, AC-TDD-GlobalExceptionHandler,
 * AC-GLOBAL-EXCEPTION-HANDLER-INTEGRATION, AC-GLOBAL-EXCEPTION-HANDLER-UNKNOWN,
 * AC-GLOBAL-EXCEPTION-HANDLER-SCOPE-BOUNDED, AC-SEC-NO-EXCEPTION-LEAK).
 *
 * <p>This test was committed RED: {@link GlobalExceptionHandler} at {@code
 * de.vvwt.tm.tournament.internal.web} did not exist at commit time — satisfying the DEC-22 Iron
 * Law.
 *
 * <p>Uses standalone {@link MockMvcBuilders#standaloneSetup} to avoid loading the full application
 * context and thus the legacy {@code @ControllerAdvice} bean-name conflict during the
 * reconstruction-in-place parallel phase. Each test wires exactly {@link GlobalExceptionHandler} +
 * {@link TestThrowingController} — no other beans participate.
 *
 * <h2>Scope-bounded verification (AC-GLOBAL-EXCEPTION-HANDLER-SCOPE-BOUNDED)</h2>
 *
 * <p>Verified structurally: the handler uses {@code @ControllerAdvice(basePackages =
 * "de.vvwt.tm.tournament")}. The test controller is at {@code de.vvwt.tm.tournament.internal.web} —
 * within scope. Legacy non-tournament exceptions are verified absent from imports via grep AC in QA
 * (AC-GLOBAL-EXCEPTION-HANDLER-SCOPE-BOUNDED).
 *
 * @see GlobalExceptionHandler
 * @see de.vvwt.tm.tournament.ApiErrorResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-26">DEC-26 — controller test methodology (C-13)</a>
 * @see <a href="E21S10">E21S10 — inventory row 450</a>
 */
@DisplayName("GlobalExceptionHandler — E21S10 exception mapping integration tests")
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Standalone setup: only the handler + test controller, no Spring Security layer
        // needed — security is wired in slice/IT tests; here we test HTTP mapping directly.
        mockMvc =
                MockMvcBuilders.standaloneSetup(new TestThrowingController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    // =========================================================================
    // Five S09 exception mappings (AC-GLOBAL-EXCEPTION-HANDLER-INTEGRATION)
    // =========================================================================

    @Test
    @DisplayName("ForbiddenException maps to HTTP 403 with ApiErrorResponse body")
    void forbidden_mapsTo403() throws Exception {
        mockMvc.perform(get("/test-throw/forbidden").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("TooManyRequestsException maps to HTTP 429 with ApiErrorResponse body")
    void tooManyRequests_mapsTo429() throws Exception {
        mockMvc.perform(get("/test-throw/too-many-requests").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("UnauthorizedException maps to HTTP 401 with ApiErrorResponse body")
    void unauthorized_mapsTo401() throws Exception {
        mockMvc.perform(get("/test-throw/unauthorized").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("ValidationException maps to HTTP 400 with ApiErrorResponse body")
    void validation_mapsTo400() throws Exception {
        mockMvc.perform(get("/test-throw/validation").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("ConflictException maps to HTTP 409 with ApiErrorResponse body")
    void conflict_mapsTo409() throws Exception {
        mockMvc.perform(get("/test-throw/conflict").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // =========================================================================
    // Unknown RuntimeException → 500, no stack trace (AC-GLOBAL-EXCEPTION-HANDLER-UNKNOWN)
    // =========================================================================

    @Test
    @DisplayName("Unknown RuntimeException maps to HTTP 500 with generic message — no stack trace")
    void unknownRuntimeException_mapsTo500_noStackTrace() throws Exception {
        String responseBody =
                mockMvc.perform(get("/test-throw/npe").accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.status").value(500))
                        .andExpect(jsonPath("$.message").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // AC-GLOBAL-EXCEPTION-HANDLER-UNKNOWN: message must not contain stack trace markers
        assertThat(responseBody).doesNotContain("NullPointerException");
        assertThat(responseBody).doesNotContain("\tat ");
    }

    // =========================================================================
    // Security: no SQL leakage in 500 body (AC-SEC-NO-EXCEPTION-LEAK)
    // =========================================================================

    @Test
    @DisplayName("SQLException body not leaked — generic 500 message without SQL statement text")
    void sqlException_mapsTo500_noSqlLeak() throws Exception {
        String responseBody =
                mockMvc.perform(get("/test-throw/sql").accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.status").value(500))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // AC-SEC-NO-EXCEPTION-LEAK: SQL statement must not appear in response
        assertThat(responseBody).doesNotContain("SELECT * FROM users WHERE password=");
        assertThat(responseBody).doesNotContain("SQLException");
        assertThat(responseBody).doesNotContain("\tat ");
    }

    // =========================================================================
    // TournamentNotFoundException → HTTP 404 (AC-REDFIRST-TNFE-HANDLER-TEST, E24S05)
    // =========================================================================

    @Test
    @DisplayName(
            "TournamentNotFoundException maps to HTTP 404 with ApiErrorResponse — errorKey"
                    + " error.tournament.notFound")
    void tournamentNotFound_mapsTo404() throws Exception {
        mockMvc.perform(get("/test-throw/tournament-not-found").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.messageKey").value("error.tournament.notFound"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // =========================================================================
    // Minimal test-only controller
    // =========================================================================

    /**
     * Minimal REST controller used ONLY for GlobalExceptionHandler testing.
     *
     * <p>Lives inside the test source tree (test-scope only). Placed at {@code
     * de.vvwt.tm.tournament.internal.web} — within the handler's {@code basePackages} scope.
     */
    @RestController
    @RequestMapping("/test-throw")
    static class TestThrowingController {

        @GetMapping("/forbidden")
        void throwForbidden() {
            throw new ForbiddenException("Forbidden operation");
        }

        @GetMapping("/too-many-requests")
        void throwTooManyRequests() {
            throw new TooManyRequestsException("Rate limit exceeded");
        }

        @GetMapping("/unauthorized")
        void throwUnauthorized() {
            throw new UnauthorizedException("Not authenticated");
        }

        @GetMapping("/validation")
        void throwValidation() {
            throw new ValidationException("Invalid input");
        }

        @GetMapping("/conflict")
        void throwConflict() {
            throw new ConflictException("Resource already exists");
        }

        @GetMapping("/npe")
        void throwNpe() {
            throw new NullPointerException("intentional NPE for testing");
        }

        @GetMapping("/sql")
        void throwSql() throws SQLException {
            throw new SQLException("SELECT * FROM users WHERE password='secret123'");
        }

        @GetMapping("/tournament-not-found")
        void throwTournamentNotFound() {
            throw new TournamentNotFoundException(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"));
        }
    }
}
