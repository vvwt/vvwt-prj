// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import de.vvwt.tm.infoportal.InfoPortalOptInService;
import de.vvwt.tm.infoportal.InfoPortalProperties;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the per-tournament Info-Portal opt-in control (E62S02).
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>GET /api/tournaments/{tournamentId}/info-portal — returns current opt-in status
 *   <li>POST /api/tournaments/{tournamentId}/info-portal — triggers opt-in
 * </ul>
 *
 * <p>Auth: behind {@code /api/**} authentication rule (AC7 — no extra config needed).
 *
 * @since E62S02
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/info-portal")
public class InfoPortalOptInController {

    private final InfoPortalOptInService optInService;
    private final InfoPortalProperties properties;

    public InfoPortalOptInController(
            InfoPortalOptInService optInService, InfoPortalProperties properties) {
        this.optInService = optInService;
        this.properties = properties;
    }

    /**
     * Returns the current opt-in status for the given tournament.
     *
     * @return {@code { "status": "REGISTERED" | "ERROR" | "NOT_REGISTERED" | "DISABLED" }}
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> getStatus(@PathVariable UUID tournamentId) {
        UUID locationId = resolveLocationId();
        String status = optInService.getOptInStatus(locationId, tournamentId.toString());
        return ResponseEntity.ok(Map.of("status", status));
    }

    /**
     * Opts the tournament into Info-Portal publication.
     *
     * @return 200 on success, 409 if already registered, 400 if no teams, 503 if disabled
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> optIn(@PathVariable UUID tournamentId) {
        UUID locationId = resolveLocationId();
        try {
            optInService.optIn(locationId, tournamentId);
            return ResponseEntity.ok(Map.of("status", "PENDING"));
        } catch (IllegalStateException e) {
            String message = e.getMessage() != null ? e.getMessage() : "opt-in failed";
            if (message.contains("not configured") || message.contains("feature")) {
                return ResponseEntity.status(503).body(Map.of("error", message));
            } else if (message.contains("already")) {
                return ResponseEntity.status(409).body(Map.of("error", message));
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", message));
            }
        }
    }

    private UUID resolveLocationId() {
        String locationIdStr = properties.getLocationId();
        if (locationIdStr == null || locationIdStr.isBlank()) {
            return UUID.fromString("00000000-0000-0000-0000-000000000000");
        }
        try {
            return UUID.fromString(locationIdStr);
        } catch (IllegalArgumentException e) {
            // locationId may be a non-UUID string identifier — use a deterministic namespace UUID
            return UUID.nameUUIDFromBytes(
                    locationIdStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
