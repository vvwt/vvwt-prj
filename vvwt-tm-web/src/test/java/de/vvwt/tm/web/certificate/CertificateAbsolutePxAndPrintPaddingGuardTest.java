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
 * RED-first guard test for the E12S13 Path A extension in {@code
 * certificate/_certificate_styles.mustache}:
 *
 * <ul>
 *   <li>(a) All absolute-pixel declarations on layout-affecting screen-media selectors are
 *       converted to {@code cqw} equivalents (AC2 part a).
 *   <li>(b) The {@code @media print} {@code .page} {@code padding} mm values are within ±0.05 mm of
 *       the proportional-mirror targets {@code 10.504mm 20.79mm 8.405mm} (AC2 part b).
 *   <li>(c) The existing E12S12 {@link CertificateContainerQueryUnitGuardTest} assertions still
 *       PASS (implicitly verified by the E12S12 test suite — this test confirms only that the
 *       E12S12 structural contract is not broken by E12S13's changes, via the AC2(c) pass-through
 *       listed assertions on properties NOT touched by E12S13).
 * </ul>
 *
 * <p>This test was committed RED (against the pre-E12S13 template at commit {@code 68f7eec6}). At
 * that baseline the screen-media rules still carry absolute-px literals on the listed selectors and
 * the {@code @media print} {@code .page} {@code padding} is {@code 12mm 18mm 10mm}. Therefore
 * assertions (a) and (b) FAIL RED; assertion (c) is GREEN because E12S12 already ships
 * container-query {@code .page} screen-media padding.
 *
 * <p>After the E12S13 fix: (a) all listed absolute-px selectors carry {@code cqw} values; (b) the
 * print {@code .page} {@code padding} is {@code 10.504mm 20.79mm 8.405mm}; all assertions turn
 * GREEN.
 *
 * <p>No Spring context is loaded — this is a plain JUnit 5 unit test that reads the Mustache
 * template as a classpath resource and inspects the {@code <style>} text. Package-local to {@code
 * de.vvwt.tm.web.certificate}, consistent with DEC-36.
 *
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-54">DEC-54 — mvn verify canonical build gate</a>
 * @see <a href="DEC-75">DEC-75 §D6 — SPDX AGPL-3.0-or-later header</a>
 * @since E12S13
 */
@DisplayName("E12S13 AC2 — absolute-px→cqw guard + print-padding proportional-mirror guard")
class CertificateAbsolutePxAndPrintPaddingGuardTest {

    private static final String TEMPLATE_CLASSPATH =
            "/templates/certificate/_certificate_styles.mustache";

    /**
     * Proportional-mirror print-padding targets for A4-landscape (297 mm width), derived from the
     * screen-media {@code .page} {@code padding: 3.537cqw 7cqw 2.830cqw} fractions.
     */
    private static final double PRINT_PADDING_TOP_TARGET_MM = 10.504; // 3.537% × 297

    private static final double PRINT_PADDING_SIDE_TARGET_MM = 20.79; // 7.000% × 297
    private static final double PRINT_PADDING_BOTTOM_TARGET_MM = 8.405; // 2.830% × 297
    private static final double PRINT_PADDING_TOLERANCE_MM = 0.051; // ±0.05 mm, exclusive

    // Matches an integer-or-decimal px literal, e.g. "36px" or "1.5px"
    private static final Pattern ABSOLUTE_PX =
            Pattern.compile("(?<![\\w-])\\d+(?:\\.\\d+)?px(?![\\w-])");

    /** Reads the full template text from classpath. */
    private String readTemplateContent() throws IOException {
        try (InputStream is =
                CertificateAbsolutePxAndPrintPaddingGuardTest.class.getResourceAsStream(
                        TEMPLATE_CLASSPATH)) {
            assertThat(is)
                    .as("Template must be on classpath at %s", TEMPLATE_CLASSPATH)
                    .isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Extracts the screen-media section: everything before the {@code @media print \{} block.
     */
    private static String extractScreenMediaCss(String content) {
        int printStart = content.indexOf("@media print {");
        assertThat(printStart).as("@media print block must exist in template").isGreaterThan(0);
        return content.substring(0, printStart);
    }

    /**
     * Extracts the rule body (between outer braces, exclusive) of the FIRST CSS rule whose
     * selector is matched EXACTLY by {@code exactSelectorPattern} within {@code cssBlock}. The
     * pattern must end just before the opening brace — use {@code \s*\{} terminator to prevent
     * greedy prefix matching (e.g. {@code \.urkunde-badge\s*\{} must NOT match {@code
     * .urkunde-badge .label \{}).
     *
     * @return rule body, or {@code null} if not found
     */
    private static String extractRuleContent(String cssBlock, String exactSelectorPattern) {
        Pattern p = Pattern.compile(exactSelectorPattern);
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

    /**
     * Asserts that the given CSS rule body contains no absolute-pixel literal on the specified
     * {@code propertyName} declaration. Passes the property value substring through the absolute-px
     * detector only — letter-spacings, {@code 1px} borders, and {@code box-shadow} offsets are NOT
     * asserted on by this helper (the {@code propertyName} argument scopes the check).
     */
    private static void assertNoPxOnProperty(
            String ruleBody, String propertyName, String selectorDesc) {
        assertThat(ruleBody).as("Rule body for %s must be non-null", selectorDesc).isNotNull();
        // Extract the value for the target property
        Pattern propPat = Pattern.compile(Pattern.quote(propertyName) + "\\s*:([^;]+);");
        Matcher m = propPat.matcher(ruleBody);
        assertThat(m.find())
                .as(
                        "Property '%s' must be declared in rule body for %s",
                        propertyName, selectorDesc)
                .isTrue();
        String value = m.group(1);
        assertThat(ABSOLUTE_PX.matcher(value).find())
                .as(
                        "Property '%s' in %s must not contain absolute-px literal, found: '%s'",
                        propertyName, selectorDesc, value.trim())
                .isFalse();
    }

    // =========================================================================
    // AC2 (a) — font-size properties: no absolute-px on listed selectors
    // =========================================================================

    @Test
    @DisplayName("AC2(a): .lockup-row font-size must be in cqw (not px)")
    void lockupRow_fontSize_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.lockup-row\\s*\\{");
        assertNoPxOnProperty(rule, "font-size", ".lockup-row");
    }

    @Test
    @DisplayName("AC2(a): .wm-tag font-size must be in cqw (not px)")
    void wmTag_fontSize_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.wm-tag\\s*\\{");
        assertNoPxOnProperty(rule, "font-size", ".wm-tag");
    }

    @Test
    @DisplayName("AC2(a): .wm-tag margin-top must be in cqw (not px)")
    void wmTag_marginTop_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.wm-tag\\s*\\{");
        assertNoPxOnProperty(rule, "margin-top", ".wm-tag");
    }

    @Test
    @DisplayName("AC2(a): .urkunde-badge .label font-size must be in cqw (not px)")
    void urkundeBadgeLabel_fontSize_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.urkunde-badge\\s+\\.label\\s*\\{");
        assertNoPxOnProperty(rule, "font-size", ".urkunde-badge .label");
    }

    @Test
    @DisplayName("AC2(a): .hero-subtitle font-size must be in cqw (not px)")
    void heroSubtitle_fontSize_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.hero-subtitle\\s*\\{");
        assertNoPxOnProperty(rule, "font-size", ".hero-subtitle");
    }

    @Test
    @DisplayName("AC2(a): .hero-subtitle margin-top must be in cqw (not px)")
    void heroSubtitle_marginTop_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.hero-subtitle\\s*\\{");
        assertNoPxOnProperty(rule, "margin-top", ".hero-subtitle");
    }

    @Test
    @DisplayName("AC2(a): .place-meta .lbl font-size must be in cqw (not px)")
    void placeMetaLbl_fontSize_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.place-meta\\s+\\.lbl\\s*\\{");
        assertNoPxOnProperty(rule, "font-size", ".place-meta .lbl");
    }

    @Test
    @DisplayName("AC2(a): .place-meta .of font-size must be in cqw (not px)")
    void placeMetaOf_fontSize_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.place-meta\\s+\\.of\\s*\\{");
        assertNoPxOnProperty(rule, "font-size", ".place-meta .of");
    }

    @Test
    @DisplayName("AC2(a): .footer font-size must be in cqw (not px)")
    void footer_fontSize_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.footer\\s*\\{");
        assertNoPxOnProperty(rule, "font-size", ".footer");
    }

    // =========================================================================
    // AC2 (a) — gap properties: no absolute-px on listed selectors
    // =========================================================================

    @Test
    @DisplayName("AC2(a): .meta gap must be in cqw (not px)")
    void meta_gap_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.meta\\s*\\{");
        assertNoPxOnProperty(rule, "gap", ".meta");
    }

    @Test
    @DisplayName("AC2(a): .placement gap must be in cqw (not px)")
    void placement_gap_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.placement\\s*\\{");
        assertNoPxOnProperty(rule, "gap", ".placement");
    }

    @Test
    @DisplayName("AC2(a): .place-meta .row gap must be in cqw (not px)")
    void placeMetaRow_gap_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.place-meta\\s+\\.row\\s*\\{");
        assertNoPxOnProperty(rule, "gap", ".place-meta .row");
    }

    // =========================================================================
    // AC2 (a) — padding properties: no absolute-px
    // =========================================================================

    @Test
    @DisplayName("AC2(a): .urkunde-badge padding must be in cqw (not px) — vertical component")
    void urkundeBadge_padding_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        // Use exact match: ".urkunde-badge {" — NOT ".urkunde-badge .label {"
        String rule = extractRuleContent(screen, "\\.urkunde-badge\\s*\\{");
        assertNoPxOnProperty(rule, "padding", ".urkunde-badge");
    }

    @Test
    @DisplayName("AC2(a): .place-meta padding-bottom must be in cqw (not px)")
    void placeMeta_paddingBottom_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        // Exact match: ".place-meta {" — NOT ".place-meta .row {" etc.
        String rule = extractRuleContent(screen, "\\.place-meta\\s*\\{");
        assertNoPxOnProperty(rule, "padding-bottom", ".place-meta");
    }

    // =========================================================================
    // AC2 (a) — margin properties: no absolute-px
    // =========================================================================

    @Test
    @DisplayName("AC2(a): .hero-title margin (top component) must be in cqw (not px)")
    void heroTitle_margin_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        // ".hero-title {" — NOT ".hero-subtitle {"
        String rule = extractRuleContent(screen, "\\.hero-title\\s*\\{");
        assertNoPxOnProperty(rule, "margin", ".hero-title");
    }

    @Test
    @DisplayName("AC2(a): .place-meta .accent margin-bottom must be in cqw (not px)")
    void placeMetaAccent_marginBottom_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.place-meta\\s+\\.accent\\s*\\{");
        assertNoPxOnProperty(rule, "margin-bottom", ".place-meta .accent");
    }

    // =========================================================================
    // AC2 (a) — width / height / right / bottom properties: no absolute-px
    // =========================================================================

    @Test
    @DisplayName("AC2(a): .lockup-row .hex width must be in cqw (not px)")
    void lockupRowHex_width_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.lockup-row\\s+\\.hex\\s*\\{");
        assertNoPxOnProperty(rule, "width", ".lockup-row .hex");
    }

    @Test
    @DisplayName("AC2(a): .lockup-row .hex height must be in cqw (not px)")
    void lockupRowHex_height_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.lockup-row\\s+\\.hex\\s*\\{");
        assertNoPxOnProperty(rule, "height", ".lockup-row .hex");
    }

    @Test
    @DisplayName("AC2(a): .place-meta .accent width must be in cqw (not px)")
    void placeMetaAccent_width_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.place-meta\\s+\\.accent\\s*\\{");
        assertNoPxOnProperty(rule, "width", ".place-meta .accent");
    }

    @Test
    @DisplayName("AC2(a): .stamp width must be in cqw (not px)")
    void stamp_width_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.stamp\\s*\\{");
        assertNoPxOnProperty(rule, "width", ".stamp");
    }

    @Test
    @DisplayName("AC2(a): .stamp height must be in cqw (not px)")
    void stamp_height_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.stamp\\s*\\{");
        assertNoPxOnProperty(rule, "height", ".stamp");
    }

    @Test
    @DisplayName("AC2(a): .stamp right must be in cqw (not px)")
    void stamp_right_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.stamp\\s*\\{");
        assertNoPxOnProperty(rule, "right", ".stamp");
    }

    @Test
    @DisplayName("AC2(a): .stamp bottom must be in cqw (not px)")
    void stamp_bottom_noPx() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.stamp\\s*\\{");
        assertNoPxOnProperty(rule, "bottom", ".stamp");
    }

    // =========================================================================
    // AC2 (b) — @media print .page padding: proportional-mirror mm values
    // =========================================================================

    /**
     * Asserts that the {@code @media print .page} {@code padding} declaration matches the
     * proportional-mirror targets within ±0.05 mm tolerance.
     *
     * <p>Expected: {@code 10.504mm 20.79mm 8.405mm} (= 3.537% / 7% / 2.830% of 297 mm).
     */
    @Test
    @DisplayName(
            "AC2(b): @media print .page padding mm values match proportional-mirror targets"
                    + " within ±0.05 mm (10.504mm 20.79mm 8.405mm)")
    void mediaPrint_pagePadding_proportionalMirror() throws IOException {
        String content = readTemplateContent();
        int printStart = content.indexOf("@media print {");
        assertThat(printStart).as("@media print block must exist").isGreaterThan(0);
        String printBlock = content.substring(printStart);

        String pageRule = extractRuleContent(printBlock, "\\.page\\s*\\{");
        assertThat(pageRule).as("@media print .page rule must exist").isNotNull();

        // Extract the padding shorthand: "padding: <top>mm <side>mm <bottom>mm"
        Pattern paddingPat =
                Pattern.compile("padding\\s*:\\s*([\\d.]+)mm\\s+([\\d.]+)mm\\s+([\\d.]+)mm");
        Matcher m = paddingPat.matcher(pageRule);
        assertThat(m.find())
                .as(
                        "@media print .page must declare padding in the form"
                                + " '<top>mm <side>mm <bottom>mm' — found rule body: %s",
                        pageRule.trim())
                .isTrue();

        double topMm = Double.parseDouble(m.group(1));
        double sideMm = Double.parseDouble(m.group(2));
        double bottomMm = Double.parseDouble(m.group(3));

        assertThat(Math.abs(topMm - PRINT_PADDING_TOP_TARGET_MM))
                .as(
                        "@media print .page padding-top must be within ±0.05 mm of %.3f mm"
                                + " (proportional mirror of 3.537cqw at A4-landscape 297mm);"
                                + " actual: %.3f mm",
                        PRINT_PADDING_TOP_TARGET_MM, topMm)
                .isLessThan(PRINT_PADDING_TOLERANCE_MM);

        assertThat(Math.abs(sideMm - PRINT_PADDING_SIDE_TARGET_MM))
                .as(
                        "@media print .page padding-side must be within ±0.05 mm of %.2f mm"
                                + " (proportional mirror of 7.000cqw at A4-landscape 297mm);"
                                + " actual: %.3f mm",
                        PRINT_PADDING_SIDE_TARGET_MM, sideMm)
                .isLessThan(PRINT_PADDING_TOLERANCE_MM);

        assertThat(Math.abs(bottomMm - PRINT_PADDING_BOTTOM_TARGET_MM))
                .as(
                        "@media print .page padding-bottom must be within ±0.05 mm of %.3f mm"
                                + " (proportional mirror of 2.830cqw at A4-landscape 297mm);"
                                + " actual: %.3f mm",
                        PRINT_PADDING_BOTTOM_TARGET_MM, bottomMm)
                .isLessThan(PRINT_PADDING_TOLERANCE_MM);
    }

    // =========================================================================
    // AC2 (c) — E12S12 guard contract: screen-media .page padding still in cqw
    //           (pass-through assertion — confirms E12S13 does not regress E12S12)
    // =========================================================================

    @Test
    @DisplayName(
            "AC2(c): screen-media .page padding still declares cqw units (E12S12 contract"
                    + " preserved)")
    void screenMedia_pagePadding_stillCqw() throws IOException {
        String screen = extractScreenMediaCss(readTemplateContent());
        String rule = extractRuleContent(screen, "\\.page\\s*\\{");
        assertThat(rule).as(".page screen-media rule must exist").isNotNull();
        assertThat(rule)
                .as(".page screen-media padding must still use cqw or cqi units (E12S12 contract)")
                .containsPattern("padding\\s*:[^;]*(?:cqw|cqi)");
    }
}
