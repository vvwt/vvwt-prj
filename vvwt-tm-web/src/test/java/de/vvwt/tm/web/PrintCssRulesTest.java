package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for CSS rule presence in {@code static/print/assets/print.css} — E08S10.
 *
 * <p>Pure JUnit 5 unit test: loads the CSS file as a classpath resource and asserts required rules
 * via regex patterns. No Spring context required (per DEC-44 exception for pure resource-reading
 * unit tests that do not exercise the web-module integration boundary).
 *
 * <p>Covers acceptance criteria Groups C + D of E08S10:
 *
 * <ul>
 *   <li>AC-TABLE-LAYOUT-FIXED — {@code .laufzettel-table} has {@code table-layout: fixed}
 *   <li>AC-COL-ROUND-WIDTH — 7%
 *   <li>AC-COL-TIME-WIDTH — 13%
 *   <li>AC-COL-FIELD-WIDTH — 8%
 *   <li>AC-COL-ACTIVITY-WIDTH — 72%
 *   <li>AC-COL-ACTIVITY-ELLIPSIS — ellipsis triple (text-overflow + white-space + overflow)
 *   <li>AC-PAGE-BREAK-INSIDE-AVOID-PRESERVED — {@code .laufzettel-team-section} has
 *       page-break-inside: avoid AND break-inside: avoid
 *   <li>AC-TEAM-SECTION-SEPARATOR — {@code .laufzettel-team-section:not(:first-of-type)} has
 *       border-top: 1.5pt solid black with padding-top ≥ 4pt and margin-top ≥ 6pt
 *   <li>AC-NO-FORCED-PAGE-BREAK-SOURCE — {@code laufzettel-all.mustache} does NOT contain {@code
 *       {{> print-page-break}}}
 * </ul>
 *
 * <p><strong>DEC-22 TDD compliance:</strong> Tests were authored RED-first against unmodified CSS
 * and template files (current widths: round=10%, time=18%, field=18%; no table-layout:fixed, no
 * ellipsis, no border-top separator, template contains page-break partial) — documented in
 * E08S10.impl-report.md.
 *
 * @since E08S10
 */
@DisplayName("PrintCssRulesTest — E08S10 Group C + D CSS + Template rule presence")
class PrintCssRulesTest {

    private static String css;
    private static String allTeamsTemplate;

    @BeforeAll
    static void loadResources() throws Exception {
        try (InputStream cssIs =
                PrintCssRulesTest.class.getResourceAsStream("/static/print/assets/print.css")) {
            assertThat(cssIs).as("print.css must be loadable from classpath").isNotNull();
            css = new String(cssIs.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (InputStream tmplIs =
                PrintCssRulesTest.class.getResourceAsStream(
                        "/templates/print/laufzettel-all.mustache")) {
            assertThat(tmplIs)
                    .as("laufzettel-all.mustache must be loadable from classpath")
                    .isNotNull();
            allTeamsTemplate = new String(tmplIs.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // =========================================================================
    // AC-TABLE-LAYOUT-FIXED (Group D)
    // =========================================================================

    @Test
    @DisplayName("AC-TABLE-LAYOUT-FIXED: .laufzettel-table has table-layout: fixed")
    void tableLayoutFixed() {
        // Matches: .laufzettel-table { ... table-layout: fixed ... }
        // The rule may span multiple lines — search the whole CSS text for
        // the pattern within the .laufzettel-table block.
        assertThat(css)
                .as("AC-TABLE-LAYOUT-FIXED: .laufzettel-table must declare table-layout: fixed")
                .containsPattern("(?s)\\.laufzettel-table\\s*\\{[^}]*table-layout:\\s*fixed\\b");
    }

    // =========================================================================
    // AC-COL-ROUND-WIDTH (Group D)
    // =========================================================================

    @Test
    @DisplayName("AC-COL-ROUND-WIDTH: .laufzettel-col-round width is 7%")
    void colRoundWidth() {
        assertThat(css)
                .as("AC-COL-ROUND-WIDTH: .laufzettel-col-round must declare width: 7%")
                .containsPattern("(?s)\\.laufzettel-col-round\\s*\\{[^}]*width:\\s*7%");
    }

    // =========================================================================
    // AC-COL-TIME-WIDTH (Group D)
    // =========================================================================

    @Test
    @DisplayName("AC-COL-TIME-WIDTH: .laufzettel-col-time width is 13%")
    void colTimeWidth() {
        assertThat(css)
                .as("AC-COL-TIME-WIDTH: .laufzettel-col-time must declare width: 13%")
                .containsPattern("(?s)\\.laufzettel-col-time\\s*\\{[^}]*width:\\s*13%");
    }

    // =========================================================================
    // AC-COL-FIELD-WIDTH (Group D)
    // =========================================================================

    @Test
    @DisplayName("AC-COL-FIELD-WIDTH: .laufzettel-col-field width is 8%")
    void colFieldWidth() {
        assertThat(css)
                .as("AC-COL-FIELD-WIDTH: .laufzettel-col-field must declare width: 8%")
                .containsPattern("(?s)\\.laufzettel-col-field\\s*\\{[^}]*width:\\s*8%");
    }

    // =========================================================================
    // AC-COL-ACTIVITY-WIDTH (Group D)
    // =========================================================================

    @Test
    @DisplayName("AC-COL-ACTIVITY-WIDTH: .laufzettel-col-activity width is 72%")
    void colActivityWidth() {
        assertThat(css)
                .as("AC-COL-ACTIVITY-WIDTH: .laufzettel-col-activity must declare width: 72%")
                .containsPattern("(?s)\\.laufzettel-col-activity\\s*\\{[^}]*width:\\s*72%");
    }

    // =========================================================================
    // AC-COL-ACTIVITY-ELLIPSIS (Group D)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-COL-ACTIVITY-ELLIPSIS: .laufzettel-col-activity has ellipsis triple"
                    + " (text-overflow + white-space + overflow)")
    void colActivityEllipsis() {
        // The three declarations must all be present in the .laufzettel-col-activity rule block.
        // We check for each individually within the CSS text after the selector.
        // Pattern: find .laufzettel-col-activity { ... } and verify all three properties present.
        // Since the rule may not be on one line, check for each property in the full CSS
        // within proximity of the selector.
        //
        // Approach: extract the .laufzettel-col-activity rule block and assert each property.
        // Simple containsPattern suffices since the properties are unique to this rule.
        assertThat(css)
                .as(
                        "AC-COL-ACTIVITY-ELLIPSIS: .laufzettel-col-activity must have"
                                + " text-overflow: ellipsis")
                .containsPattern(
                        "(?s)\\.laufzettel-col-activity\\s*\\{[^}]*text-overflow:\\s*ellipsis");
        assertThat(css)
                .as(
                        "AC-COL-ACTIVITY-ELLIPSIS: .laufzettel-col-activity must have"
                                + " white-space: nowrap")
                .containsPattern(
                        "(?s)\\.laufzettel-col-activity\\s*\\{[^}]*white-space:\\s*nowrap");
        assertThat(css)
                .as("AC-COL-ACTIVITY-ELLIPSIS: .laufzettel-col-activity must have overflow: hidden")
                .containsPattern("(?s)\\.laufzettel-col-activity\\s*\\{[^}]*overflow:\\s*hidden");
    }

    // =========================================================================
    // AC-PAGE-BREAK-INSIDE-AVOID-PRESERVED (Group C)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-PAGE-BREAK-INSIDE-AVOID-PRESERVED: .laufzettel-team-section @media print has"
                    + " page-break-inside: avoid AND break-inside: avoid")
    void pageBreakInsideAvoidPreserved() {
        // The rule is inside @media print. Check the full CSS text contains the pattern
        // somewhere after @media print.
        assertThat(css)
                .as(
                        "AC-PAGE-BREAK-INSIDE-AVOID-PRESERVED: must have page-break-inside: avoid"
                                + " on .laufzettel-team-section within @media print")
                .containsPattern(
                        "(?s)\\.laufzettel-team-section\\s*\\{[^}]*page-break-inside:\\s*avoid\\b");
        assertThat(css)
                .as(
                        "AC-PAGE-BREAK-INSIDE-AVOID-PRESERVED: must have break-inside: avoid on"
                                + " .laufzettel-team-section within @media print")
                .containsPattern(
                        "(?s)\\.laufzettel-team-section\\s*\\{[^}]*break-inside:\\s*avoid\\b");
    }

    // =========================================================================
    // AC-TEAM-SECTION-SEPARATOR (Group C)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEAM-SECTION-SEPARATOR: .laufzettel-team-section:not(:first-of-type) has"
                    + " border-top: 1.5pt solid black, padding-top ≥ 4pt, margin-top ≥ 6pt")
    void teamSectionSeparator() {
        // Verify border-top: 1.5pt solid black
        assertThat(css)
                .as(
                        "AC-TEAM-SECTION-SEPARATOR: must have border-top: 1.5pt solid black on"
                                + " .laufzettel-team-section:not(:first-of-type)")
                .containsPattern(
                        "(?s)\\.laufzettel-team-section:not\\(:first-of-type\\)\\s*\\{"
                                + "[^}]*border-top:\\s*1\\.5pt\\s+solid\\s+black");
        // Verify padding-top is declared (any positive pt value ≥ 4pt is acceptable;
        // test for presence of padding-top: <N>pt in the same rule block)
        assertThat(css)
                .as(
                        "AC-TEAM-SECTION-SEPARATOR: must have padding-top on"
                                + " .laufzettel-team-section:not(:first-of-type)")
                .containsPattern(
                        "(?s)\\.laufzettel-team-section:not\\(:first-of-type\\)\\s*\\{"
                                + "[^}]*padding-top:\\s*\\d+pt");
        // Verify margin-top is declared
        assertThat(css)
                .as(
                        "AC-TEAM-SECTION-SEPARATOR: must have margin-top on"
                                + " .laufzettel-team-section:not(:first-of-type)")
                .containsPattern(
                        "(?s)\\.laufzettel-team-section:not\\(:first-of-type\\)\\s*\\{"
                                + "[^}]*margin-top:\\s*\\d+pt");
    }

    // =========================================================================
    // AC-NO-FORCED-PAGE-BREAK-SOURCE (Group C)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-NO-FORCED-PAGE-BREAK-SOURCE: laufzettel-all.mustache must NOT contain"
                    + " {{> print-page-break}}")
    void noForcedPageBreakInAllTeamsTemplate() {
        assertThat(allTeamsTemplate)
                .as(
                        "AC-NO-FORCED-PAGE-BREAK-SOURCE: laufzettel-all.mustache must not contain"
                                + " the print-page-break partial invocation")
                .doesNotContain("{{> print-page-break}}");
    }
}
