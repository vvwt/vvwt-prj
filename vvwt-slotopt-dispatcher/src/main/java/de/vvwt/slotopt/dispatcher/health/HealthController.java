// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.health;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal health endpoint for dispatcher availability probes (E63S08).
 *
 * <p>Returns HTTP 200 OK with a static JSON body on {@code GET /} and {@code GET /health}. This
 * endpoint satisfies the {@link de.vvwt.tm.slotopt.internal.DefaultDispatcherReachabilityService}
 * HEAD probe that TM uses to determine whether the dispatcher is reachable before routing a large-N
 * optimization to Leg 2 (DEC-49, E63S08).
 *
 * <p>Without this endpoint, the dispatcher root URL returns HTTP 404, causing TM's reachability
 * service to consider the dispatcher UNREACHABLE and fall through to Leg 3 even when the dispatcher
 * is fully operational. This was a pre-existing defect exposed by the E63S08 E2E test
 * (AC-ERR-E2E-EXPOSED-DEFECT-SURFACED).
 *
 * <p>The endpoint is intentionally minimal — no actuator dependency. The response body {@code
 * {"status":"UP"}} is informational only; the HTTP 200 status code is what the probe checks.
 */
@RestController
public class HealthController {

    /**
     * Returns HTTP 200 with {@code {"status":"UP"}} for dispatcher health probes.
     *
     * <p>Handles both {@code GET /} (base URL probe by {@link
     * de.vvwt.tm.slotopt.internal.DefaultDispatcherReachabilityService}) and {@code GET /health}
     * (conventional health check path).
     *
     * @return static health response
     */
    @GetMapping(value = {"/", "/health"})
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
