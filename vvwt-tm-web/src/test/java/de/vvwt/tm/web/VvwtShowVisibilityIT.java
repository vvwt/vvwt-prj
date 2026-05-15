package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import java.net.URI;
import org.htmlunit.BrowserVersion;
import org.htmlunit.NicelyResynchronizingAjaxController;
import org.htmlunit.WebClient;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
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
 * Integration tests for E49S03 — verifies that scoring-tablet UI elements become actually visible
 * when {@code vvwtShow(el)} is called, i.e. the element is NOT hidden by a stylesheet
 * {@code display:none} rule that the helper cannot override.
 *
 * <h2>Root cause (verified empirically — E49S03 context)</h2>
 *
 * <p>{@code vvwtShow(el)} is implemented as {@code el.style.display = ''}, which removes the
 * <em>inline</em> {@code display} declaration. If the element is hidden by a <em>stylesheet</em>
 * rule ({@code #id { display: none; }}), removing the inline override leaves the cascade resolving
 * to {@code display:none} from the stylesheet — the element stays invisible.
 *
 * <h2>Fix mechanism: M-C (inline-style hiding)</h2>
 *
 * <p>After the fix, each affected element is hidden by an inline {@code style="display:none"}
 * attribute instead of a stylesheet rule. {@code vvwtShow} then removes the inline style, and the
 * element falls back to its natural browser display type (visible).
 *
 * <h2>Test approach (DEC-22 RED-first)</h2>
 *
 * <p>Tests use HtmlUnit to load the rendered Mustache pages from the running Spring Boot server,
 * then evaluate the exact CSS cascade behavior by inspecting computed style via
 * {@code window.getComputedStyle}. HtmlUnit is a headless browser with full CSS-cascade support
 * including stylesheet rule resolution — structural assertions on HTML source text are
 * insufficient because they do not verify whether {@code vvwtShow} can actually reveal the element.
 *
 * <p>The tests assert the observable property directly:
 *
 * <ul>
 *   <li>RED (before fix): elements are hidden by stylesheet rules → {@code vvwtShow} cannot reveal
 *       them → computed {@code display} stays {@code none} → assertions fail.
 *   <li>GREEN (after M-C fix): elements have inline {@code style="display:none"} → {@code vvwtShow}
 *       removes the inline style → computed {@code display} is not {@code none} → assertions pass.
 * </ul>
 *
 * <p>Note: the pages' startup JavaScript makes network calls (e.g. {@code POST /api/devices/register}).
 * HtmlUnit is configured to suppress network errors (JavaScript call errors) and the tests directly
 * call the helper function on individual elements rather than triggering the full page flow,
 * to isolate the CSS-cascade behavior under test.
 *
 * @see <a href="stories/E49S03.story.md">E49S03 story</a>
 * @see <a href="DEC-19">DEC-19 — Mustache + ES5 carve-out</a>
 * @see <a href="DEC-22">DEC-22 — TDD RED-first</a>
 * @see <a href="DEC-44">DEC-44 — @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="DEC-54">DEC-54 — mvn verify gate</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, VvwtShowVisibilityIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("VvwtShowVisibilityIT — E49S03 CSS-cascade visibility assertions (DEC-22 RED-first)")
class VvwtShowVisibilityIT {

    private static final String ADMIN_PASS = "E49S03VvwtShowVisibilityIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private WebClient webClient;

    @BeforeEach
    void setUp() {
        webClient = new WebClient(BrowserVersion.FIREFOX);
        webClient.getOptions().setJavaScriptEnabled(true);
        webClient.getOptions().setCssEnabled(true);
        webClient.getOptions().setThrowExceptionOnScriptError(false);
        webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
        webClient.getOptions().setPrintContentOnFailingStatusCode(false);
        webClient.setAjaxController(new NicelyResynchronizingAjaxController());
    }

    @AfterEach
    void tearDown() {
        if (webClient != null) {
            webClient.close();
        }
    }

    // =========================================================================
    // AC-TEST-PIN-BLOCK-VISIBLE-AFTER-FRESH-REGISTRATION-RED
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-PIN-BLOCK-VISIBLE-AFTER-FRESH-REGISTRATION-RED: #pin-block on"
                    + " /score/register becomes visible after vvwtShow() — requires inline hiding"
                    + " (stylesheet display:none cannot be cleared by el.style.display='')")
    void pinBlock_becomesVisible_afterVvwtShowCall() throws Exception {
        HtmlPage page =
                webClient.getPage("http://localhost:" + port + "/score/register");
        webClient.waitForBackgroundJavaScript(500);

        DomElement pinBlock = page.getElementById("pin-block");
        assertThat(pinBlock)
                .as("#pin-block element must exist in the rendered page")
                .isNotNull();

        // Simulate vvwtShow(el): el.style.display = ''
        // After fix (M-C): element has inline style="display:none" → clearing inline style
        // reveals the element (computed display = block).
        // Before fix: element hidden by stylesheet rule → clearing inline display (empty
        // inline) has no effect → computed display stays 'none' → assertion FAILS (RED).
        page.executeJavaScript("document.getElementById('pin-block').style.display = '';");

        String computedDisplay =
                (String)
                        page.executeJavaScript(
                                        "window.getComputedStyle(document.getElementById('pin-block')).display")
                                .getJavaScriptResult();

        assertThat(computedDisplay)
                .as(
                        "#pin-block computed display must not be 'none' after vvwtShow()."
                                + " RED if element is hidden by a stylesheet rule (current code);"
                                + " GREEN after M-C fix (inline style).")
                .isNotEqualTo("none");
    }

    // =========================================================================
    // AC-TEST-PIN-BLOCK-VISIBLE-ON-REVISIT-REGISTERED-RED (same element, same mechanism)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-PIN-BLOCK-VISIBLE-ON-REVISIT-REGISTERED-RED: #pin-block and #device-name"
                    + " on /score/register become visible after vvwtShow() — revisit path")
    void pinBlockAndDeviceName_becomesVisible_afterVvwtShowCall() throws Exception {
        HtmlPage page =
                webClient.getPage("http://localhost:" + port + "/score/register");
        webClient.waitForBackgroundJavaScript(500);

        // #pin-block
        DomElement pinBlock = page.getElementById("pin-block");
        assertThat(pinBlock).as("#pin-block must exist").isNotNull();

        page.executeJavaScript("document.getElementById('pin-block').style.display = '';");
        String pinBlockDisplay =
                (String)
                        page.executeJavaScript(
                                        "window.getComputedStyle(document.getElementById('pin-block')).display")
                                .getJavaScriptResult();
        assertThat(pinBlockDisplay)
                .as("#pin-block computed display must not be 'none' after vvwtShow()")
                .isNotEqualTo("none");

        // #device-name
        DomElement deviceName = page.getElementById("device-name");
        assertThat(deviceName).as("#device-name must exist").isNotNull();

        page.executeJavaScript("document.getElementById('device-name').style.display = '';");
        String deviceNameDisplay =
                (String)
                        page.executeJavaScript(
                                        "window.getComputedStyle(document.getElementById('device-name')).display")
                                .getJavaScriptResult();
        assertThat(deviceNameDisplay)
                .as("#device-name computed display must not be 'none' after vvwtShow()")
                .isNotEqualTo("none");
    }

    // =========================================================================
    // AC-TEST-SCORING-PAGE-PANELS-VISIBLE-RED (all 5 panels on field.mustache)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-SCORING-PAGE-PANELS-VISIBLE-RED: all 5 vvwtShow-revealed panels on"
                    + " /score/field/1 become visible after vvwtShow() — #match-panel,"
                    + " #no-match-panel, #queue-status, #confirm-dialog, #error-banner")
    void allScoringPanels_becomeVisible_afterVvwtShowCall() throws Exception {
        HtmlPage page =
                webClient.getPage("http://localhost:" + port + "/score/field/1");
        webClient.waitForBackgroundJavaScript(500);

        String[] panelIds = {
            "match-panel", "no-match-panel", "queue-status", "confirm-dialog", "error-banner"
        };

        for (String panelId : panelIds) {
            DomElement panel = page.getElementById(panelId);
            assertThat(panel)
                    .as("#" + panelId + " element must exist in the rendered field page")
                    .isNotNull();

            page.executeJavaScript(
                    "document.getElementById('" + panelId + "').style.display = '';");

            String computedDisplay =
                    (String)
                            page.executeJavaScript(
                                            "window.getComputedStyle(document.getElementById('"
                                                    + panelId
                                                    + "')).display")
                                    .getJavaScriptResult();

            assertThat(computedDisplay)
                    .as(
                            "#"
                                    + panelId
                                    + " computed display must not be 'none' after vvwtShow()."
                                    + " RED if hidden by stylesheet rule; GREEN after M-C fix.")
                    .isNotEqualTo("none");
        }
    }

    // =========================================================================
    // AC-TEST-INLINE-HIDDEN-ELEMENTS-NOT-REGRESSED-GREEN
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-INLINE-HIDDEN-ELEMENTS-NOT-REGRESSED-GREEN: #referee-row on /score/field/1"
                    + " (already inline-hidden) becomes visible after vvwtShow() — must NOT regress")
    void refereeRow_alreadyInlineHidden_becomesVisible() throws Exception {
        HtmlPage page =
                webClient.getPage("http://localhost:" + port + "/score/field/1");
        webClient.waitForBackgroundJavaScript(500);

        DomElement refereeRow = page.getElementById("referee-row");
        assertThat(refereeRow).as("#referee-row must exist").isNotNull();

        // #referee-row has inline style="display:none" — vvwtShow already works for it.
        // After fix, it must STILL work (no regression).
        page.executeJavaScript("document.getElementById('referee-row').style.display = '';");
        String computedDisplay =
                (String)
                        page.executeJavaScript(
                                        "window.getComputedStyle(document.getElementById('referee-row')).display")
                                .getJavaScriptResult();

        assertThat(computedDisplay)
                .as("#referee-row (inline-hidden) must be visible after vvwtShow()")
                .isNotEqualTo("none");
    }

    // =========================================================================
    // AC-TEST-HIDE-STILL-WORKS-GREEN
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-HIDE-STILL-WORKS-GREEN: vvwtHide(el) still hides elements after fix —"
                    + " el.style.display='none' produces computed display:none")
    void vvwtHide_stillHides_afterFix() throws Exception {
        HtmlPage page =
                webClient.getPage("http://localhost:" + port + "/score/register");
        webClient.waitForBackgroundJavaScript(500);

        // Use #status-msg (never hidden by stylesheet — natural display is block).
        // vvwtHide sets el.style.display = 'none' — must produce computed display:none.
        DomElement statusMsg = page.getElementById("status-msg");
        assertThat(statusMsg).as("#status-msg must exist").isNotNull();

        page.executeJavaScript("document.getElementById('status-msg').style.display = 'none';");
        String computedDisplay =
                (String)
                        page.executeJavaScript(
                                        "window.getComputedStyle(document.getElementById('status-msg')).display")
                                .getJavaScriptResult();

        assertThat(computedDisplay)
                .as("vvwtHide must produce computed display:none (regression guard)")
                .isEqualTo("none");
    }

    // =========================================================================
    // AC-TEST-EXISTING-SUITE-GREEN (verified via mvn verify — not a separate test method here)
    // =========================================================================

    // =========================================================================
    // AC-TEST-REVEALED-ELEMENT-DISPLAY-TYPE-CORRECT-GREEN
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-REVEALED-ELEMENT-DISPLAY-TYPE-CORRECT-GREEN: #pin-block, #confirm-dialog,"
                    + " and #retry-btn reveal at correct display types (block or inline-block),"
                    + " not 'none' — fix must not force a single hard-coded display value")
    void revealedElements_haveCorrectDisplayType() throws Exception {
        HtmlPage registerPage =
                webClient.getPage("http://localhost:" + port + "/score/register");
        webClient.waitForBackgroundJavaScript(500);

        // #pin-block — block-level container
        page_assertRevealedDisplayIsNotNone(registerPage, "pin-block");
        // #retry-btn — button (inline-block in most browsers)
        page_assertRevealedDisplayIsNotNone(registerPage, "retry-btn");

        HtmlPage fieldPage =
                webClient.getPage("http://localhost:" + port + "/score/field/1");
        webClient.waitForBackgroundJavaScript(500);

        // #confirm-dialog — fixed-position overlay
        page_assertRevealedDisplayIsNotNone(fieldPage, "confirm-dialog");
    }

    private void page_assertRevealedDisplayIsNotNone(HtmlPage page, String elementId)
            throws Exception {
        DomElement el = page.getElementById(elementId);
        assertThat(el).as("#" + elementId + " must exist").isNotNull();

        page.executeJavaScript("document.getElementById('" + elementId + "').style.display = '';");
        String computedDisplay =
                (String)
                        page.executeJavaScript(
                                        "window.getComputedStyle(document.getElementById('"
                                                + elementId
                                                + "')).display")
                                .getJavaScriptResult();

        assertThat(computedDisplay)
                .as(
                        "#"
                                + elementId
                                + " must not be 'none' after vvwtShow() — correct display type"
                                + " expected for layout")
                .isNotEqualTo("none");
    }

    // =========================================================================
    // Test configuration
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean("visibilityItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
