// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing read-only tenant settings needed by the admin UI.
 *
 * <p>Currently exposes a single endpoint: {@code GET /api/settings} — returns a JSON object with
 * the tenant's current {@code display_name} from the per-tenant {@code tenants} table. This is used
 * by {@code TournamentForm.svelte} to pre-fill the organizer field on the tournament create form
 * (E68S01 AC1).
 *
 * <p>The {@code display_name} is read via intra-DB JDBC directly from the per-tenant DataSource,
 * consistent with the pattern used in {@link
 * de.vvwt.tm.tournament.internal.DefaultTournamentRepository} (E46S01 snapshot logic). No
 * cross-module call is needed.
 *
 * @see <a href="E68S01">E68S01 — Organizer as editable field (AC1: create form pre-fill)</a>
 * @see <a href="DEC-5">DEC-5 — per-tenant DB invariants (exactly one tenants row)</a>
 * @see <a href="DEC-40">DEC-40 — REST controllers in de.vvwt.tm.web</a>
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final JdbcTemplate jdbcTemplate;

    public SettingsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns the current tenant's display name.
     *
     * <p>Used by the tournament create form ({@code TournamentForm.svelte}) to pre-fill the
     * organizer field with the organization's current name (E68S01 AC1).
     *
     * @return 200 OK with a JSON object containing {@code organizerDefault} — the tenant {@code
     *     display_name} from the per-tenant {@code tenants} table. Falls back to an empty string if
     *     no tenants row exists (DEC-5 invariant violation; front-end degrades gracefully).
     */
    @GetMapping
    public ResponseEntity<SettingsResponse> getSettings() {
        List<String> names =
                jdbcTemplate.queryForList("SELECT display_name FROM tenants", String.class);
        String organizerDefault = names.isEmpty() ? "" : (names.get(0) != null ? names.get(0) : "");
        return ResponseEntity.ok(new SettingsResponse(organizerDefault));
    }

    /**
     * Response DTO for {@code GET /api/settings}.
     *
     * @param organizerDefault the tenant's current display name, used to pre-fill the organizer
     *     field on the tournament create form (E68S01 AC1)
     */
    public record SettingsResponse(String organizerDefault) {}
}
