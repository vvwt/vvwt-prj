package de.vvwt.tm.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * MVC controller that serves the display SPA pages — E25S02 Q-1a TDD reconstruction.
 *
 * <h2>Routes (AC-RED-FIRST-DISPLAY-VIEW-CONTROLLER)</h2>
 *
 * <ul>
 *   <li>{@code GET /display/overview} — serves the Gesamtübersicht SPA shell (AC-URL-PATHS-PRESERVED)
 *   <li>{@code GET /display/register} — serves the device registration SPA shell (AC-URL-PATHS-PRESERVED)
 * </ul>
 *
 * <p>Both routes forward to the same Vite-built {@code index.html}. The Svelte app uses {@code
 * window.location.pathname} to decide which "page" to render client-side. Spring Boot's {@code
 * ResourceHttpRequestHandler} serves {@code classpath:/static/display/index.html} when the URL is
 * {@code /display/index.html}.
 *
 * <h2>SPA serving strategy</h2>
 *
 * <p>The display SPA does not use a router library. {@code App.svelte} inspects {@code
 * window.location.pathname} on mount to dispatch to the registration or overview flow. Both paths
 * share the same Vite entry point and compiled bundle — no second build is needed.
 *
 * <h2>DEC-16 offline compatibility (AC-URL-PATHS-PRESERVED)</h2>
 *
 * <p>The Vite-built {@code index.html} references only local assets ({@code /display/assets/...})
 * bundled in the JAR. No CDN links appear in the rendered HTML. {@link
 * DisplayViewControllerIT} verifies that no {@code https://} appears in {@code <script>} or {@code
 * <link>} tags.
 *
 * <h2>Security (AC-AUTHENTICATION-FLOW-PRESERVED)</h2>
 *
 * <p>The display routes serve a read-only Svelte bundle. No admin actions are possible from this
 * surface. Spring Security permits the entire {@code /display/**} path space without admin
 * authentication (see {@code de.vvwt.tm.auth.internal.SecurityConfig}). Device tokens are never
 * embedded in the HTML shell.
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-40 Clause A — controller resides in {@code de.vvwt.tm.web}, NOT in any bounded-context
 *       module; relocated from {@code de.vvwt.tm.infrastructure.display.*} per E25 display track
 *       reconstruction
 *   <li>DEC-22 Iron Law Q-1a — authored RED-first: {@link DisplayViewControllerIT} written before
 *       this class; RED commit 6e0e8d8; GREEN commit = this class
 * </ul>
 *
 * @see DisplayOverviewController
 * @since E25S02
 */
@Controller
public class DisplayViewController {

    /**
     * Serves the Gesamtübersicht HTML shell at {@code GET /display/overview}.
     *
     * <p>Forwards to the Vite-built {@code index.html} in {@code classpath:/static/display/}.
     * The Svelte app handles token resolution, data fetching, and rendering client-side.
     * URL path preserved verbatim per AC-URL-PATHS-PRESERVED (C-8 + D-9).
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
     * Serves the device registration HTML shell at {@code GET /display/register}.
     *
     * <p>Forwards to the same Vite-built {@code index.html} as {@code /display/overview}. The
     * Svelte app reads {@code window.location.pathname} on mount and renders the registration page
     * when the path starts with {@code /display/register}. No authentication is required — display
     * devices register themselves without admin credentials.
     * URL path preserved verbatim per AC-URL-PATHS-PRESERVED (C-8 + D-9).
     *
     * @return forward directive to the static Svelte shell (shared with /display/overview)
     */
    @GetMapping("/display/register")
    public String registerPage() {
        return "forward:/display/index.html";
    }
}
