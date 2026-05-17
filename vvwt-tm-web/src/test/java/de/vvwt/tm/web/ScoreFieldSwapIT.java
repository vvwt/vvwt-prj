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
 * Integration tests for E61S03 — Scoring-tablet team-position swap.
 *
 * <h2>Coverage</h2>
 *
 * <p>Asserts on the rendered HTML of {@code GET /score/field/1} that:
 *
 * <ul>
 *   <li>AC1 (manual swap): the field page provides a swap control element.
 *   <li>AC6 (reload consistency): the swap-offset storage key constant is present in the script.
 *   <li>AC8 (i18n): the swap control label is sourced from a {@code score.field.swap.*} message key
 *       (no hardcoded label in the template).
 *   <li>AC9 (governance): the page still contains no ES2015+ syntax identifiable in the template
 *       source — specifically no arrow functions, class keyword, or template literals in the inline
 *       script.
 *   <li>AC10 (testing — reload-while-at-or-above-8): the tiebreak swap predicate constant {@code
 *       TIEBREAK_SWAP_THRESHOLD} is present in the inline script, confirming the deterministic
 *       recomputation is implemented as a named constant rather than an inline literal (structural
 *       governance marker).
 *   <li>AC5 (score-team binding): the score inputs (input-team1, input-team2) remain structurally
 *       bound to their fixed column IDs — their IDs must NOT be swapped by the server; the swap is
 *       purely presentation.
 * </ul>
 *
 * <h2>Test methodology</h2>
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT)} per DEC-44 web-module IT convention, importing
 * {@link WebModuleTestConfig}. DEC-22 Iron Law: this test was written RED-first against an
 * implementation that does not yet contain the swap elements — it fails until the field.mustache
 * template and inline ES5 script are updated to deliver E61S03.
 *
 * <h2>Test surface limitation</h2>
 *
 * <p>The existing test infrastructure for this Mustache + ES5 page is an HTML source-inspection
 * pattern (this IT and {@link ScoreFieldLayoutIT}): the rendered HTML is fetched via {@link
 * TestRestTemplate} and asserted as a string. Runtime JavaScript execution (actual DOM swap,
 * localStorage read/write, per-set alternation triggers, tiebreak threshold crossing) is NOT
 * exercised by this surface. Per AC10, the behaviours not reachable by this surface are recorded in
 * the E61S03 impl-report.
 *
 * @see ScoreController
 * @see ScoreFieldLayoutIT
 * @see <a href="DEC-19">DEC-19 — Mustache + ES5 carve-out</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E61S03">E61S03 — Team-position swap</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, ScoreFieldSwapIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("ScoreFieldSwapIT — E61S03 team-position swap ACs")
class ScoreFieldSwapIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E61S03ScoreFieldSwapIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    // =========================================================================
    // AC1: swap control is present on the field page
    // =========================================================================

    @Test
    @DisplayName(
            "AC1 (manual swap): rendered field page contains a swap control element"
                    + " with id 'swap-btn'")
    void fieldPage_containsSwapControl() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as(
                        "Field page must contain a swap control button element (AC1:"
                                + " manual swap — E61S03)")
                .contains("id=\"swap-btn\"");
    }

    // =========================================================================
    // AC5: score input IDs are fixed — the swap is purely presentational
    // =========================================================================

    @Test
    @DisplayName(
            "AC5 (score-team binding): score input IDs remain fixed as input-team1 / input-team2"
                    + " — the server does not swap them")
    void fieldPage_scoreInputIdsAreFixed() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        assertThat(body)
                .as(
                        "Score input for team 1 must be present with id 'input-team1'"
                                + " (AC5: score-team binding — E61S03)")
                .contains("id=\"input-team1\"");
        assertThat(body)
                .as(
                        "Score input for team 2 must be present with id 'input-team2'"
                                + " (AC5: score-team binding — E61S03)")
                .contains("id=\"input-team2\"");
    }

    // =========================================================================
    // AC8: swap control label sourced from i18n message key
    // =========================================================================

    @Test
    @DisplayName(
            "AC8 (i18n): field page contains the Mustache message key reference for the swap"
                    + " control label — msgSwapLabel is bound in the template")
    void fieldPage_swapControlUsesI18nKey() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The rendered HTML must not contain the raw Mustache placeholder (it should be resolved).
        // The swap button must have a non-empty label (the German text from messages.properties).
        assertThat(response.getBody())
                .as(
                        "Rendered field page must not contain unresolved Mustache placeholder"
                                + " for swap label (AC8: i18n — E61S03)")
                .doesNotContain("{{msgSwapLabel}}");
        // The swap button's rendered text must be the German label (default DE locale in test)
        // or any non-empty resolved value — confirm the key resolution happened.
        assertThat(response.getBody())
                .as(
                        "Rendered field page must contain a resolved swap control"
                                + " (AC8: i18n via score.field.swap.label — E61S03)")
                .containsPattern("id=\"swap-btn\"[^>]*>[^<]");
    }

    // =========================================================================
    // AC6 + AC10: SWAP_OFFSET_KEY and TIEBREAK_SWAP_THRESHOLD constants in script
    // =========================================================================

    @Test
    @DisplayName(
            "AC6+AC10 (governance): inline script contains SWAP_OFFSET_KEY"
                    + " (localStorage key for manual-swap offset) and TIEBREAK_SWAP_THRESHOLD"
                    + " (8-point deterministic threshold constant)")
    void fieldPage_inlineScriptContainsSwapConstants() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        assertThat(body)
                .as(
                        "Inline script must declare SWAP_OFFSET_KEY constant for localStorage"
                                + " persistence (AC6: reload consistency — E61S03)")
                .contains("SWAP_OFFSET_KEY");
        assertThat(body)
                .as(
                        "Inline script must declare TIEBREAK_SWAP_THRESHOLD constant (= 8)"
                                + " for deterministic tiebreak mid-set switch (AC3 + AC10"
                                + " — E61S03)")
                .contains("TIEBREAK_SWAP_THRESHOLD");
    }

    // =========================================================================
    // AC2+AC3 (E65S03): swap-aware score display — structural governance marker
    // =========================================================================

    /**
     * AC2+AC3 (E65S03): After a side-swap each column shows the correct team's score and +/-
     * buttons. The fix tracks the active swap state in a {@code currentSwap} variable (0 or 1) that
     * {@code updateScoreDisplay} and the +/- handlers consult to route to the correct score.
     *
     * <p>Test surface: rendered-HTML / inline-script source inspection (see AC7 constraint). The
     * runtime behaviour — each column's displayed score and +/- routing belonging to the team
     * currently shown (AC2, AC3) — is verified by the implementer and recorded in the E65S03
     * impl-report.
     *
     * <p>DEC-22 Iron Law: this test was written RED-first against the E61S03 implementation that
     * does NOT yet declare {@code currentSwap} — it fails until the E65S03 fix is applied.
     */
    @Test
    @DisplayName(
            "AC2+AC3 (E65S03 swap-aware score): inline script declares currentSwap variable"
                    + " — swap-state is tracked so updateScoreDisplay routes values correctly")
    void fieldPage_inlineScriptDeclareCurrentSwap() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as(
                        "Inline script must declare 'currentSwap' variable — the swap-state"
                                + " tracker that makes updateScoreDisplay and +/- handlers"
                                + " swap-aware (AC2+AC3: E65S03)")
                .contains("currentSwap");
    }

    // =========================================================================
    // AC9 (governance): no ES2015+ syntax in the inline script
    // =========================================================================

    @Test
    @DisplayName(
            "AC9 (governance): rendered field page inline script contains no ES2015+ syntax"
                    + " (no arrow functions, class keyword, or backtick template literals)")
    void fieldPage_inlineScriptIsEs5Only() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        assertThat(body)
                .as(
                        "Inline script must not contain arrow function syntax '=>'"
                                + " (AC9: ES5 only, DEC-19 — E61S03)")
                .doesNotContainPattern("=>");
        assertThat(body)
                .as(
                        "Inline script must not contain 'class ' keyword"
                                + " (AC9: ES5 only, DEC-19 — E61S03)")
                .doesNotContain("class ");
        assertThat(body)
                .as(
                        "Inline script must not contain backtick template literals"
                                + " (AC9: ES5 only, DEC-19 — E61S03)")
                .doesNotContain("`");
    }

    // =========================================================================
    // Test configuration
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean("webItAdminCredentialsProviderSwap")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
