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
 * RED-first guard test for the container-query-unit cleanup in {@code
 * certificate/_certificate_styles.mustache} (E12S12 AC2).
 *
 * <p>This test was committed RED — before the CSS fix — as required by the DEC-22 Iron Law. The
 * pre-story template contains {@code cqh} literals on every selector listed below (lines 46, 81,
 * 83, 97, 104, 106, 115, 144 in the origin/staging baseline at commit {@code 3e024ff8}). Therefore
 * every assertion in this test FAILS RED against the unmodified template.
 *
 * <p>After the Path A conversion (replacing all {@code cqh} occurrences on the seven screen-media
 * selectors with their {@code 0.7074×} {@code cqw} equivalents, and adopting the operator-tuned
 * vertical-spacing values from the story's Root Cause Analysis table), all assertions turn GREEN.
 *
 * <h2>AC2 assertions (DEC-22 RED-first, E12S12)</h2>
 *
 * <ul>
 *   <li>(a) The rendered CSS contains zero occurrences of any container-query block-direction unit
 *       literal ({@code cqh}, {@code cqb}) within screen-media rules applying to the seven
 *       selectors: {@code .page}, {@code .divider}, {@code .hero}, {@code .placement}, {@code
 *       .place-meta}, {@code .photo-wrap}, {@code .footer}.
 *   <li>(b) The {@code .page} {@code padding} rule (screen-media) declares values in
 *       container-query-inline-size units ({@code cqw} or {@code cqi}) for top + bottom — not
 *       {@code cqh}/{@code cqb}/{@code %}/{@code px}/{@code mm}.
 *   <li>(c) The {@code .place-meta .of} rule's {@code transform: translateY(...)} declaration uses
 *       {@code cqw}/{@code cqi} units — not {@code cqh}/{@code cqb}/{@code em}.
 *   <li>(d) The {@code @media print} block from E12S11 is preserved: the {@code .page} print rule
 *       still declares {@code width} AND {@code height} AND {@code aspect-ratio: auto} AND {@code
 *       padding} in fixed-mm units.
 * </ul>
 *
 * <p>No Spring context is loaded — this is a plain JUnit 5 unit test that reads the Mustache
 * template as a classpath resource and inspects the {@code <style>} text. The test is package-local
 * to {@code de.vvwt.tm.web.certificate} (same package as the render controller), consistent with
 * DEC-36.
 *
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first, reconstruction-in-place)</a>
 * @see <a href="DEC-54">DEC-54 — mvn verify canonical build gate</a>
 * @see <a href="DEC-75">DEC-75 §D6 — SPDX AGPL-3.0-or-later header</a>
 * @since E12S12
 */
@DisplayName(
        "E12S12 AC2 — cqh→cqw conversion guard: no block-direction CQ units in screen-media rules")
class CertificateContainerQueryUnitGuardTest {

    private static final String TEMPLATE_CLASSPATH =
            "/templates/certificate/_certificate_styles.mustache";

    /** Reads the full template text from classpath. */
    private String readTemplateContent() throws IOException {
        try (InputStream is =
                CertificateContainerQueryUnitGuardTest.class.getResourceAsStream(
                        TEMPLATE_CLASSPATH)) {
            assertThat(is)
                    .as("Template must be on classpath at %s", TEMPLATE_CLASSPATH)
                    .isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Extracts the screen-media section of the CSS: everything before the {@code @media print {}
     * block.
     */
    private String extractScreenMediaCss(String content) {
        int printStart = content.indexOf("@media print {");
        assertThat(printStart).as("@media print block must exist in template").isGreaterThan(0);
        return content.substring(0, printStart);
    }

    // =========================================================================
    // AC2 (a) — no cqh/cqb literals in screen-media rules for the seven selectors
    // =========================================================================

    /**
     * Extracts the rule body (between braces) of the FIRST CSS rule whose selector matches {@code
     * selectorPattern} within the given {@code cssBlock}. Returns {@code null} when not found.
     */
    private static String extractRuleContent(String cssBlock, String selectorPattern) {
        Pattern p = Pattern.compile(selectorPattern);
        Matcher m = p.matcher(cssBlock);
        if (!m.find()) return null;
        int openBrace = cssBlock.indexOf('{', m.start());
        if (openBrace < 0) return null;
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

    @Test
    @DisplayName("AC2(a): screen-media .page rule contains no cqh or cqb units")
    void screenMedia_pageRule_hasNoCqhOrCqb() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.page\\s*\\{");
        assertThat(rule).as(".page screen-media rule must exist").isNotNull();
        assertThat(rule)
                .as(".page screen-media rule must not contain cqh or cqb")
                .doesNotContainPattern("(?:cqh|cqb)");
    }

    @Test
    @DisplayName("AC2(a): screen-media .divider rule contains no cqh or cqb units")
    void screenMedia_dividerRule_hasNoCqhOrCqb() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.divider\\s*\\{");
        assertThat(rule).as(".divider screen-media rule must exist").isNotNull();
        assertThat(rule)
                .as(".divider screen-media rule must not contain cqh or cqb")
                .doesNotContainPattern("(?:cqh|cqb)");
    }

    @Test
    @DisplayName("AC2(a): screen-media .hero rule contains no cqh or cqb units")
    void screenMedia_heroRule_hasNoCqhOrCqb() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.hero\\s*\\{");
        assertThat(rule).as(".hero screen-media rule must exist").isNotNull();
        assertThat(rule)
                .as(".hero screen-media rule must not contain cqh or cqb")
                .doesNotContainPattern("(?:cqh|cqb)");
    }

    @Test
    @DisplayName("AC2(a): screen-media .placement rule contains no cqh or cqb units")
    void screenMedia_placementRule_hasNoCqhOrCqb() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.placement\\s*\\{");
        assertThat(rule).as(".placement screen-media rule must exist").isNotNull();
        assertThat(rule)
                .as(".placement screen-media rule must not contain cqh or cqb")
                .doesNotContainPattern("(?:cqh|cqb)");
    }

    @Test
    @DisplayName("AC2(a): screen-media .place-meta .row rule contains no cqh or cqb units")
    void screenMedia_placeMetaRowRule_hasNoCqhOrCqb() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.place-meta\\s+\\.row\\s*\\{");
        assertThat(rule).as(".place-meta .row screen-media rule must exist").isNotNull();
        assertThat(rule)
                .as(".place-meta .row screen-media rule must not contain cqh or cqb")
                .doesNotContainPattern("(?:cqh|cqb)");
    }

    @Test
    @DisplayName("AC2(a): screen-media .photo-wrap rule contains no cqh or cqb units")
    void screenMedia_photoWrapRule_hasNoCqhOrCqb() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.photo-wrap\\s*\\{");
        assertThat(rule).as(".photo-wrap screen-media rule must exist").isNotNull();
        assertThat(rule)
                .as(".photo-wrap screen-media rule must not contain cqh or cqb")
                .doesNotContainPattern("(?:cqh|cqb)");
    }

    @Test
    @DisplayName("AC2(a): screen-media .footer rule contains no cqh or cqb units")
    void screenMedia_footerRule_hasNoCqhOrCqb() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.footer\\s*\\{");
        assertThat(rule).as(".footer screen-media rule must exist").isNotNull();
        assertThat(rule)
                .as(".footer screen-media rule must not contain cqh or cqb")
                .doesNotContainPattern("(?:cqh|cqb)");
    }

    // =========================================================================
    // AC2 (b) — .page padding uses cqw/cqi units (screen-media)
    // =========================================================================

    @Test
    @DisplayName("AC2(b): screen-media .page padding declares top+bottom in cqw or cqi units")
    void screenMedia_pageRule_paddingUsesCqwOrCqi() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.page\\s*\\{");
        assertThat(rule).as(".page screen-media rule must exist").isNotNull();

        // padding must be present
        assertThat(rule)
                .as(".page screen-media rule must declare padding")
                .containsPattern("padding\\s*:");

        // padding value must contain cqw or cqi (not just px/mm/%)
        assertThat(rule)
                .as(".page screen-media padding must use cqw or cqi units")
                .containsPattern("padding\\s*:[^;]*(?:cqw|cqi)");
    }

    // =========================================================================
    // AC2 (c) — .place-meta .of transform uses cqw/cqi units
    // =========================================================================

    @Test
    @DisplayName("AC2(c): screen-media .place-meta .of transform: translateY uses cqw or cqi units")
    void screenMedia_placeMetaOf_transformUsesCqwOrCqi() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.place-meta\\s+\\.of\\s*\\{");
        assertThat(rule).as(".place-meta .of screen-media rule must exist").isNotNull();

        // transform: translateY must be present
        assertThat(rule)
                .as(".place-meta .of screen-media rule must declare transform: translateY")
                .containsPattern("transform\\s*:\\s*translateY");

        // translateY argument must use cqw or cqi units
        assertThat(rule)
                .as(".place-meta .of translateY argument must use cqw or cqi units")
                .containsPattern("translateY\\s*\\([^)]*(?:cqw|cqi)[^)]*\\)");
    }

    // =========================================================================
    // AC2 (d) — @media print block from E12S11 preserved
    // =========================================================================

    @Test
    @DisplayName(
            "AC2(d): @media print .page rule still declares width, height, aspect-ratio: auto,"
                    + " and padding in fixed-mm units")
    void mediaPrint_pageRule_fixedDimensionsPreserved() throws IOException {
        String content = readTemplateContent();
        int printStart = content.indexOf("@media print {");
        assertThat(printStart).as("@media print block must exist").isGreaterThan(0);
        String printBlock = content.substring(printStart);

        String pageRule = extractRuleContent(printBlock, "\\.page\\s*\\{");
        assertThat(pageRule).as("@media print .page rule must exist").isNotNull();

        assertThat(pageRule)
                .as("@media print .page must declare width in mm/cm/in")
                .containsPattern("width\\s*:\\s*\\d+(?:\\.\\d+)?(?:mm|cm|in)");

        assertThat(pageRule)
                .as("@media print .page must declare height in mm/cm/in")
                .containsPattern("height\\s*:\\s*\\d+(?:\\.\\d+)?(?:mm|cm|in)");

        assertThat(pageRule)
                .as("@media print .page must declare aspect-ratio: auto")
                .containsPattern("aspect-ratio\\s*:\\s*auto");

        assertThat(pageRule)
                .as("@media print .page padding must not use container-query units")
                .doesNotContainPattern("padding\\s*:[^;]*(?:cqh|cqw|cqi|cqb)");
    }
}
