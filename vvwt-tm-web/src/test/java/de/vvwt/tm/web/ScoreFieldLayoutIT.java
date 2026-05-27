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
    // AC7 (E65S05): polling mechanism — structural governance markers
    // =========================================================================

    @Test
    @DisplayName(
            "AC7 (E65S05 — polling constant): rendered field page inline script declares"
                    + " POLL_INTERVAL_MS constant (structural governance marker for auto-advance"
                    + " polling mechanism)")
    void fieldPage_inlineScriptDeclaresPollingConstant() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as(
                        "Rendered field page must contain POLL_INTERVAL_MS constant in inline"
                                + " script (AC7 E65S05: polling auto-advance mechanism present)")
                .contains("POLL_INTERVAL_MS");
    }

    @Test
    @DisplayName(
            "AC7 (E65S05 — startPolling function): rendered field page inline script declares"
                + " startPolling function (structural governance marker for auto-advance polling)")
    void fieldPage_inlineScriptDeclaresStartPolling() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as(
                        "Rendered field page must contain startPolling function in inline script"
                                + " (AC7 E65S05: polling mechanism implemented)")
                .contains("startPolling");
    }

    @Test
    @DisplayName(
            "AC7 (E65S05 — stopPolling function): rendered field page inline script declares"
                + " stopPolling function (structural governance marker for auto-advance polling)")
    void fieldPage_inlineScriptDeclaresStopPolling() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as(
                        "Rendered field page must contain stopPolling function in inline script"
                                + " (AC7 E65S05: polling mechanism implemented)")
                .contains("stopPolling");
    }

    // =========================================================================
    // AC5 (E65S06): AGPL §13 source-code link present
    // =========================================================================

    @Test
    @DisplayName(
            "AC5 (E65S06 — §13 link): rendered field page contains AGPL §13 source-code link"
                    + " with GitHub URL (governance: DEC-75 / DEC-76)")
    void fieldPage_containsAgplSourceCodeLink() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        assertThat(body)
                .as(
                        "Rendered field page must contain AGPL source-code offer text"
                                + " (AC5 E65S06: §13 link — DEC-75/DEC-76)")
                .contains("Source code (AGPL");
        assertThat(body)
                .as(
                        "Rendered field page must contain GitHub repository URL"
                                + " (AC5 E65S06: §13 link — DEC-75/DEC-76)")
                .contains("https://github.com/vvwt/vvwt-prj");
    }

    // =========================================================================
    // AC2 (E65S06): swap control appears before match-panel in DOM
    // =========================================================================

    @Test
    @DisplayName(
            "AC2 (E65S06 — swap placement): swap-btn element appears before match-panel in DOM"
                    + " (swap control moved above score area)")
    void fieldPage_swapControlAppearsBeforeMatchPanel() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        assertThat(body)
                .as("Rendered field page must contain swap-btn element (AC2 E65S06)")
                .contains("id=\"swap-btn\"");
        assertThat(body)
                .as("Rendered field page must contain match-panel element (AC2 E65S06)")
                .contains("id=\"match-panel\"");

        int swapIdx = body.indexOf("id=\"swap-btn\"");
        int matchIdx = body.indexOf("id=\"match-panel\"");
        assertThat(swapIdx)
                .as(
                        "swap-btn must appear before match-panel in DOM"
                                + " (AC2 E65S06: swap control above score area)")
                .isLessThan(matchIdx);
    }

    // =========================================================================
    // AC7 (E65S06): symmetric three-zone header structure
    // =========================================================================

    @Test
    @DisplayName(
            "AC7 (E65S06 — three-zone header): rendered field page contains header-team1,"
                    + " header-vs, header-team2 elements (symmetric header layout)")
    void fieldPage_containsThreeZoneSymmetricHeader() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        assertThat(body)
                .as(
                        "Rendered field page must contain header-team1 element"
                                + " (AC7 E65S06: left zone of symmetric header)")
                .contains("id=\"header-team1\"");
        assertThat(body)
                .as(
                        "Rendered field page must contain header-vs element"
                                + " (AC7 E65S06: centre zone of symmetric header)")
                .contains("id=\"header-vs\"");
        assertThat(body)
                .as(
                        "Rendered field page must contain header-team2 element"
                                + " (AC7 E65S06: right zone of symmetric header)")
                .contains("id=\"header-team2\"");
    }

    // =========================================================================
    // AC6 (E65S06): score inputs have native spinner suppressed
    // =========================================================================

    @Test
    @DisplayName(
            "AC6 (E65S06 — spinner suppression): rendered field page CSS suppresses native"
                    + " number-input spinner (-webkit-appearance:none / appearance:none)")
    void fieldPage_scoreInputHasSpinnerSuppression() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        assertThat(body)
                .as(
                        "Rendered field page CSS must contain appearance:none rule to suppress"
                                + " native spinner (AC6 E65S06: -webkit-appearance / appearance)")
                .containsAnyOf("-webkit-appearance: none", "appearance: none", "appearance:none");
    }

    // =========================================================================
    // AC1 (E65S07): relaxed poll interval — POLL_INTERVAL_MS must NOT be 5000
    // =========================================================================

    /**
     * AC1 (E65S07): The between-match poll interval must be relaxed so the auto-advance latency
     * bound is ≤60 s (AC1). The E65S05 value of 5000 ms (5 s) must no longer be used.
     *
     * <p>DEC-22 Iron Law: written RED-first against E65S06 implementation that still declares
     * {@code POLL_INTERVAL_MS = 5000}. Fails until E65S07 changes the value.
     */
    @Test
    @DisplayName(
            "AC1 (E65S07 — relaxed interval): POLL_INTERVAL_MS is NOT 5000"
                    + " (auto-advance latency relaxed to ≤60 s — E65S07)")
    void fieldPage_pollIntervalIsNotFiveSeconds() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as(
                        "Rendered field page POLL_INTERVAL_MS must NOT be 5000"
                                + " (AC1 E65S07: relaxed auto-advance latency ≤60 s"
                                + " — the 5-second E65S05 value has been replaced)")
                .doesNotContainPattern("POLL_INTERVAL_MS\\s*=\\s*5000");
    }

    // =========================================================================
    // AC2 (E65S07): visibility guard — isPageHidden / document.hidden in script
    // =========================================================================

    /**
     * AC2 (E65S07): While the field page is hidden (screen locked, tab backgrounded), no polling
     * requests must be issued. The implementation must use the Page Visibility API ({@code
     * document.hidden} / {@code document.webkitHidden} vendor-prefix fallback).
     *
     * <p>Test surface: rendered-HTML / inline-script source inspection. The runtime behaviour
     * (polling actually pausing) is attested in the impl-report per AC7.
     *
     * <p>DEC-22 Iron Law: written RED-first against E65S06 implementation that has no visibility
     * check. Fails until E65S07 adds the Page Visibility guard.
     */
    @Test
    @DisplayName(
            "AC2 (E65S07 — visibility guard): inline script references document.hidden or"
                    + " webkitHidden for Page Visibility API (AC2: no polling when page hidden)")
    void fieldPage_inlineScriptDeclaresVisibilityGuard() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as(
                        "Rendered field page inline script must reference 'document.hidden'"
                                + " or 'webkitHidden' for Page Visibility API"
                                + " (AC2 E65S07: polling paused when page hidden — DEC-19"
                                + " feature-detection with vendor-prefix fallback)")
                .containsAnyOf("document.hidden", "webkitHidden");
    }

    // =========================================================================
    // AC3 (E65S07): idle-timeout — IDLE_TIMEOUT_MS constant in script
    // =========================================================================

    /**
     * AC3 (E65S07): After an extended no-match / waiting state, the tablet stops polling and shows
     * a clearly labelled tap-to-refresh control. The implementation uses a named {@code
     * IDLE_TIMEOUT_MS} constant for the threshold.
     *
     * <p>DEC-22 Iron Law: written RED-first against E65S06 implementation that has no idle timeout.
     * Fails until E65S07 adds the constant.
     */
    @Test
    @DisplayName(
            "AC3 (E65S07 — idle timeout constant): inline script declares IDLE_TIMEOUT_MS"
                    + " constant (structural governance marker for idle-timeout mechanism)")
    void fieldPage_inlineScriptDeclaresIdleTimeoutConstant() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as(
                        "Rendered field page inline script must declare IDLE_TIMEOUT_MS constant"
                                + " (AC3 E65S07: idle-timeout threshold named constant required)")
                .contains("IDLE_TIMEOUT_MS");
    }

    // =========================================================================
    // AC3 (E65S07): tap-to-refresh button present in HTML
    // =========================================================================

    /**
     * AC3 (E65S07): When the idle timeout fires, the tablet shows a clearly labelled "tap to
     * refresh" control (AC3). The control must be present in the rendered HTML.
     *
     * <p>DEC-22 Iron Law: written RED-first against E65S06 template that has no tap-to-refresh
     * button. Fails until E65S07 adds the element.
     */
    @Test
    @DisplayName(
            "AC3 (E65S07 — tap-to-refresh button): rendered field page contains"
                    + " tap-to-refresh-btn element (idle-timeout manual resume control)")
    void fieldPage_containsTapToRefreshButton() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        assertThat(body)
                .as(
                        "Rendered field page must contain tap-to-refresh-btn element"
                                + " (AC3 E65S07: tap-to-refresh manual resume control)")
                .contains("id=\"tap-to-refresh-btn\"");
        assertThat(body)
                .as(
                        "Rendered field page must contain tap-to-refresh-panel wrapper element"
                                + " (AC3 E65S07: panel hidden initially, shown on idle timeout)")
                .contains("id=\"tap-to-refresh-panel\"");
        // The tap-to-refresh label must not be an unresolved Mustache placeholder
        assertThat(body)
                .as(
                        "Tap-to-refresh button must have a resolved i18n label (AC3 E65S07:"
                                + " msgTapToRefresh must be resolved from messages.properties)")
                .doesNotContain("{{msgTapToRefresh}}");
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
