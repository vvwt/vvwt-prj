package de.vvwt.tm.infrastructure;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Svelte Admin SPA at {@code /admin/} and all nested paths.
 *
 * <p>Story E05S01 — AC3, AC4, AC8 | DEC-2 (Svelte/Vite SPA, no SvelteKit).
 *
 * <h2>SPA serving strategy</h2>
 *
 * <p>The SPA uses hash-based routing (svelte-spa-router, Brief H-2 decision). Hash fragments
 * ({@code #/path}) are not sent to the server, so the server only ever receives requests for:
 *
 * <ul>
 *   <li>{@code /admin} or {@code /admin/} — the SPA entry point (AC3)
 *   <li>{@code /admin/**} — deep-link URL typed into the address bar (AC4, AC8)
 * </ul>
 *
 * <h2>Static asset serving</h2>
 *
 * <p>Vite assets ({@code /admin/assets/*.js}, {@code /admin/assets/*.css}) are served by Spring
 * Boot's {@code ResourceHttpRequestHandler} from {@code classpath:/static/admin/assets/} — these
 * requests never reach this controller because they match static resources before MVC dispatch.
 *
 * <h2>404 outside /admin/</h2>
 *
 * <p>Requests to paths not matched by this controller and not matched by any other
 * {@code @RequestMapping} fall through to Spring Boot's default {@code BasicErrorController}, which
 * returns HTTP 404 (AC8). This controller does NOT install a global catch-all.
 *
 * <h2>AC10 — no secrets in SPA bundle</h2>
 *
 * <p>This controller serves static HTML only. No server-side configuration, credentials, or
 * environment-specific values are injected into the SPA response.
 */
@Controller
public class AdminSpaController {

    /**
     * Serves {@code /admin/} (SPA root, AC3).
     *
     * <p>Maps both {@code /admin} (without trailing slash) and {@code /admin/} (with trailing
     * slash) to forward to the SPA {@code index.html} in {@code classpath:/static/admin/}.
     */
    @GetMapping({"/admin", "/admin/"})
    public String adminRoot() {
        return "forward:/static/admin/index.html";
    }

    /**
     * SPA fallback: serves {@code /admin/**} (deep-link support, AC4, AC8).
     *
     * <p>Paths such as {@code /admin/tournaments} typed directly into the address bar are forwarded
     * to the SPA {@code index.html}. The Svelte router then handles the hash-based sub-route
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
