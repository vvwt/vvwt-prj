// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

import java.time.Instant;

/**
 * Immutable value type representing a row from the {@code info_portal_state} table (AC12).
 *
 * <p>Schema per E38S09 AC12 + E45S06 Big-Bang-Reset cleanup (tenant_id column removed, DEC-50):
 *
 * <ul>
 *   <li>{@code locationId} / {@code tournamentId} — composite PK (DEC-50)
 *   <li>{@code lastPublishedSeq} — monotonic per-tournament sequence; TM assigns BEFORE posting
 *   <li>{@code tournamentToken} — opaque bearer token from tournament-registration
 *   <li>{@code perTournamentSecret} — 32-byte HMAC secret; used to derive team tokens
 *   <li>{@code lastPublishedAt} — timestamp of last successful publish; nullable
 *   <li>{@code registrationStatus} — {@code "REGISTERED"} | {@code "UNREGISTERED"} | {@code
 *       "ERROR"}
 * </ul>
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09
 *     AC12</a>
 */
public record InfoPortalStateRecord(
        String locationId,
        String tournamentId,
        long lastPublishedSeq,
        String tournamentToken,
        byte[] perTournamentSecret,
        Instant lastPublishedAt,
        String registrationStatus) {}
