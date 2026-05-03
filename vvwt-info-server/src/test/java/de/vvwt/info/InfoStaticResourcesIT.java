package de.vvwt.info;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC1, AC2, AC4, AC6, AC7 — Static-resource integration test for Info branding.
 *
 * <p>DEC-22 Iron Law: written BEFORE index.html branding edits (RED state). RED: the current
 * index.html has no favicon links and title "vvwt-info — Öffentliches Teilnehmerportal".
 *
 * <p>Test annotation per AC1: {@code @SpringBootTest(webEnvironment=RANDOM_PORT,
 * classes=InfoServerApplication.class)}. RANDOM_PORT is mandatory — it exercises the actual {@link
 * org.springframework.web.servlet.resource.ResourceHttpRequestHandler} chain end-to-end.
 * {@code @WebMvcTest} is insufficient (does not load static resource handlers).
 *
 * <p>DEC-44 / DEC-38 NOT applicable: {@code vvwt-info-server} is a separate Maven submodule per
 * DEC-42 D1, NOT a Spring Modulith bounded-context-module within {@code vvwt-tm-web}. Its own
 * {@code InfoServerApplication} entry class is used directly.
 *
 * <p>Test classpath note: {@code src/test/resources/static/info/index.html} is the branding
 * fixture. Spring Boot's test classpath merges test/resources on top of main/resources, so the
 * fixture is served at {@code GET /info/}. This mirrors the production intent: Vite builds the
 * same-content index.html into the gitignored {@code src/main/resources/static/info/}.
 *
 * <p>Story: E44S03 — DEC-2, DEC-22, DEC-42.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = InfoServerApplication.class)
@ActiveProfiles("self-host")
class InfoStaticResourcesIT {

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate rest;

    @Test
    void indexHtmlContainsYellowFaviconSvgLink_AC1() {
        // AC1: SVG-first yellow favicon link present in served HTML
        String body = getInfoIndexBody();
        assertThat(body)
                .as("AC1: index.html must contain SVG favicon link for yellow variant")
                .contains(
                        "<link rel=\"icon\" type=\"image/svg+xml\" href=\"vvw-icon-yellow.svg\">");
    }

    @Test
    void indexHtmlContainsPngFaviconFallback_AC1() {
        // AC1: at least one PNG fallback link present
        String body = getInfoIndexBody();
        assertThat(body)
                .as("AC1: index.html must contain at least one PNG favicon fallback")
                .contains("type=\"image/png\"");
        assertThat(body)
                .as("AC1: PNG fallback must use yellow variant")
                .contains("vvw-favicon-yellow-");
    }

    @Test
    void indexHtmlTitleIsToMLiveInformation_AC2() {
        // AC2: title must be exactly "ToM · Live Information" (U+00B7 middle-dot)
        String body = getInfoIndexBody();
        assertThat(body)
                .as("AC2: title must be 'ToM · Live Information'")
                .contains("<title>ToM · Live Information</title>");
    }

    @Test
    void indexHtmlContainsAppleTouchIconWithoutSizesAttr_AC4() {
        // AC4: apple-touch-icon declared with 256px PNG, no sizes attribute
        String body = getInfoIndexBody();
        assertThat(body)
                .as("AC4: apple-touch-icon must reference 256px yellow PNG")
                .contains("rel=\"apple-touch-icon\"")
                .contains("vvw-favicon-yellow-256.png");
        // Verify no sizes attribute on this link
        // Extract the apple-touch-icon line and verify no sizes= is present on it
        int atiIdx = body.indexOf("rel=\"apple-touch-icon\"");
        assertThat(atiIdx).as("AC4: apple-touch-icon link must exist").isGreaterThanOrEqualTo(0);
        // Find the enclosing <link ...> tag
        int linkStart = body.lastIndexOf("<link", atiIdx);
        int linkEnd = body.indexOf(">", atiIdx);
        String linkTag = body.substring(linkStart, linkEnd + 1);
        assertThat(linkTag)
                .as(
                        "AC4: apple-touch-icon link MUST NOT have a sizes attribute (256px PNG, iOS"
                                + " auto-scales per Brief S-5)")
                .doesNotContain("sizes=");
    }

    @Test
    void indexHtmlSvgFaviconDeclaredBeforePngFallbacks_AC6() {
        // AC6: SVG link must appear before any PNG link (SVG-first fallback order per Brief Q-3)
        String body = getInfoIndexBody();
        int svgIdx = body.indexOf("type=\"image/svg+xml\"");
        int pngIdx = body.indexOf("type=\"image/png\"");
        assertThat(svgIdx)
                .as("AC6: SVG favicon link must be declared before PNG fallback links")
                .isGreaterThanOrEqualTo(0)
                .isLessThan(pngIdx);
    }

    @Test
    void missingBrandAssetReturns404_AC7() {
        // AC7: missing brand asset returns 404; Spring ResourceHttpRequestHandler default behaviour
        ResponseEntity<String> resp =
                rest.getForEntity(
                        "http://localhost:" + port + "/info/non-existent-asset.svg", String.class);
        assertThat(resp.getStatusCode())
                .as("AC7: request for non-existent brand asset must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void indexHtmlContains16pxFaviconLink_AC12() {
        // AC12: 16x16 PNG favicon declared (minimum render size — Brief C-9)
        String body = getInfoIndexBody();
        assertThat(body)
                .as("AC12: index.html must contain 16x16 favicon link")
                .contains("sizes=\"16x16\"")
                .contains("vvw-favicon-yellow-16.png");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private String getInfoIndexBody() {
        // Request the index.html explicitly — Spring Boot's ResourceHttpRequestHandler serves
        // classpath:/static/info/index.html at GET /info/index.html. The trailing-slash form
        // GET /info/ also maps to the same resource via Spring's welcome-file handling; both
        // paths are equivalent for static content testing. Using /info/index.html is the most
        // explicit and reliable form in RANDOM_PORT context.
        ResponseEntity<String> resp =
                rest.getForEntity("http://localhost:" + port + "/info/index.html", String.class);
        assertThat(resp.getStatusCode())
                .as("GET /info/index.html must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }
}
