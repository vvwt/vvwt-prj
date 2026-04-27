package de.vvwt.tm.infrastructure.web.timer;

import de.vvwt.tm.timer.TimerDataService;
import de.vvwt.tm.web.internal.dto.TimerDataResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the timer data endpoint (E11S02).
 *
 * <p>Provides a single public endpoint for timer devices to fetch the full tournament schedule,
 * current position, and audio configuration. No authentication is required (AC6a — the timer page
 * has no admin auth).
 *
 * <h2>Endpoint</h2>
 *
 * <pre>GET /api/timer/{tournamentId}</pre>
 *
 * <h2>Security (AC6a)</h2>
 *
 * <p>{@code /api/timer/**} is listed in {@link de.vvwt.tm.auth.internal.SecurityConfig} as {@code
 * permitAll()}. The {@code /api/**} catch-all rule that requires authentication is declared AFTER
 * this, so the timer endpoint remains public.
 *
 * <h2>Error handling (AC7)</h2>
 *
 * <p>Exceptions are translated by {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}:
 *
 * <ul>
 *   <li>{@link de.vvwt.tm.timer.InvalidTimerUrlException} → HTTP 404 with {@code errorCode:
 *       "INVALID_TIMER_URL"}
 *   <li>{@link de.vvwt.tm.timer.NoActiveTournamentException} → HTTP 404 with {@code
 *       errorCode: "NO_ACTIVE_TOURNAMENT"}
 * </ul>
 *
 * @see TimerDataService
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S02.story.md">Story
 *     E11S02</a>
 */
@RestController
@RequestMapping("/api/timer")
public class TimerController {

    private final TimerDataService timerDataService;

    public TimerController(TimerDataService timerDataService) {
        this.timerDataService = timerDataService;
    }

    /**
     * Returns the full timer data for the given tournament.
     *
     * <p>The response covers:
     *
     * <ul>
     *   <li>Tournament metadata and status (AC1, AC5)
     *   <li>Ordered schedule of rounds and breaks with optional wall-clock times (AC1, AC2, AC3)
     *   <li>Phase structure summary with per-phase lap counts (AC5)
     *   <li>Audio file URLs per category (AC4)
     * </ul>
     *
     * <p>Returns HTTP 200 in all success cases, including when no phases have been configured yet
     * (AC7 — {@code emptySchedule: true}).
     *
     * @param tournamentId the UUID of the tournament from the URL path
     * @return 200 with {@link TimerDataResponse}, or 404 if the tournament does not exist or is in
     *     DRAFT / CANCELLED status
     */
    @GetMapping("/{tournamentId}")
    public ResponseEntity<TimerDataResponse> getTimerData(
            @PathVariable("tournamentId") UUID tournamentId) {

        TimerDataResponse response = timerDataService.buildTimerData(tournamentId);
        return ResponseEntity.ok(response);
    }
}
