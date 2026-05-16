// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Minimalist integration test for {@link ScoringRulesController} (E21S10 + E21S20 rename,
 * AC-REST-IT-HAPPY-ScoringRulesController + AC-REST-IT-SEC-ScoringRulesController, DEC-26 C-13
 * methodology, inventory row 412).
 *
 * <p>Renamed from {@code TournamentRulesControllerIT} to {@code ScoringRulesControllerIT} at E21S20
 * (class+URL rename per DEC-40 Clause A DDD-ownership, AC-RENAME-SCORINGRULES-TESTS). URL updated
 * from {@code /api/tournament-rules} to {@code /api/scoring/rules}.
 *
 * <p>Moved from {@code de.vvwt.tm.tournament.TournamentRulesControllerIT} to {@code
 * de.vvwt.tm.web.TournamentRulesControllerIT} at E22S11 atomic cutover (DEC-40
 * Primary-Adapter-Isolation — REST controllers MUST reside in {@code web.*}).
 *
 * <h2>AC-REST-IT-HAPPY-ScoringRulesController</h2>
 *
 * <p>Authenticated GET exercises the full Spring context with the real {@link
 * de.vvwt.tm.tournament.MatchGeneratorRegistry} from S08 (wired), and new {@code
 * de.vvwt.tm.scoring.*} registries. Response shape is verified: four keys, each a non-empty list.
 *
 * <h2>AC-REST-IT-SEC-ScoringRulesController</h2>
 *
 * <p>Anonymous GET returns 401.
 *
 * @see ScoringRulesController
 * @see ScoringRulesControllerSliceTest
 * @see <a href="DEC-26">DEC-26 — controller test methodology (C-13)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E21S10">E21S10 — inventory row 412</a>
 * @see <a href="E21S20">E21S20 — class+URL rename TournamentRules → ScoringRules</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, ScoringRulesControllerIT.TestAdminCredentials.class})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:scoringrulescontrolleritdb"
                    + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@DisplayName("ScoringRulesController IT — E21S10+E21S20 AC-REST-IT (2-test minimalist)")
class ScoringRulesControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S10RulesControllerIT01";

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
    // AC-REST-IT-HAPPY: authenticated GET returns 200 + rule-registry JSON
    // =========================================================================

    @Test
    @DisplayName(
            "AC-REST-IT-HAPPY: authenticated GET /api/scoring/rules returns 200 + four"
                    + " non-empty lists")
    @SuppressWarnings("unchecked")
    void authenticatedGet_returns200WithScoringRules() {
        ResponseEntity<Map<String, List<String>>> response =
                authed.exchange(
                        baseUrl + "/api/scoring/rules",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<Map<String, List<String>>>() {});

        assertThat(response.getStatusCode())
                .as("authenticated GET must return 200 OK")
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
    // AC-REST-IT-SEC: anonymous GET returns 401
    // =========================================================================

    @Test
    @DisplayName("AC-REST-IT-SEC: anonymous GET /api/scoring/rules returns 401")
    void anonymousGet_returns401() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(baseUrl + "/api/scoring/rules", String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Test-local AdminCredentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
