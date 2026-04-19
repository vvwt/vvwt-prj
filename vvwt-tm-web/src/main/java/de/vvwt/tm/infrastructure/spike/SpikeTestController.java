package de.vvwt.tm.infrastructure.spike;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Controller for the iOS 9 compatibility spike (E06S01, DEC-19).
 *
 * <p>Serves the spike test page and provides a simple REST echo endpoint for validating fetch/XHR
 * functionality on legacy iOS Safari.
 *
 * <h2>Routes</h2>
 *
 * <ul>
 *   <li>{@code GET /score/spike/} — serves the spike test HTML page (AC1–AC10)
 *   <li>{@code GET /score/spike/api/echo} — returns a JSON echo response (AC1)
 * </ul>
 *
 * <h2>Security (AC9)</h2>
 *
 * <p>The {@code /score/spike/**} path is permitted without authentication in {@link
 * de.vvwt.tm.auth.internal.SecurityConfig} to allow spike testing on devices without admin
 * credentials. This is a spike-only route — production scoring tablet routes will use device-based
 * authentication (E06S03).
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E06S01.story.md">Story
 *     E06S01</a>
 */
@Controller
public class SpikeTestController {

    /**
     * Serves the spike test page at {@code /score/spike/} (AC4, AC7).
     *
     * <p>Returns a forward to the static HTML page. The page is a self-contained HTML file with
     * inline ES5 JavaScript that exercises all browser APIs needed by the scoring tablet (fetch,
     * XHR, WebSocket, DOM, touch events).
     *
     * @return forward to the static spike test page
     */
    @GetMapping({"/score/spike", "/score/spike/"})
    public String spikeTestPage() {
        return "redirect:/score/spike/test.html";
    }

    /**
     * Simple echo endpoint for testing fetch and XHR (AC1).
     *
     * <p>Returns a JSON object with a status message and the current server timestamp. This
     * endpoint is used by the spike test page to verify that HTTP communication works on iOS 9
     * Safari.
     *
     * @return JSON map with status and timestamp
     */
    @GetMapping("/score/spike/api/echo")
    @ResponseBody
    public Map<String, Object> echo() {
        return Map.of(
                "status", "ok",
                "message", "Spike echo response",
                "timestamp", Instant.now().toString());
    }
}
