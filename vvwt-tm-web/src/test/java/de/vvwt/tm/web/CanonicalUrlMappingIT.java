package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Spring integration test that asserts canonical Wave-2 URL patterns are registered by {@link
 * RequestMappingHandlerMapping} introspection (Story E11S08, AC8).
 *
 * <h2>DEC-22 RED-first procedural attestation</h2>
 *
 * <p>This test was committed in TWO phases per DEC-22 Iron Law:
 *
 * <ol>
 *   <li><b>RED commit (E11S08 commit-1):</b> {@code AUDIO_LIST_PATTERN} is deliberately set to the
 *       LEGACY value {@code /api/tournaments/{tournamentId}/audio}. On execution against the
 *       canonical-state backend (E26S03 reconstruction, merged 2026-04-22), the IT FAILS because
 *       the legacy pattern resolves to no {@code @RequestMapping} in the canonical backend. The
 *       failing assertion text is captured in {@code impl-report.md} § "AC8 RED step" as the audit
 *       trail.
 *   <li><b>GREEN commit (E11S08 commit-2):</b> {@code AUDIO_LIST_PATTERN} is corrected to the
 *       canonical value {@code /api/audio/tournaments/{tournamentId}}. On execution the IT PASSES.
 * </ol>
 *
 * <p>Git history of this file (2 commits on story/E11S08) is the DEC-22 attestation. DEC-41
 * §spec-anchored carve-out is NOT invoked — AC8 is a regression check authored RED-first via
 * deliberate-failure procedure, not a property test or external-spec test.
 *
 * <h2>Annotation (DEC-44 D1)</h2>
 *
 * <p>{@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)} —
 * web-module ITs use the full application context per DEC-44 D1 (retro-correction of DEC-40 Clause
 * E §Sub-Clause-3 threshold; web-module scope too broad for {@code @ApplicationModuleTest}).
 *
 * @see de.vvwt.tm.web.timer.AudioController
 * @see de.vvwt.tm.web.timer.TimerController
 * @see de.vvwt.tm.web.timer.TimerViewController
 * @since E11S08
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e11s08canonicalurldb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.main.allow-bean-definition-overriding=true"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, CanonicalUrlMappingIT.TestAdminCredentials.class})
@DisplayName("CanonicalUrlMappingIT — E11S08 AC8: Wave-2 URL patterns registered")
class CanonicalUrlMappingIT {

    // ── Canonical Wave-2 URL patterns (hardcoded per Brief Q4=(F)) ──────────────

    /**
     * AC8 RED substitution: deliberately set to the LEGACY pattern for commit-1 (RED).
     *
     * <p>LEGACY (pre-E26S03): {@code /api/tournaments/{tournamentId}/audio}
     *
     * <p>CANONICAL (E26S03, 2026-04-22): {@code /api/audio/tournaments/{tournamentId}}
     *
     * <p>This constant is corrected to canonical in commit-2 (GREEN). The RED/GREEN transition is
     * the DEC-22 Iron Law attestation for AC8.
     */
    private static final String AUDIO_LIST_PATTERN =
            "/api/audio/tournaments/{tournamentId}"; // GREEN — canonical Wave-2 pattern

    private static final String AUDIO_UPLOAD_PATTERN =
            "/api/audio/tournaments/{tournamentId}/{category}"; // POST
    private static final String AUDIO_DELETE_PATTERN =
            "/api/audio/tournaments/{tournamentId}/{category}"; // DELETE
    private static final String AUDIO_STREAM_PATTERN =
            "/api/audio/tournaments/{tournamentId}/{category}/stream";
    private static final String TIMER_DATA_PATTERN = "/api/timer/tournaments/{tournamentId}";
    private static final String TIMER_VIEW_PATTERN = "/timer/tournaments/{tournamentId}";

    static final String TEST_PASSWORD = "CanonicalUrlMappingIT-E11S08";

    @Autowired private RequestMappingHandlerMapping requestMappingHandlerMapping;

    // ── AC8 assertions ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AudioController: GET /api/audio/tournaments/{tournamentId} is registered (list)")
    void audioListPatternIsRegistered() {
        Set<String> allPatterns = collectAllPatternStrings();
        assertThat(allPatterns)
                .as(
                        "Expected AudioController.list GET mapping at '%s' but it was not found."
                                + " Found patterns: %s",
                        AUDIO_LIST_PATTERN, allPatterns)
                .contains(AUDIO_LIST_PATTERN);
    }

    @Test
    @DisplayName(
            "AudioController: POST+DELETE /api/audio/tournaments/{tournamentId}/{category} is"
                    + " registered")
    void audioUploadAndDeletePatternIsRegistered() {
        Set<String> allPatterns = collectAllPatternStrings();
        assertThat(allPatterns)
                .as(
                        "Expected AudioController POST/DELETE mapping at '%s' but it was not found."
                                + " Found patterns: %s",
                        AUDIO_UPLOAD_PATTERN, allPatterns)
                .contains(AUDIO_UPLOAD_PATTERN);
    }

    @Test
    @DisplayName(
            "AudioController: GET /api/audio/tournaments/{tournamentId}/{category}/stream is"
                    + " registered")
    void audioStreamPatternIsRegistered() {
        Set<String> allPatterns = collectAllPatternStrings();
        assertThat(allPatterns)
                .as(
                        "Expected AudioController.stream GET mapping at '%s' but it was not found."
                                + " Found patterns: %s",
                        AUDIO_STREAM_PATTERN, allPatterns)
                .contains(AUDIO_STREAM_PATTERN);
    }

    @Test
    @DisplayName("TimerController: GET /api/timer/tournaments/{tournamentId} is registered")
    void timerDataPatternIsRegistered() {
        Set<String> allPatterns = collectAllPatternStrings();
        assertThat(allPatterns)
                .as(
                        "Expected TimerController GET mapping at '%s' but it was not found."
                                + " Found patterns: %s",
                        TIMER_DATA_PATTERN, allPatterns)
                .contains(TIMER_DATA_PATTERN);
    }

    @Test
    @DisplayName("TimerViewController: GET /timer/tournaments/{tournamentId} is registered")
    void timerViewPatternIsRegistered() {
        Set<String> allPatterns = collectAllPatternStrings();
        assertThat(allPatterns)
                .as(
                        "Expected TimerViewController GET mapping at '%s' but it was not found."
                                + " Found patterns: %s",
                        TIMER_VIEW_PATTERN, allPatterns)
                .contains(TIMER_VIEW_PATTERN);
    }

    // ── Helper ────────────────────────────────────────────────────────────────────

    /**
     * Collects all URL pattern strings from all registered {@code @RequestMapping} handler methods.
     *
     * <p>Each {@link org.springframework.web.servlet.mvc.method.RequestMappingInfo} may expose
     * multiple patterns. We flatten to a {@code Set<String>} for containment assertions.
     */
    private Set<String> collectAllPatternStrings() {
        return requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(
                        info -> {
                            var patterns = info.getPatternValues();
                            if (!patterns.isEmpty()) {
                                return patterns.stream();
                            }
                            // PathPatternsRequestCondition path — also check parsed patterns
                            var pathPatternsCondition = info.getPathPatternsCondition();
                            if (pathPatternsCondition != null) {
                                return pathPatternsCondition.getPatterns().stream()
                                        .map(p -> p.getPatternString());
                            }
                            return java.util.stream.Stream.empty();
                        })
                .collect(Collectors.toSet());
    }

    // ── Per-IT auth substitute ─────────────────────────────────────────────────

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider adminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode(TEST_PASSWORD);
            return new AdminCredentialsProvider() {
                @Override
                public String getPasswordHash() {
                    return hash;
                }
            };
        }
    }
}
