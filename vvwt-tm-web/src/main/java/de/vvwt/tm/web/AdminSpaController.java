package de.vvwt.tm.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Svelte Admin SPA at {@code /admin/} and all nested paths (E21S09,
 * AC-TDD-AdminSpaController, AC-PKG-AdminSpaController).
 *
 * <p>Relocated from the {@code tournament} package to {@code de.vvwt.tm.web} per DEC-40 Clause A
 * (E22S01, Q-1b whole-class refactor). Class body is byte-identical to the pre-relocation version
 * except for the {@code package} declaration and the removal of the stale
 * {@code @ConditionalOnMissingBean} guard (the legacy {@code
 * de.vvwt.tm.infrastructure.AdminSpaController} was deleted at E21S13 cutover; the guard became a
 * no-op and was removed during the E22S01 Q-1b move).
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
 * <p>All of the above are served by reading {@code classpath:/static/admin/index.html} directly as
 * a {@link ResponseEntity} backed by a {@link ClassPathResource}. This is the pattern used by
 * {@link de.vvwt.tm.web.timer.TimerViewController} for the Timer SPA (E26S03).
 *
 * <h2>E42S02 bug-fix: why ClassPathResource instead of {@code forward:}</h2>
 *
 * <p>The pre-fix implementation used {@code return "forward:/static/admin/index.html"}. Under
 * Spring Framework 7.x / Spring Boot 4.x, the {@code /static/} prefix is not valid as a URL path:
 * Spring Boot maps {@code classpath:/static/} onto URL {@code /} (NOT {@code /static/}). The
 * correct URL for the static resource is {@code /admin/index.html}. However, forwarding to {@code
 * /admin/index.html} causes an infinite dispatch loop because this controller maps {@code
 * /admin/**}, which intercepts the forward before the static resource handler can serve it. To
 * avoid the loop, this controller returns the resource directly (same approach as {@link
 * de.vvwt.tm.web.timer.TimerViewController} for {@code /timer/tournaments/{id}}, E26S03).
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
 * served with the SPA {@code index.html}. The Svelte router renders a client-side 404 page for
 * unknown routes. A raw stack trace is NEVER surfaced.
 */
@RestController
public class AdminSpaController {

    private static final Resource ADMIN_INDEX_HTML =
            new ClassPathResource("static/admin/index.html");

    /**
     * Serves {@code /admin/} and {@code /admin} (SPA root).
     *
     * <p>Returns the Vite-built Admin SPA shell from {@code classpath:/static/admin/index.html}
     * directly as a {@code text/html} {@link ResponseEntity}. The Svelte router handles sub-routing
     * client-side via the hash fragment.
     *
     * @return {@code text/html} response with the Admin SPA index.html content
     */
    @GetMapping({"/admin", "/admin/"})
    public ResponseEntity<Resource> adminRoot() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(ADMIN_INDEX_HTML);
    }

    /**
     * SPA fallback: serves {@code /admin/**} (deep-link support).
     *
     * <p>Paths such as {@code /admin/tournaments} typed directly into the address bar are served
     * with the SPA {@code index.html}. The Svelte router handles the hash-based sub-route
     * client-side.
     *
     * <p>This mapping does NOT match static asset paths ({@code /admin/assets/**}) because Spring
     * Boot resolves classpath static resources before dispatching to controllers.
     *
     * @return {@code text/html} response with the Admin SPA index.html content
     */
    @GetMapping("/admin/**")
    public ResponseEntity<Resource> adminDeepLink() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(ADMIN_INDEX_HTML);
    }
}
