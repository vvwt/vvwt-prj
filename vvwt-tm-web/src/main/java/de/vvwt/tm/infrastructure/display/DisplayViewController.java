package de.vvwt.tm.infrastructure.display;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * MVC controller that serves the display SPA pages (E07S05, E07S07).
 *
 * <h2>Routes</h2>
 *
 * <ul>
 *   <li>{@code GET /display/overview} — serves the Gesamtübersicht (E07S05 AC8)
 *   <li>{@code GET /display/register} — serves the device registration page (E07S07 AC1, AC8)
 * </ul>
 *
 * <p>Both routes forward to the same Vite-built {@code index.html}. The Svelte app uses {@code
 * window.location.pathname} to decide which "page" to render client-side (E07S07 plan §2). Spring
 * Security permits the entire {@code /display/**} path space without authentication (see {@link
 * de.vvwt.tm.auth.SecurityConfig}).
 *
 * <h2>SPA serving strategy</h2>
 *
 * <p>The display SPA does not use a router library. {@code App.svelte} inspects {@code
 * window.location.pathname} on mount to dispatch to {@code DisplayRegisterPage} or the existing
 * overview flow. Both paths share the same Vite entry point and compiled bundle — no second build
 * is needed.
 *
 * <h2>DEC-16 / AC8 offline compatibility</h2>
 *
 * <p>The Vite-built {@code index.html} references only local assets ({@code /display/assets/...})
 * bundled in the JAR. No CDN links appear in the rendered HTML. The integration test {@link
 * de.vvwt.tm.infrastructure.display.DisplayViewControllerIT} verifies that no {@code https://}
 * appears in {@code <script>} or {@code <link>} tags.
 *
 * <h2>Security (AC11)</h2>
 *
 * <p>The display routes serve a read-only Svelte bundle. No admin actions are possible from this
 * surface. Device tokens are never embedded in the HTML shell — tokens are written to and read from
 * {@code localStorage} exclusively by the Svelte app at runtime.
 *
 * @see de.vvwt.tm.infrastructure.AdminSpaController — same pattern for the admin SPA
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S05.story.md">Story
 *     E07S05</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S07.story.md">Story
 *     E07S07</a>
 */
@Controller
public class DisplayViewController {

    /**
     * Serves the Gesamtübersicht HTML shell at {@code GET /display/overview} (E07S05 AC8).
     *
     * <p>Forwards to the Vite-built {@code index.html} in {@code classpath:/static/display/}. The
     * Svelte app handles token resolution, data fetching, and rendering client-side.
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

    /**
     * Serves the device registration HTML shell at {@code GET /display/register} (E07S07 AC1, AC8).
     *
     * <p>Forwards to the same Vite-built {@code index.html} as {@code /display/overview}. The
     * Svelte app reads {@code window.location.pathname} on mount and renders {@code
     * DisplayRegisterPage} when the path starts with {@code /display/register}. No authentication
     * is required — display devices register themselves without admin credentials.
     *
     * @return forward directive to the static Svelte shell (shared with /display/overview)
     */
    @GetMapping("/display/register")
    public String registerPage() {
        return "forward:/display/index.html";
    }
}
