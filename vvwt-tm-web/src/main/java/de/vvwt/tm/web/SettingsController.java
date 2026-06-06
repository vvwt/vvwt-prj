// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import de.vvwt.tm.photo.PhotoStorageConfig;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing read-only tenant settings needed by the admin UI.
 *
 * <p>Currently exposes a single endpoint: {@code GET /api/settings} — returns a JSON object with
 * the tenant's current {@code display_name} from the per-tenant {@code tenants} table, plus global
 * crop configuration for the team-photo crop step (E71S01 AC3).
 *
 * <p>The {@code display_name} is read via intra-DB JDBC directly from the per-tenant DataSource,
 * consistent with the pattern used in {@link
 * de.vvwt.tm.tournament.internal.DefaultTournamentRepository} (E46S01 snapshot logic). No
 * cross-module call is needed.
 *
 * <p>E71S01 AC3: the crop aspect ratio and max-long-edge values are resolved server-side (via
 * {@link PhotoStorageConfig}) and returned here so the frontend never hard-codes them. DEC-78 reuse
 * mandate — extending the existing endpoint, NOT adding a new config endpoint.
 *
 * @see <a href="E68S01">E68S01 — Organizer as editable field (AC1: create form pre-fill)</a>
 * @see <a href="E71S01">E71S01 — Crop team photo (AC3: server-configurable crop defaults)</a>
 * @see <a href="DEC-5">DEC-5 — per-tenant DB invariants (exactly one tenants row)</a>
 * @see <a href="DEC-40">DEC-40 — REST controllers in de.vvwt.tm.web</a>
 * @see <a href="DEC-78">DEC-78 — Clean Code reuse; no new endpoint</a>
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final JdbcTemplate jdbcTemplate;
    private final PhotoStorageConfig photoStorageConfig;

    public SettingsController(
            JdbcTemplate jdbcTemplate,
            @Qualifier("photoModuleStorageConfig") PhotoStorageConfig photoStorageConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.photoStorageConfig = photoStorageConfig;
    }

    /**
     * Returns the current tenant's display name and global crop configuration.
     *
     * <p>Used by the tournament create form ({@code TournamentForm.svelte}) to pre-fill the
     * organizer field with the organization's current name (E68S01 AC1), and by the photo crop step
     * ({@code TeamPhotos.svelte} via {@code settingsStore.ts}) to resolve the crop aspect ratio and
     * max long edge (E71S01 AC3).
     *
     * @return 200 OK with a {@link SettingsResponse}. Falls back to an empty string for {@code
     *     organizerDefault} if no tenants row exists (DEC-5 invariant violation; front-end degrades
     *     gracefully).
     */
    @GetMapping
    public ResponseEntity<SettingsResponse> getSettings() {
        List<String> names =
                jdbcTemplate.queryForList("SELECT display_name FROM tenants", String.class);
        String organizerDefault = names.isEmpty() ? "" : (names.get(0) != null ? names.get(0) : "");
        return ResponseEntity.ok(
                new SettingsResponse(
                        organizerDefault,
                        photoStorageConfig.getCropAspectRatioWidth(),
                        photoStorageConfig.getCropAspectRatioHeight(),
                        photoStorageConfig.getCropMaxLongEdge()));
    }

    /**
     * Response DTO for {@code GET /api/settings}.
     *
     * @param organizerDefault the tenant's current display name, used to pre-fill the organizer
     *     field on the tournament create form (E68S01 AC1)
     * @param cropAspectRatioWidth width component of the global crop aspect ratio (E71S01 AC3)
     * @param cropAspectRatioHeight height component of the global crop aspect ratio (E71S01 AC3)
     * @param cropMaxLongEdge maximum long-edge pixel length after downscale (E71S01 AC3)
     */
    public record SettingsResponse(
            String organizerDefault,
            int cropAspectRatioWidth,
            int cropAspectRatioHeight,
            int cropMaxLongEdge) {}
}
