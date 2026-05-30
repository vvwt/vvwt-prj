// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first guard test for the {@code @media print} block in {@code
 * certificate/_certificate_styles.mustache} (E12S11 AC2).
 *
 * <p>This test was committed RED — before the CSS fix — as required by the DEC-22 Iron Law. The
 * pre-story {@code @media print} block (lines 146–151) contains only {@code body}, {@code .stage},
 * {@code .page} box-shadow/border-radius overrides, and {@code @page size}: it lacks the
 * fixed-dimension {@code .page} dimensions, the print-safe {@code body} override, and the {@code
 * .page:last-child} page-break guard that this test asserts. Therefore this test FAILS RED against
 * the unmodified template.
 *
 * <p>After the fix lands (adding {@code width: 297mm; height: 210mm; aspect-ratio: auto; padding:
 * 12mm 18mm 10mm} to the {@code .page} print rule, overriding {@code body} to {@code display:
 * block; min-height: 0}, and adding {@code .page:last-child { page-break-after: auto }}), this test
 * turns GREEN.
 *
 * <h2>AC2 assertions (DEC-22 RED-first, E12S11)</h2>
 *
 * <ul>
 *   <li>(a) {@code .page} rule in {@code @media print} contains {@code width} AND {@code height}
 *       AND {@code aspect-ratio: auto} AND {@code padding} set in fixed-dimension non-container-
 *       query units ({@code mm}, {@code cm}, {@code in}, {@code vh}, or {@code vw} — NOT {@code
 *       cqh}, {@code cqw}, {@code cqi}, {@code cqb}).
 *   <li>(b) {@code body} rule in {@code @media print} overrides the screen-mode {@code display:
 *       flex} / {@code min-height: 100vh} to print-safe values ({@code display: block} / {@code
 *       min-height: 0} or equivalent).
 *   <li>(c) A {@code .page:last-child} declaration in {@code @media print} sets {@code
 *       page-break-after: auto}.
 * </ul>
 *
 * <p>No Spring context is loaded — this is a plain JUnit 5 unit test that reads the Mustache
 * template as a classpath resource and inspects the rendered {@code <style>} text. The test is
 * package-local to {@code de.vvwt.tm.web.certificate} (same package as the render controller),
 * consistent with DEC-36 (no cross-package reference to implementation classes).
 *
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first, reconstruction-in-place)</a>
 * @see <a href="DEC-54">DEC-54 — mvn verify canonical build gate</a>
 * @see <a href="DEC-75">DEC-75 §D6 — SPDX AGPL-3.0-or-later header</a>
 * @since E12S11
 */
@DisplayName(
        "E12S11 AC2 — @media print guard: fixed-dim .page + print-safe body + :last-child break")
class CertificatePrintStylesGuardTest {

    private static final String TEMPLATE_CLASSPATH =
            "/templates/certificate/_certificate_styles.mustache";

    /**
     * Reads the Mustache template from classpath and extracts the {@code @media print { ... }}
     * block content.
     */
    private String readMediaPrintBlock() throws IOException {
        try (InputStream is =
                CertificatePrintStylesGuardTest.class.getResourceAsStream(TEMPLATE_CLASSPATH)) {
            assertThat(is)
                    .as("Template must be on classpath at %s", TEMPLATE_CLASSPATH)
                    .isNotNull();
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            // Extract the @media print { ... } block (non-greedy, single-depth braces)
            // The block ends at the matching closing brace of @media print {
            int start = content.indexOf("@media print {");
            assertThat(start).as("@media print block must exist in the template").isGreaterThan(0);

            // Find the matching closing brace by counting nesting depth
            int depth = 0;
            int end = -1;
            for (int i = start; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = i + 1;
                        break;
                    }
                }
            }
            assertThat(end)
                    .as("@media print block must have a matching closing brace")
                    .isGreaterThan(start);
            return content.substring(start, end);
        }
    }

    // =========================================================================
    // AC2 (a) — .page rule: width + height + aspect-ratio: auto + fixed-dim padding
    // =========================================================================

    @Test
    @DisplayName(
            "AC2(a): @media print .page rule contains width, height, aspect-ratio: auto,"
                    + " and padding in fixed-dimension non-cq units")
    void mediaPrint_pageRule_hasFixedDimensions() throws IOException {
        String block = readMediaPrintBlock();

        // Extract the .page { ... } rule within @media print
        // We look for .page { ... } (without :last-child qualifier)
        String pageRuleContent = extractRuleContent(block, "\\.page\\s*\\{");
        assertThat(pageRuleContent).as("@media print .page rule content").isNotNull();

        // (a1) width in fixed-dimension units (mm, cm, in, vh, vw)
        assertThat(pageRuleContent)
                .as(
                        "@media print .page must declare width in fixed-dimension units"
                                + " (mm/cm/in/vh/vw)")
                .containsPattern("width\\s*:\\s*\\d+(?:\\.\\d+)?(?:mm|cm|in|vh|vw)");

        // (a2) height in fixed-dimension units
        assertThat(pageRuleContent)
                .as(
                        "@media print .page must declare height in fixed-dimension units"
                                + " (mm/cm/in/vh/vw)")
                .containsPattern("height\\s*:\\s*\\d+(?:\\.\\d+)?(?:mm|cm|in|vh|vw)");

        // (a3) aspect-ratio: auto
        assertThat(pageRuleContent)
                .as("@media print .page must declare aspect-ratio: auto")
                .containsPattern("aspect-ratio\\s*:\\s*auto");

        // (a4) padding in fixed-dimension units — value must NOT contain cqh/cqw/cqi/cqb
        assertThat(pageRuleContent)
                .as("@media print .page must declare padding")
                .containsPattern("padding\\s*:");

        assertThat(pageRuleContent)
                .as(
                        "@media print .page padding must NOT use container-query units"
                                + " (cqh/cqw/cqi/cqb)")
                .doesNotContainPattern("padding\\s*:[^;]*(?:cqh|cqw|cqi|cqb)");
    }

    // =========================================================================
    // AC2 (b) — body rule: display: block + min-height: 0 (print-safe overrides)
    // =========================================================================

    @Test
    @DisplayName("AC2(b): @media print body rule overrides display to block and min-height to 0")
    void mediaPrint_bodyRule_hasPrintSafeOverrides() throws IOException {
        String block = readMediaPrintBlock();

        String bodyRuleContent = extractRuleContent(block, "body\\s*\\{");
        assertThat(bodyRuleContent).as("@media print body rule content").isNotNull();

        // (b1) display: block (overrides screen-mode display: flex)
        assertThat(bodyRuleContent)
                .as("@media print body must declare display: block")
                .containsPattern("display\\s*:\\s*block");

        // (b2) min-height: 0 (overrides screen-mode min-height: 100vh)
        assertThat(bodyRuleContent)
                .as("@media print body must declare min-height: 0")
                .containsPattern("min-height\\s*:\\s*0");
    }

    // =========================================================================
    // AC2 (c) — .page:last-child rule: page-break-after: auto
    // =========================================================================

    @Test
    @DisplayName("AC2(c): @media print .page:last-child declares page-break-after: auto")
    void mediaPrint_pageLastChildRule_hasPageBreakAfterAuto() throws IOException {
        String block = readMediaPrintBlock();

        String lastChildRuleContent = extractRuleContent(block, "\\.page:last-child\\s*\\{");
        assertThat(lastChildRuleContent)
                .as("@media print .page:last-child rule must exist")
                .isNotNull();

        assertThat(lastChildRuleContent)
                .as("@media print .page:last-child must declare page-break-after: auto")
                .containsPattern("page-break-after\\s*:\\s*auto");
    }

    // =========================================================================
    // Helper — extract CSS rule content by selector pattern
    // =========================================================================

    /**
     * Extracts the content (between braces) of the FIRST CSS rule whose selector matches {@code
     * selectorPattern} within the given {@code cssBlock}.
     *
     * @return rule content (between the outer braces, exclusive), or {@code null} if not found
     */
    private static String extractRuleContent(String cssBlock, String selectorPattern) {
        Pattern p = Pattern.compile(selectorPattern);
        Matcher m = p.matcher(cssBlock);
        if (!m.find()) {
            return null;
        }
        // Find the opening brace of this rule
        int openBrace = cssBlock.indexOf('{', m.start());
        if (openBrace < 0) return null;

        // Find matching closing brace (depth 1 — CSS rules are not nested here)
        int depth = 0;
        int end = -1;
        for (int i = openBrace; i < cssBlock.length(); i++) {
            char c = cssBlock.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) return null;
        return cssBlock.substring(openBrace + 1, end);
    }
}
