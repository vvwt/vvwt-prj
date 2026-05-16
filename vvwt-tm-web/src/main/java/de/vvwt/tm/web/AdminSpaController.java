// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Svelte Admin SPA at {@code /admin/} and {@code /admin} (E21S09,
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
 * <p>The Admin SPA uses hash-based routing (svelte-spa-router, Brief H-2 decision). Hash fragments
 * ({@code #/path}) are not sent to the server, so the server only ever receives requests for:
 *
 * <ul>
 *   <li>{@code /admin} or {@code /admin/} — the SPA entry point
 * </ul>
 *
 * <p>Both are served by reading {@code classpath:/static/admin/index.html} directly as a {@link
 * ResponseEntity} backed by a {@link ClassPathResource}. This is the pattern used by {@link
 * de.vvwt.tm.web.timer.TimerViewController} for the Timer SPA (E26S03).
 *
 * <h2>E21S18 bug-fix: remove adminDeepLink() catch-all</h2>
 *
 * <p>The pre-fix implementation had a second mapping method {@code adminDeepLink()} with
 * {@code @GetMapping("/admin/**")}. Under Spring Framework 7.x / Spring Boot 4.x
 * (PathPatternParser), controller {@code @GetMapping} patterns are resolved with HIGHER precedence
 * than Spring Boot's static {@code ResourceHttpRequestHandler}. As a result, the {@code /admin/**}
 * catch-all intercepted asset requests such as {@code /admin/assets/index-*.css} and {@code
 * /admin/assets/index-*.js}, serving them as {@code text/html} with the {@code index.html} body —
 * making the SPA unstyled and non-interactive.
 *
 * <p>The Javadoc claim in the pre-fix version that "Spring Boot resolves classpath static resources
 * before dispatching to controllers" is empirically false under Spring Framework 7 / Spring Boot 4.
 * H-1 (hypothesis: PathPatternParser ranks controller mappings before ResourceHttpRequestHandler)
 * is empirically VERIFIED by the E21S18 incident.
 *
 * <p>With hash-based routing, the catch-all {@code /admin/**} was vestigial: sub-routes like {@code
 * /admin/#/tournaments} never reach the server as {@code /admin/tournaments} — the hash fragment is
 * client-side only. The only server-visible paths are {@code /admin} and {@code /admin/}, both
 * handled by {@link #adminRoot()}.
 *
 * <p>The fix is to delete {@code adminDeepLink()} entirely. Asset requests at {@code
 * /admin/assets/**} fall through to Spring Boot's {@code ResourceHttpRequestHandler}, which serves
 * them with the correct MIME type ({@code text/css}, {@code application/javascript}) and the actual
 * binary content.
 *
 * <h2>E42S02 bug-fix: why ClassPathResource instead of {@code forward:}</h2>
 *
 * <p>The pre-E42S02 implementation used {@code return "forward:/static/admin/index.html"}. Under
 * Spring Framework 7.x / Spring Boot 4.x, the {@code /static/} prefix is not valid as a URL path:
 * Spring Boot maps {@code classpath:/static/} onto URL {@code /} (NOT {@code /static/}). The
 * correct URL for the static resource is {@code /admin/index.html}. However, forwarding to {@code
 * /admin/index.html} would cause an infinite dispatch loop if the catch-all were still present
 * (because {@code @GetMapping("/admin/**")} would intercept the forward). With the catch-all
 * removed (E21S18), forwarding to {@code /admin/index.html} is safe. The {@link ClassPathResource}
 * approach is retained from E42S02 for consistency with {@link
 * de.vvwt.tm.web.timer.TimerViewController}.
 *
 * <h2>Static asset serving</h2>
 *
 * <p>Vite assets ({@code /admin/assets/*.js}, {@code /admin/assets/*.css}) are served by Spring
 * Boot's {@code ResourceHttpRequestHandler} from {@code classpath:/static/admin/assets/}. After the
 * removal of {@code adminDeepLink()}, these requests correctly reach the static handler without
 * being intercepted by this controller.
 *
 * <h2>Security (AC-SEC-NO-AUTH-BYPASS)</h2>
 *
 * <p>This controller does NOT declare {@code @PermitAll} or any Spring Security bypass. Access
 * control for {@code /admin/**} is governed entirely by the {@code SecurityConfig} filter chain,
 * which requires authentication for all {@code /admin/**} paths. This matches the legacy controller
 * behaviour — neither tightening nor loosening the security posture.
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
     * <p>No catch-all mapping exists in this controller (E21S18 fix: {@code adminDeepLink()} was
     * deleted). Asset paths ({@code /admin/assets/**}) fall through to Spring Boot's {@code
     * ResourceHttpRequestHandler} for correct MIME-type-aware static resource serving.
     *
     * @return {@code text/html} response with the Admin SPA index.html content
     */
    @GetMapping({"/admin", "/admin/"})
    public ResponseEntity<Resource> adminRoot() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(ADMIN_INDEX_HTML);
    }
}
