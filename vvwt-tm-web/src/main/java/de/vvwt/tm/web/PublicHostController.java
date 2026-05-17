// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import de.vvwt.tm.tournament.LanHostDetector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that exposes the server's effective public host for use in QR-code URLs.
 *
 * <h2>Purpose (E49S04)</h2>
 *
 * <p>The admin SPA cannot determine the machine's LAN address from {@code window.location.origin}
 * when the operator opens the UI via loopback ({@code http://127.0.0.1:8080}). This endpoint
 * supplies the server-detected (or explicitly configured) LAN address so that {@code
 * Devices.svelte} and {@code TimerLink.svelte} can build QR-code URLs that are reachable from other
 * devices on the venue LAN.
 *
 * <h2>Response format</h2>
 *
 * <pre>
 * GET /api/public-host
 * → { "host": "192.168.1.42", "port": 8080, "scheme": "http" }
 * </pre>
 *
 * <h2>Security</h2>
 *
 * <p>Exposes only the server's own detected host — no tenant data, no auth tokens. Access is
 * governed by the existing {@code SecurityConfig} filter chain (all {@code /api/**} paths require
 * authentication in the same way as other admin API paths).
 *
 * @see LanHostDetector
 * @see <a href="../../../../../../../../docs/governance/stories/E49S04.story.md">Story E49S04</a>
 */
@RestController
@RequestMapping("/api/public-host")
public class PublicHostController {

    private final LanHostDetector lanHostDetector;
    private final int serverPort;

    public PublicHostController(
            LanHostDetector lanHostDetector, @Value("${server.port:8080}") int serverPort) {
        this.lanHostDetector = lanHostDetector;
        this.serverPort = serverPort;
    }

    /**
     * Returns the effective public host, port, and scheme for QR-URL construction.
     *
     * @return a {@link PublicHostResponse} containing the detected/configured host, the server
     *     port, and the URL scheme ({@code "http"} in V1 per Story scope)
     */
    @GetMapping
    public PublicHostResponse getPublicHost() {
        String host = lanHostDetector.detectHost();
        return new PublicHostResponse(host, serverPort, "http");
    }

    /**
     * Wire-format response for the public-host endpoint.
     *
     * @param host the effective LAN host (auto-detected or configured)
     * @param port the server port currently in use
     * @param scheme the URL scheme ({@code "http"} in V1)
     */
    public record PublicHostResponse(String host, int port, String scheme) {

        /**
         * Builds the full origin string ({@code scheme://host:port}) for use in URL construction.
         *
         * @return the origin, e.g. {@code "http://192.168.1.42:8080"}
         */
        public String origin() {
            return scheme + "://" + host + ":" + port;
        }
    }
}
