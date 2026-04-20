package de.vvwt.tm.tournament;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Svelte Admin SPA at {@code /admin/} and all nested paths (E21S09,
 * AC-TDD-AdminSpaController, AC-PKG-AdminSpaController).
 *
 * <p>This is the new public-API controller at {@code de.vvwt.tm.tournament.*} per DEC-21 (D-8
 * package discipline). It replaces the legacy {@code de.vvwt.tm.infrastructure.AdminSpaController}
 * at atomic cutover time. During the parallel-development phase, both coexist. The bean qualifier
 * {@code "tmAdminSpaController"} prevents Spring from treating them as duplicate beans.
 *
 * <h2>SPA serving strategy</h2>
 *
 * <p>The SPA uses hash-based routing (svelte-spa-router, Brief H-2 decision). Hash fragments
 * ({@code #/path}) are not sent to the server, so the server only ever receives requests for:
 *
 * <ul>
 *   <li>{@code /admin} or {@code /admin/} — the SPA entry point
 *   <li>{@code /admin/**} — deep-link URL typed into the address bar
 * </ul>
 *
 * <p>All of the above are forwarded to {@code classpath:/static/admin/index.html}. The Svelte
 * router then handles sub-routing client-side via the hash fragment.
 *
 * <h2>Static asset serving</h2>
 *
 * <p>Vite assets ({@code /admin/assets/*.js}, {@code /admin/assets/*.css}) are served by Spring
 * Boot's {@code ResourceHttpRequestHandler} from {@code classpath:/static/admin/assets/} — these
 * requests never reach this controller because they match static resources before MVC dispatch.
 *
 * <h2>Security (AC-SEC-NO-AUTH-BYPASS)</h2>
 *
 * <p>This controller does NOT declare {@code @PermitAll} or any Spring Security bypass. Access
 * control for {@code /admin/**} is governed entirely by the {@code SecurityConfig} filter chain,
 * which requires authentication for all {@code /admin/**} paths. This matches the legacy controller
 * behaviour — neither tightening nor loosening the security posture.
 *
 * <h2>404 / SPA fallback behaviour (AC-SPA-404)</h2>
 *
 * <p>Requests to {@code /admin/**} (including paths like {@code /admin/nonexistent.file.html}) are
 * forwarded to {@code index.html}. The Svelte router renders a client-side 404 page for unknown
 * routes. A raw stack trace is NEVER surfaced — Spring Boot's error handler ensures a structured
 * error response if the static file is absent.
 *
 * @see de.vvwt.tm.infrastructure.AdminSpaController legacy counterpart (untouched until cutover)
 */
@Controller("tmAdminSpaController")
public class AdminSpaController {

    /**
     * Serves {@code /admin/} and {@code /admin} (SPA root).
     *
     * <p>Maps both {@code /admin} (without trailing slash) and {@code /admin/} (with trailing
     * slash) to forward to the SPA {@code index.html} in {@code classpath:/static/admin/}.
     */
    @GetMapping({"/admin", "/admin/"})
    public String adminRoot() {
        return "forward:/static/admin/index.html";
    }

    /**
     * SPA fallback: serves {@code /admin/**} (deep-link support).
     *
     * <p>Paths such as {@code /admin/tournaments} typed directly into the address bar are forwarded
     * to the SPA {@code index.html}. The Svelte router handles the hash-based sub-route
     * client-side.
     *
     * <p>This mapping does NOT match static asset paths ({@code /admin/assets/**}) because Spring
     * Boot resolves classpath static resources before dispatching to controllers.
     */
    @GetMapping("/admin/**")
    public String adminDeepLink() {
        return "forward:/static/admin/index.html";
    }
}
