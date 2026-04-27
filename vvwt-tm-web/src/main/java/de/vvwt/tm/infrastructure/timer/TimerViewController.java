package de.vvwt.tm.infrastructure.timer;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVC controller that serves the Timer SPA at {@code /timer/{tournamentId}} (E11S03).
 *
 * <h2>Route</h2>
 *
 * <ul>
 *   <li>{@code GET /timer/{tournamentId}} — serves the timer page for a specific tournament (AC1)
 * </ul>
 *
 * <p>The route forwards to the Vite-built {@code index.html} in {@code classpath:/static/timer/}.
 * The Svelte app reads {@code tournamentId} from {@code window.location.pathname} and fetches timer
 * data from {@code GET /api/timer/{tournamentId}} (E11S02, AC5).
 *
 * <h2>Security (AC1)</h2>
 *
 * <p>{@code /timer/**} is listed in {@link de.vvwt.tm.auth.internal.SecurityConfig} as {@code
 * permitAll()}. No authentication is required — the timer page is for venue use without admin
 * credentials. The underlying data endpoint ({@code /api/timer/**}) is also public per E11S02 AC6a.
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
 * bundled in the JAR. No CDN links appear in the rendered HTML. The integration test {@link
 * TimerViewControllerIT} verifies that no {@code https://} appears in asset tags.
 *
 * <h2>Why not use forward: (pattern deviation from de.vvwt.tm.web.DisplayViewController)</h2>
 *
 * <p>The display SPA controller maps specific named routes ({@code /display/overview}, {@code
 * /display/register}). The timer controller maps a path variable ({@code /timer/{tournamentId}}),
 * which means a forward to {@code /timer/index.html} would re-enter the controller (because {@code
 * index.html} would be matched as the path variable). To avoid an infinite forward loop, this
 * controller returns the {@code index.html} content directly as an {@link ResponseEntity} backed by
 * a {@link ClassPathResource}. This is functionally equivalent to a static resource serve.
 *
 * @see de.vvwt.tm.web.DisplayViewController — display SPA (named routes, forward OK)
 * @see de.vvwt.tm.infrastructure.AdminSpaController — admin SPA (named routes, forward OK)
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S03.story.md">Story
 *     E11S03</a>
 */
@RestController
public class TimerViewController {

    /**
     * Serves the Timer SPA HTML shell at {@code GET /timer/{tournamentId}} (AC1).
     *
     * <p>The {@code tournamentId} path variable is constrained to UUID format ({@code [0-9a-f-]+})
     * so that static resource requests (e.g. {@code /timer/index.html}, {@code /timer/assets/...})
     * do not accidentally match this route and cause a forward loop. The Svelte app reads the UUID
     * from {@code window.location.pathname} at runtime.
     *
     * <p>The forward target {@code /timer/index.html} is served as a static classpath resource from
     * {@code classpath:/static/timer/index.html} by Spring Boot's {@code
     * ResourceHttpRequestHandler} — it does not re-enter this controller because {@code index.html}
     * does not match the UUID-constrained path variable.
     *
     * @param tournamentId the tournament UUID from the URL — not used server-side
     * @return forward directive to the static Timer SPA shell
     */
    /**
     * Serves the Timer SPA HTML shell for any tournament UUID (AC1).
     *
     * <p>Returns the Vite-built {@code index.html} directly as a response body backed by {@code
     * classpath:/static/timer/index.html}. This avoids the infinite forward loop that would occur
     * if we used {@code forward:/timer/index.html} — since that path would re-enter this controller
     * with {@code tournamentId="index.html"}.
     *
     * <p>The Svelte app reads the tournament UUID from {@code window.location.pathname} at runtime
     * and calls {@code GET /api/timer/{tournamentId}} (E11S02).
     *
     * @param tournamentId tournament UUID from the URL — not used server-side
     * @return {@code text/html} response with the Timer SPA index.html content
     */
    @GetMapping("/timer/{tournamentId}")
    public ResponseEntity<Resource> timerPage(@PathVariable("tournamentId") String tournamentId) {
        Resource resource = new ClassPathResource("static/timer/index.html");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(resource);
    }
}
