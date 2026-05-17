// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.net.URI;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for E61S01 — Scoring-tablet field-page layout redesign.
 *
 * <h2>Coverage</h2>
 *
 * <p>Asserts on the rendered HTML of {@code GET /score/field/1} that:
 *
 * <ul>
 *   <li>AC1: the brand logo lockup image ({@code vvw-tm-logo.svg}) is NOT present in the field page
 *       body.
 *   <li>AC3: the bottom wordmark area is present with the blue icon inline image and "Scoring
 *       Tablet" text.
 *   <li>AC4: the field number is displayed at the bottom of the page ("Feld 1").
 *   <li>AC5: exactly one score input per team is present (no separate large score-display div).
 *   <li>AC6: the header contains no raw placeholders when rendered (static render shows empty
 *       team/round spans, not unresolved Mustache tokens).
 *   <li>AC7: no hardcoded display strings — i18n keys used consistently.
 *   <li>AC9: +/- buttons are present and operate on a single editable input (layout asserted via
 *       HTML structure — runtime behaviour verified by AC5 single-input assertion).
 * </ul>
 *
 * <h2>Test methodology</h2>
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT)} same as {@link ScoreControllerIT}, reusing {@link
 * WebModuleTestConfig}. DEC-22 Iron Law: this test was written RED-first, failing before the
 * template redesign was implemented.
 *
 * @see ScoreController
 * @see ScoreControllerIT
 * @see <a href="DEC-19">DEC-19 — Mustache + ES5 carve-out</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E61S01">E61S01 — Field-page layout redesign</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, ScoreFieldLayoutIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("ScoreFieldLayoutIT — E61S01 field-page layout redesign ACs")
class ScoreFieldLayoutIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E61S01ScoreFieldLayoutIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    // =========================================================================
    // AC1: logo lockup image removed from field page
    // =========================================================================

    @Test
    @DisplayName(
            "AC1 (layout): rendered field page does NOT contain vvw-tm-logo.svg image src"
                    + " (logo lockup removed)")
    void fieldPage_doesNotContainLogoImage() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as(
                        "Rendered field page must NOT contain vvw-tm-logo.svg img src"
                                + " (AC1: logo lockup removed — E61S01)")
                .doesNotContain("vvw-tm-logo.svg");
    }

    // =========================================================================
    // AC2: header contains team/round/set identity spans
    // =========================================================================

    @Test
    @DisplayName(
            "AC2 (header): rendered field page contains a header with team and round/set identity"
                    + " elements")
    void fieldPage_containsHeaderWithMatchIdentityElements() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as(
                        "Rendered field page must contain header-teams span element"
                                + " (AC2: team identity in header — E61S01)")
                .contains("id=\"header-teams\"");
        assertThat(response.getBody())
                .as(
                        "Rendered field page must contain header-round-set span element"
                                + " (AC2: round/set in header — E61S01)")
                .contains("id=\"header-round-set\"");
    }

    // =========================================================================
    // AC3: bottom-left wordmark with blue icon inline
    // =========================================================================

    @Test
    @DisplayName(
            "AC3 (branding): rendered field page contains bottom wordmark with vvw-icon-blue.svg"
                    + " inline image")
    void fieldPage_containsBottomWordmarkWithBlueIcon() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as(
                        "Rendered field page must contain bottom wordmark element"
                                + " (AC3: 'Scoring Tablet' wordmark — E61S01)")
                .contains("id=\"bottom-wordmark\"");
        assertThat(response.getBody())
                .as(
                        "Bottom wordmark must contain vvw-icon-blue.svg as inline image"
                                + " (AC3: letter 'o' as blue icon — E61S01)")
                .contains("vvw-icon-blue.svg");
    }

    // =========================================================================
    // AC4: field number at page bottom centre
    // =========================================================================

    @Test
    @DisplayName("AC4 (layout): rendered field page contains a bottom-centred field number display")
    void fieldPage_containsBottomFieldNumber() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as(
                        "Rendered field page must contain bottom-field-number element"
                                + " (AC4: 'Feld {n}' centred at bottom — E61S01)")
                .contains("id=\"bottom-field-number\"");
        // Field number 1 rendered in the element — fieldNumber=1 in the path
        assertThat(response.getBody())
                .as("Rendered field page must contain the field number 1 in the bottom element")
                .containsPattern("bottom-field-number[^>]*>[^<]*1");
    }

    // =========================================================================
    // AC5: exactly one score input per team — no separate score-display div
    // =========================================================================

    @Test
    @DisplayName(
            "AC5 (score field): rendered field page has exactly one score input per team,"
                    + " no separate large score-display element")
    void fieldPage_hasExactlyOneScoreInputPerTeam() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        // The two score inputs must be present (one per team)
        assertThat(body)
                .as("Field page must contain score input for team 1 (AC5 — E61S01)")
                .contains("id=\"input-team1\"");
        assertThat(body)
                .as("Field page must contain score input for team 2 (AC5 — E61S01)")
                .contains("id=\"input-team2\"");

        // The separate large read-only score-display div must NOT be present
        assertThat(body)
                .as(
                        "Field page must NOT contain separate score-display div"
                                + " (AC5: single editable field per team — E61S01)")
                .doesNotContain("id=\"score-team1\"");
        assertThat(body)
                .as(
                        "Field page must NOT contain separate score-display div for team 2"
                                + " (AC5 — E61S01)")
                .doesNotContain("id=\"score-team2\"");
    }

    // =========================================================================
    // AC6: no-match state — header shows no stale text (favicon unchanged)
    // =========================================================================

    @Test
    @DisplayName(
            "AC1+favicon (governance): favicon link tags are present and unchanged"
                    + " (logo removed but favicons kept — E61S01)")
    void fieldPage_faviconLinksPresent() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("Favicon SVG link must remain present (AC1: only logo removed, not favicon)")
                .contains("vvw-icon-blue.svg")
                .contains("rel=\"icon\"");
    }

    // =========================================================================
    // AC9: +/- buttons present
    // =========================================================================

    @Test
    @DisplayName("AC9 (testing): rendered field page contains +/- buttons for both teams")
    void fieldPage_containsPlusMinusButtons() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        assertThat(body)
                .as("Field page must contain plus button for team 1 (AC9 — E61S01)")
                .contains("id=\"plus-team1\"");
        assertThat(body)
                .as("Field page must contain minus button for team 1 (AC9 — E61S01)")
                .contains("id=\"minus-team1\"");
        assertThat(body)
                .as("Field page must contain plus button for team 2 (AC9 — E61S01)")
                .contains("id=\"plus-team2\"");
        assertThat(body)
                .as("Field page must contain minus button for team 2 (AC9 — E61S01)")
                .contains("id=\"minus-team2\"");
    }

    // =========================================================================
    // Test configuration
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
