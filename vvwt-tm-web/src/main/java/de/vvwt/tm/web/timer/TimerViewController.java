package de.vvwt.tm.web.timer;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVC controller that serves the Timer SPA at {@code /timer/tournaments/{tournamentId}} (E26S03
 * Q-1a TDD reconstruction; Wave-2-aligned URL per Brief v1.1 D-10).
 *
 * <h2>Route (Wave-2-aligned per Brief v1.1 D-10)</h2>
 *
 * <ul>
 *   <li>{@code GET /timer/tournaments/{tournamentId}} — serves the timer page for a specific
 *       tournament
 * </ul>
 *
 * <p>The route returns the Vite-built {@code index.html} from {@code
 * classpath:/static/timer/index.html}. The Svelte app reads {@code tournamentId} from {@code
 * window.location.pathname} and fetches timer data from {@code GET
 * /api/timer/tournaments/{tournamentId}} (E26S03 TimerController).
 *
 * <h2>Security (AC-AUTHENTICATION-FLOW-PRESERVED)</h2>
 *
 * <p>{@code /timer/tournaments/**} is listed in {@link de.vvwt.tm.auth.internal.SecurityConfig} as
 * {@code permitAll()}. No authentication is required — the timer page is for venue use without
 * admin credentials.
 *
 * <h2>SPA serving strategy</h2>
 *
 * <p>The timer SPA does not use a router library. {@code App.svelte} reads {@code
 * window.location.pathname} on mount to extract the tournament UUID. Spring Boot's {@code
 * ResourceHttpRequestHandler} serves {@code /timer/assets/**} directly from {@code
 * classpath:/static/timer/assets/} — those requests never reach this controller.
 *
 * <h2>DEC-15 compatibility</h2>
 *
 * <p>The Vite-built {@code index.html} references only local assets ({@code /timer/assets/...})
 * bundled in the JAR. No CDN links appear in the rendered HTML.
 *
 * <h2>Forward-loop avoidance pattern preserved verbatim per C-3</h2>
 *
 * <p>The timer controller maps a path variable ({@code /timer/tournaments/{tournamentId}}), which
 * means a forward to {@code /timer/tournaments/index.html} would NOT re-enter this controller since
 * {@code index.html} would not match the expected route. However, for consistency with the legacy
 * implementation and to eliminate any risk of infinite forwarding (documented reasoning from the
 * legacy {@code TimerViewController} Javadoc lines 43-50), this controller returns the {@code
 * index.html} content directly as a {@link ResponseEntity} backed by a {@link ClassPathResource}.
 * This is functionally equivalent to a static resource serve.
 *
 * @see TimerController
 * @since E26S03
 */
@RestController
public class TimerViewController {

    /**
     * Serves the Timer SPA HTML shell at {@code GET /timer/tournaments/{tournamentId}}.
     *
     * <p>The {@code tournamentId} path variable is accepted as a {@link String} — the SPA reads it
     * from {@code window.location.pathname} at runtime; no server-side validation is needed.
     *
     * <p>Returns {@code text/html} response with the Vite-built {@code index.html} content from
     * {@code classpath:/static/timer/index.html}.
     *
     * @param tournamentId the tournament UUID from the URL — not validated server-side (SPA reads
     *     it at runtime)
     * @return {@code text/html} response with the Timer SPA index.html content
     */
    @GetMapping("/timer/tournaments/{tournamentId}")
    public ResponseEntity<Resource> timerPage(@PathVariable("tournamentId") String tournamentId) {
        Resource resource = new ClassPathResource("static/timer/index.html");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(resource);
    }
}
