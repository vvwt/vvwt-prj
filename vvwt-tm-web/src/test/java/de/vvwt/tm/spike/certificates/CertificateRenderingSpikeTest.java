package de.vvwt.tm.spike.certificates;

import static org.assertj.core.api.Assertions.*;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E12S01 — Certificate Rendering Spike: SVG vs HTML+print-CSS approach evaluation.
 *
 * <p>This is a <em>technical spike</em> — not production code. Tests verify that jmustache can
 * render both SVG templates (AC1) and HTML templates (AC2) correctly, and document error modes
 * (AC6). The findings inform the approach choice for E12S04 / E12S06.
 *
 * <p>Browser rendering findings (AC3, AC4) are documented in the impl-report artefact. Automated
 * tests validate the Mustache rendering layer only — not browser print output.
 *
 * <h2>Story: E12S01</h2>
 *
 * <ul>
 *   <li>AC1: SVG PoC — render Inkscape-style SVG with gradients, blur, base64 image, Mustache
 *       placeholders
 *   <li>AC2: HTML PoC — render HTML+CSS certificate template through jmustache
 *   <li>AC3: SVG browser rendering findings (documented in impl-report)
 *   <li>AC4: HTML print rendering findings (documented in impl-report)
 *   <li>AC6: Error-handling — failure modes for both approaches
 * </ul>
 */
@DisplayName("E12S01 — Certificate Rendering Spike: SVG vs HTML+CSS")
class CertificateRenderingSpikeTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Test data
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A minimal 10×10 red PNG, base64-encoded. Used as a representative "team photo" placeholder in
     * rendering tests. Kept small (< 1 KB) to avoid memory pressure (AC6 error-mode: oversized
     * images are tested separately with a synthetic large string).
     */
    private static final String MINIMAL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAoAAAAKCAYAAACNMs+9AAAAFklEQVR42mP8z8BQDwADhQGAWjR9awAAAABJRU5ErkJggg==";

    private static final String TEAM_PHOTO_DATA_URI = "data:image/png;base64," + MINIMAL_PNG_BASE64;

    private static final Map<String, String> STANDARD_DATA =
            Map.of(
                    "placement", "1. Platz",
                    "teamName", "VB Muster-Team",
                    "teamPhoto", TEAM_PHOTO_DATA_URI);

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Load a resource from the test classpath as a String. Resource path is relative to this
     * class's package directory.
     */
    private String loadResource(String filename) throws Exception {
        String path = "/de/vvwt/tm/spike/certificates/" + filename;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            assertThat(is).as("Spike resource not found on classpath: %s", path).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Render a Mustache template string with the given data map using jmustache.
     *
     * <p>Uses lenient mode with HTML escaping disabled — critical for SVG and raw HTML certificate
     * templates. jmustache defaults to HTML escaping ({@code &} → {@code &amp;}, {@code =} → {@code
     * &#x3D;}), which corrupts base64 data URIs (the trailing {@code ==} becomes {@code
     * &#x3D;&#x3D;}).
     *
     * <p><strong>AC6 finding:</strong> The production certificate renderer MUST disable HTML
     * escaping ({@code escapeHTML(false)}) to preserve data URIs and SVG attribute values. Using
     * the default escaped renderer silently corrupts embedded images.
     */
    private String renderLenient(String templateSource, Map<String, ?> data) {
        Mustache.Compiler compiler =
                Mustache.compiler()
                        .escapeHTML(false) // CRITICAL: preserve base64 "==" in data URIs and SVG
                        // attributes
                        .defaultValue(""); // lenient: missing keys render as empty string
        com.samskivert.mustache.Template template = compiler.compile(templateSource);
        StringWriter writer = new StringWriter();
        template.execute(data, writer);
        return writer.toString();
    }

    /**
     * Render a Mustache template string in strict mode (missing keys throw MustacheException).
     *
     * <p>In jmustache 1.16 the equivalent of "strict mode" is achieved by NOT providing a {@code
     * defaultValue} — when a key is missing and no default is configured, jmustache throws a {@link
     * MustacheException} during template execution.
     *
     * <p>Also uses {@code escapeHTML(false)} to match the production renderer configuration.
     */
    private String renderStrict(String templateSource, Map<String, ?> data) {
        // No defaultValue set → missing keys throw MustacheException.
        // This is jmustache 1.16's strict rendering mode (no strictMode() method in this version).
        Mustache.Compiler compiler =
                Mustache.compiler().escapeHTML(false); // match production renderer configuration
        com.samskivert.mustache.Template template = compiler.compile(templateSource);
        StringWriter writer = new StringWriter();
        template.execute(data, writer);
        return writer.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC1 — SVG PoC rendering
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * AC1: Render the SVG PoC template through jmustache.
     *
     * <p>Verifies:
     *
     * <ul>
     *   <li>Template loads correctly from classpath
     *   <li>jmustache replaces all three placeholders: {{placement}}, {{teamName}}, {{teamPhoto}}
     *   <li>Rendered output is a valid SVG string (starts with SVG markup)
     *   <li>Gradient definition is preserved through rendering
     *   <li>Gaussian blur filter definition is preserved
     *   <li>Base64-encoded image is embedded (data URI present)
     *   <li>No unreplaced Mustache placeholders remain in the output
     * </ul>
     */
    @Test
    @DisplayName(
            "AC1: SVG template renders through jmustache — placeholders replaced, SVG structure"
                    + " preserved")
    void testSvgRendering() throws Exception {
        // Arrange
        String template = loadResource("certificate-poc.svg");

        // Verify template loaded and contains expected structure
        assertThat(template).contains("<svg");
        assertThat(template).contains("linearGradient");
        assertThat(template).contains("feGaussianBlur");
        assertThat(template).contains("{{placement}}");
        assertThat(template).contains("{{teamName}}");
        assertThat(template).contains("{{teamPhoto}}");

        // Act
        String rendered = renderLenient(template, STANDARD_DATA);

        // Assert: placeholders are replaced
        assertThat(rendered)
                .as("{{placement}} must be replaced with '1. Platz'")
                .contains("1. Platz")
                .doesNotContain("{{placement}}");

        assertThat(rendered)
                .as("{{teamName}} must be replaced with team name")
                .contains("VB Muster-Team")
                .doesNotContain("{{teamName}}");

        assertThat(rendered)
                .as("{{teamPhoto}} must be replaced with data URI")
                .contains("data:image/png;base64,")
                .doesNotContain("{{teamPhoto}}");

        // Assert: SVG structure is preserved
        assertThat(rendered)
                .as("Rendered output must be valid SVG")
                .startsWith("<?xml")
                .contains("<svg");

        // Assert: SVG features are intact (gradient, filter)
        assertThat(rendered)
                .as("linearGradient definition must survive rendering")
                .contains("linearGradient");

        assertThat(rendered)
                .as("feGaussianBlur filter definition must survive rendering")
                .contains("feGaussianBlur");

        // Assert: base64 image is embedded (data URI format)
        assertThat(rendered)
                .as("Base64 image data URI must be embedded in SVG")
                .contains("data:image/png;base64," + MINIMAL_PNG_BASE64);

        // Assert: no unreplaced Mustache tokens remain
        assertThat(rendered)
                .as("No unreplaced Mustache placeholders must remain in the rendered SVG")
                .doesNotContain("{{")
                .doesNotContain("}}");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC2 — HTML PoC rendering
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * AC2: Render the HTML+CSS certificate template through jmustache.
     *
     * <p>Verifies:
     *
     * <ul>
     *   <li>Template loads correctly from classpath
     *   <li>All three placeholders are replaced
     *   <li>Rendered output is valid HTML (DOCTYPE present)
     *   <li>Photo is embedded via {@code <img>} tag with data URI
     *   <li>Certificate CSS structure is present (@media print reference)
     *   <li>No unreplaced Mustache placeholders remain
     * </ul>
     */
    @Test
    @DisplayName(
            "AC2: HTML+CSS template renders through jmustache — placeholders replaced, HTML"
                    + " structure valid")
    void testHtmlRendering() throws Exception {
        // Arrange
        String template = loadResource("certificate-poc.html.mustache");

        // Verify template loaded
        assertThat(template).contains("<!DOCTYPE html>");
        assertThat(template).contains("{{placement}}");
        assertThat(template).contains("{{teamName}}");
        assertThat(template).contains("{{teamPhoto}}");

        // Act
        String rendered = renderLenient(template, STANDARD_DATA);

        // Assert: placeholders are replaced
        assertThat(rendered)
                .as("{{placement}} must be replaced")
                .contains("1. Platz")
                .doesNotContain("{{placement}}");

        assertThat(rendered)
                .as("{{teamName}} must be replaced")
                .contains("VB Muster-Team")
                .doesNotContain("{{teamName}}");

        assertThat(rendered)
                .as("{{teamPhoto}} must be replaced with data URI in <img> src")
                .contains("src=\"" + TEAM_PHOTO_DATA_URI + "\"")
                .doesNotContain("{{teamPhoto}}");

        // Assert: valid HTML structure
        // Note: the template begins with an HTML comment (<!-- ... -->) before <!DOCTYPE html>.
        // HTML comments are valid before DOCTYPE per HTML5 spec (though non-standard in practice).
        // The test verifies DOCTYPE presence, not position.
        assertThat(rendered)
                .as("Rendered output must contain valid HTML DOCTYPE declaration")
                .contains("<!DOCTYPE html>");

        // Assert: @media print rule is present in the CSS
        assertThat(rendered)
                .as("@media print rules must be present for A4 output (AC4)")
                .contains("@media print");

        assertThat(rendered)
                .as("A4 page size declaration must be present in @media print")
                .contains("A4 landscape");

        // Assert: no unreplaced Mustache opening tokens remain.
        // Note: closing braces '}}' may legitimately appear in HTML/CSS content
        // (e.g., nested CSS rules, HTML comments with literal braces).
        // Only opening '{{' indicates an unreplaced placeholder.
        assertThat(rendered)
                .as("No unreplaced Mustache placeholders must remain in the rendered HTML")
                .doesNotContain("{{");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC6 — SVG error mode documentation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * AC6 (SVG path): Error modes documented via test.
     *
     * <p>Failure modes tested:
     *
     * <ol>
     *   <li>Malformed SVG (unclosed tag): jmustache renders because Mustache operates on raw string
     *       content — it does not parse XML. Malformed SVG is rendered without error.
     *   <li>Missing key with strictMode=false: jmustache renders empty string for missing key.
     *   <li>Missing key with strictMode=true: jmustache throws {@link MustacheException}.
     *   <li>Oversized base64 image: rendering completes (jmustache treats value as a string, no
     *       size validation). Memory impact is proportional to string size but manageable for
     *       certificate-sized templates.
     * </ol>
     */
    @Test
    @DisplayName("AC6 (SVG): Malformed SVG — jmustache renders raw string without XML validation")
    void testSvgErrorMode_malformedSvg() {
        // Malformed SVG: unclosed rect tag
        String malformedSvg = "<svg><rect id=\"{{placement}}\" width=\"100\" height=\"100\"";
        // Note: missing closing > and </svg>

        // jmustache is a string-based engine — it does NOT validate XML
        // This is a documented finding for AC6: malformed SVG is rendered silently
        Map<String, String> data = Map.of("placement", "test-id");

        String rendered = renderLenient(malformedSvg, data);

        assertThat(rendered)
                .as(
                        "jmustache renders malformed SVG without exception (AC6 finding: no XML"
                                + " validation)")
                .contains("test-id")
                .doesNotContain("{{placement}}");
    }

    @Test
    @DisplayName("AC6 (SVG): Missing key with strictMode=false — empty string substitution")
    void testSvgErrorMode_missingKeyLenient() throws Exception {
        String template = loadResource("certificate-poc.svg");
        // Provide only placement, omit teamName and teamPhoto
        Map<String, String> partialData = Map.of("placement", "1. Platz");

        // lenient mode: missing keys render as empty string — template renders without exception
        String rendered = renderLenient(template, partialData);

        assertThat(rendered)
                .as("With strictMode=false, missing keys render as empty string (AC6 finding)")
                .contains("1. Platz")
                .doesNotContain("{{placement}}")
                // teamName and teamPhoto were missing — their placeholders were replaced with empty
                // string
                .doesNotContain("{{teamName}}")
                .doesNotContain("{{teamPhoto}}");

        // The rendered output contains both href="" and xlink:href="" for the image element
        // (empty string substitution for teamPhoto). This is a documented limitation.
        assertThat(rendered)
                .as(
                        "Empty teamPhoto results in href=\"\" in the SVG image element (AC6"
                                + " documented behavior)")
                .contains("href=\"\"");
    }

    @Test
    @DisplayName("AC6 (SVG): Missing key with strictMode=true — MustacheException thrown")
    void testSvgErrorMode_missingKeyStrict() throws Exception {
        String template = loadResource("certificate-poc.svg");
        Map<String, String> partialData = Map.of("placement", "1. Platz");
        // teamName and teamPhoto are missing

        // strict mode: missing key → MustacheException
        assertThatThrownBy(() -> renderStrict(template, partialData))
                .as(
                        "With strictMode=true, missing key throws MustacheException (AC6: error"
                                + " handling for invalid templates)")
                .isInstanceOf(MustacheException.class);
    }

    @Test
    @DisplayName(
            "AC6 (SVG): Oversized base64 image — rendering completes (no size limit in jmustache)")
    void testSvgErrorMode_oversizedBase64Image() throws Exception {
        String template = loadResource("certificate-poc.svg");

        // Simulate oversized image: ~500 KB base64 string (generated synthetically)
        // 500 KB ≈ 666,667 base64 characters; we use a repeating pattern
        String oversizedBase64 = "A".repeat(666_667);
        String oversizedDataUri = "data:image/jpeg;base64," + oversizedBase64;

        Map<String, String> data =
                Map.of(
                        "placement", "2. Platz",
                        "teamName", "Test Team",
                        "teamPhoto", oversizedDataUri);

        // jmustache does not impose a size limit — it treats the value as a plain string
        // The rendering should complete without error
        String rendered = renderLenient(template, data);

        assertThat(rendered)
                .as(
                        "Oversized base64 image renders without exception (AC6: no size limit in"
                                + " jmustache string substitution)")
                .contains("data:image/jpeg;base64,")
                .doesNotContain("{{teamPhoto}}");

        // Note for impl-report: the rendered SVG will be large (~500 KB).
        // Impact: browser may be slow to open; print from browser is not affected for normal
        // certificate images.
        // Recommendation: validate image size server-side before Mustache rendering in production.
        assertThat(rendered.length())
                .as("Rendered output size reflects oversized image embedded in SVG")
                .isGreaterThan(666_000);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC6 — HTML error mode documentation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * AC6 (HTML path): Error modes documented via test.
     *
     * <p>Failure modes tested:
     *
     * <ol>
     *   <li>Missing key with strictMode=false: empty string substitution, template renders.
     *   <li>Missing key with strictMode=true: MustacheException thrown.
     * </ol>
     *
     * <p>Note on malformed HTML: same as SVG — jmustache is string-based and does not parse HTML.
     * Malformed HTML templates render silently (same finding as SVG malformed-template test).
     */
    @Test
    @DisplayName(
            "AC6 (HTML): Missing key with strictMode=false — empty substitution, renders without"
                    + " exception")
    void testHtmlErrorMode_missingKeyLenient() throws Exception {
        String template = loadResource("certificate-poc.html.mustache");
        Map<String, String> partialData = Map.of("placement", "3. Platz");
        // teamName and teamPhoto missing

        String rendered = renderLenient(template, partialData);

        assertThat(rendered)
                .as(
                        "With strictMode=false, missing HTML keys render as empty string (AC6 HTML"
                                + " finding)")
                .contains("3. Platz")
                .doesNotContain("{{placement}}")
                .doesNotContain("{{teamName}}")
                .doesNotContain("{{teamPhoto}}");

        // The <img src=""> will be present with an empty src — renders as broken image in browser
        // (documented behavior: always provide all required template variables)
        assertThat(rendered)
                .as("Missing teamPhoto results in src=\"\" on the img tag")
                .contains("src=\"\"");
    }

    @Test
    @DisplayName("AC6 (HTML): Missing key with strictMode=true — MustacheException thrown")
    void testHtmlErrorMode_missingKeyStrict() throws Exception {
        String template = loadResource("certificate-poc.html.mustache");
        Map<String, String> partialData = Map.of("placement", "3. Platz");

        assertThatThrownBy(() -> renderStrict(template, partialData))
                .as("With strictMode=true, missing HTML key throws MustacheException (AC6)")
                .isInstanceOf(MustacheException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC5 — Verify rendering completeness (both paths produce non-trivial output)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * AC5 supporting test: both rendered outputs contain non-trivial content. The impl-report
     * documents the qualitative findings (recommendation, trade-offs).
     */
    @Test
    @DisplayName("AC5: Both SVG and HTML render non-trivial output with all three data variables")
    void testBothPathsProduceCompleteOutput() throws Exception {
        String svgTemplate = loadResource("certificate-poc.svg");
        String htmlTemplate = loadResource("certificate-poc.html.mustache");

        String svgRendered = renderLenient(svgTemplate, STANDARD_DATA);
        String htmlRendered = renderLenient(htmlTemplate, STANDARD_DATA);

        // Both contain all three data values, with no unreplaced Mustache openers
        for (String rendered : new String[] {svgRendered, htmlRendered}) {
            assertThat(rendered).contains("1. Platz", "VB Muster-Team");
            assertThat(rendered).contains(TEAM_PHOTO_DATA_URI);
            // Only check for '{{' (opening braces = unreplaced placeholder).
            // '}}' may appear in HTML comments or CSS blocks legitimately.
            assertThat(rendered).doesNotContain("{{");
        }

        // SVG path produces an SVG document
        assertThat(svgRendered).contains("<svg").contains("</svg>");

        // HTML path produces an HTML document (starts with HTML comment, then DOCTYPE)
        assertThat(htmlRendered).contains("<!DOCTYPE html>").contains("</html>");
    }
}
