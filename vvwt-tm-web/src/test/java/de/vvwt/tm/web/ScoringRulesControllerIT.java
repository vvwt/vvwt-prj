package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Minimalist integration test for {@link ScoringRulesController} (E22S08,
 * AC-S08-IT-ANNOTATION-SWAP, AC-S08-IT-REGISTRY-REAL-PARTICIPATE,
 * AC-S08-SECURITY-NEGATIVE-TESTS-GREEN).
 *
 * <p>Relocated and renamed from {@code de.vvwt.tm.tournament.TournamentRulesControllerIT} (E21S10)
 * to {@code de.vvwt.tm.web.ScoringRulesControllerIT} in E22S08 (DEC-40 Clause A Q-1b).
 *
 * <p>Annotation re-targeted to {@code web} module scope via
 * {@code @ApplicationModuleTest(webEnvironment = RANDOM_PORT)} per DEC-38/DEC-40 amendment. Uses
 * {@code WebModuleTestConfig} (shared web-module test infrastructure).
 *
 * <h2>AC-S08-IT-REGISTRY-REAL-PARTICIPATE</h2>
 *
 * <p>Real {@code de.vvwt.tm.scoring.ScoringRuleRegistry} and {@code
 * de.vvwt.tm.scoring.SetValidationRuleRegistry} participate in this IT — no {@code @MockitoBean}
 * for scoring registries. The web module declares {@code scoring} in {@code allowedDependencies},
 * so real scoring beans are present in the context (AC-S08-REVERSE-MOCKITOBEAN-REMOVAL: mocks
 * removed from {@code WebModuleTestConfig}).
 *
 * <h2>AC-S08-NO-TRANSIENT-404-SMOKE</h2>
 *
 * <p>The renamed endpoint {@code GET /api/scoring/rules} returns HTTP 200 with the same JSON body
 * shape as the legacy {@code GET /api/tournament-rules}. Wire format verified: four keys
 * (scoringRuleIds, setValidationRuleIds, matchGeneratorIds, matchFormats), each a non-empty list.
 *
 * @see ScoringRulesController
 * @see ScoringRulesControllerSliceTest
 * @see <a href="DEC-38">DEC-38 — @ApplicationModuleTest canon for reconstructed modules</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation; reverse @MockitoBean case</a>
 * @see <a href="E22S08">E22S08 — relocate + rename + re-point scoring registries</a>
 */
@ApplicationModuleTest(
        mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WebModuleTestConfig.class)
@ActiveProfiles("test")
@DisplayName("ScoringRulesController IT — E22S08 web-module (2-test minimalist)")
class ScoringRulesControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E22S08ScoringRulesControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);
    }

    // =========================================================================
    // AC-S08-NO-TRANSIENT-404-SMOKE + AC-S08-IT-REGISTRY-REAL-PARTICIPATE:
    // authenticated GET /api/scoring/rules returns 200 + correct JSON shape
    // =========================================================================

    @Test
    @DisplayName(
            "AC-S08-IT-HAPPY: authenticated GET /api/scoring/rules returns 200 + four non-empty"
                    + " lists (real scoring registries participate)")
    @SuppressWarnings("unchecked")
    void authenticatedGet_returns200WithRuleRegistries() {
        ResponseEntity<Map<String, List<String>>> response =
                authed.exchange(
                        baseUrl + "/api/scoring/rules",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<Map<String, List<String>>>() {});

        assertThat(response.getStatusCode())
                .as("authenticated GET /api/scoring/rules must return 200 OK")
                .isEqualTo(HttpStatus.OK);

        Map<String, List<String>> body = response.getBody();
        assertThat(body).as("response body must not be null").isNotNull();
        assertThat(body).containsKey("scoringRuleIds");
        assertThat(body).containsKey("setValidationRuleIds");
        assertThat(body).containsKey("matchGeneratorIds");
        assertThat(body).containsKey("matchFormats");
        assertThat(body.get("matchFormats"))
                .as("matchFormats must list MatchFormat enum values")
                .isNotEmpty()
                .contains("BEST_OF_1", "BEST_OF_3");
    }

    // =========================================================================
    // AC-S08-SECURITY-NEGATIVE-TESTS-GREEN: anonymous GET returns 401
    // =========================================================================

    @Test
    @DisplayName("AC-S08-SEC: anonymous GET /api/scoring/rules returns 401")
    void anonymousGet_returns401() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(baseUrl + "/api/scoring/rules", String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated request to /api/scoring/rules must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Test-local AdminCredentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
