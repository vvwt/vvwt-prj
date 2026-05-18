// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * Immutable record representing one team in a flat ranked list produced by a {@link
 * TeamSortCalculator}.
 *
 * <p>Carries team identity and display fields, plus optional source-phase structural coordinates
 * (null for Phase 1 — no predecessor phase). The list ordering encodes the rank: index 0 is the
 * highest-ranked team.
 *
 * <p>No {@code (groupNumber, groupPosition)} distribution is embedded here — distribution is the
 * responsibility of {@link Team2AvatarDistributor} (DEC-77 D-1). No {@code teamId} is written to
 * the database by the calculator (DEC-59 Clause C / DEC-9 / AC7).
 *
 * @param teamId the team's UUID (for proposal-building and operator-confirmation)
 * @param teamNumber the team's registration number (display field)
 * @param description the team's description / name (display field)
 * @param sourceGroupNumber the team's group number in the previous phase; {@code null} for Phase 1
 * @param sourceGroupPosition the team's position within its group in the previous phase; {@code
 *     null} for Phase 1
 * @see TeamSortCalculator
 * @see Team2AvatarDistributor
 * @see <a href="DEC-77">DEC-77 D-1 — flat ranked list, then distribute</a>
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity</a>
 * @see <a href="DEC-59">DEC-59 Clause C — teamId written only by operator-confirmation handler</a>
 * @see <a href="E66S01">E66S01 — AC2</a>
 */
public record RankedTeamEntry(
        UUID teamId,
        int teamNumber,
        String description,
        Integer sourceGroupNumber,
        Integer sourceGroupPosition) {}
