// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tournament.MatchGeneratorInfo;
import java.util.List;
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
 * Integration test for {@link MatchGeneratorInfoController} (E58S05 AC1 + AC8 TDD RED-first).
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>AC-REST-IT-HAPPY-MatchGeneratorInfoController: authenticated GET {@code
 *       /api/match-generators} returns 200 with a non-empty array of {@code MatchGeneratorInfo}
 *       records against the real Spring context (real registry, real generators).
 *   <li>AC-REST-IT-SEC-MatchGeneratorInfoController: anonymous GET returns 401.
 * </ul>
 *
 * @see MatchGeneratorInfoController
 * @see de.vvwt.tm.tournament.MatchGeneratorInfo
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (AC8)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-73">DEC-73 — D-5 + REST endpoint</a>
 * @see <a href="E58S05">E58S05 — AC1, AC8</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, MatchGeneratorInfoControllerIT.TestAdminCredentials.class})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:matchgeneratorinfoctrlitdb"
                    + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@DisplayName("MatchGeneratorInfoController IT — E58S05 AC1+AC8 TDD (2-test minimalist)")
class MatchGeneratorInfoControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E58S05MatchGeneratorInfoCtrlIT01";

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
    // AC-REST-IT-HAPPY: authenticated GET returns 200 + real generator list
    // =========================================================================

    @Test
    @DisplayName(
            "AC-REST-IT-HAPPY: authenticated GET /api/match-generators returns 200 with"
                    + " non-empty generator list containing keyId + isLastPhaseGenerator")
    void authenticatedGet_returnsGeneratorInfoList() {
        ResponseEntity<List<MatchGeneratorInfo>> response =
                authed.exchange(
                        baseUrl + "/api/match-generators",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<List<MatchGeneratorInfo>>() {});

        assertThat(response.getStatusCode())
                .as("authenticated GET must return 200 OK")
                .isEqualTo(HttpStatus.OK);

        List<MatchGeneratorInfo> body = response.getBody();
        assertThat(body).as("response body must not be null").isNotNull();
        assertThat(body).as("generator list must not be empty").isNotEmpty();
        // Verify each record has keyId and isLastPhaseGenerator
        body.forEach(
                info -> {
                    assertThat(info.keyId())
                            .as("keyId must be non-null and non-empty")
                            .isNotBlank();
                });
        // Exactly one generator should have isLastPhaseGenerator==true (the awardCeremony
        // generator)
        long lastPhaseCount =
                body.stream().filter(MatchGeneratorInfo::isLastPhaseGenerator).count();
        assertThat(lastPhaseCount)
                .as("exactly one registered generator must have isLastPhaseGenerator==true")
                .isEqualTo(1L);
    }

    // =========================================================================
    // AC-REST-IT-SEC: anonymous GET returns 401
    // =========================================================================

    @Test
    @DisplayName("AC-REST-IT-SEC: anonymous GET /api/match-generators returns 401")
    void anonymousGet_returns401() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(baseUrl + "/api/match-generators", String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Test-local AdminCredentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("matchGenInfoItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
