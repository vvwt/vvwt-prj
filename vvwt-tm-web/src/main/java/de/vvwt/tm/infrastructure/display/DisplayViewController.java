package de.vvwt.tm.infrastructure.display;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * MVC controller that serves the Gesamtübersicht (tournament overview) display page (E07S05).
 *
 * <h2>Route (AC8)</h2>
 * <p>{@code GET /display/overview} serves the Svelte display bundle's {@code index.html}
 * without authentication. Spring Security permits the entire {@code /display/**} path space
 * (see {@link de.vvwt.tm.auth.SecurityConfig}). The Vite build places compiled assets under
 * {@code classpath:/static/display/} (via maven-resources-plugin in pom.xml).
 *
 * <h2>SPA serving strategy</h2>
 * <p>The display SPA does not use hash-based routing (it is a single-view app). The controller
 * forwards {@code /display/overview} to the static {@code index.html} in
 * {@code classpath:/static/display/}. Static assets ({@code /display/assets/*.js/.css}) are
 * served by Spring Boot's {@code ResourceHttpRequestHandler} before this controller is reached.
 *
 * <h2>DEC-16 / AC7 offline compatibility</h2>
 * <p>The Vite-built {@code index.html} references only local assets ({@code /display/assets/...})
 * bundled in the JAR. No CDN links appear in the rendered HTML. The integration test
 * {@link de.vvwt.tm.infrastructure.display.DisplayViewControllerIT} verifies that no
 * {@code https://} appears in {@code <script>} or {@code <link>} tags.
 *
 * <h2>Security (AC11)</h2>
 * <p>The display route serves a read-only Svelte bundle. All data access goes through the
 * device-token-authenticated {@code /api/display/**} endpoints (E07S04). No admin actions are
 * possible from this surface. No device token is ever embedded in the HTML shell — tokens
 * are read exclusively from {@code localStorage} by the Svelte app at runtime.
 *
 * <h2>Token redirect (AC11)</h2>
 * <p>If no valid device token is found in {@code localStorage}, the Svelte app (not this
 * controller) redirects to {@code /display/register} (E07S07). This controller always serves
 * the shell unconditionally — server-side {@code localStorage} access is impossible.
 *
 * @see de.vvwt.tm.infrastructure.AdminSpaController — same pattern for the admin SPA
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S05.story.md">Story E07S05</a>
 */
@Controller
public class DisplayViewController {

    /**
     * Serves the Gesamtübersicht HTML shell at {@code GET /display/overview} (AC8).
     *
     * <p>Forwards to the Vite-built {@code index.html} in {@code classpath:/static/display/}.
     * The Svelte app handles token resolution, data fetching, and rendering client-side.
     *
     * @return forward directive to the static Svelte shell
     */
    @GetMapping("/display/overview")
    public String overviewPage() {
        // Forward to /display/index.html — Spring Boot's ResourceHttpRequestHandler serves
        // classpath:/static/display/index.html when the URL is /display/index.html.
        // (Spring Boot maps classpath:/static/** → /**, so the URL path is /display/index.html,
        // NOT /static/display/index.html.)
        return "forward:/display/index.html";
    }
}
