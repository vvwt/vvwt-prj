// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.List;

/**
 * Strategy interface for distributing a ranked list of teams into avatar slots.
 *
 * <p>Each implementation produces a list of {@link Team2AvatarSlot} records — one per team in the
 * ranked input — describing the {@code (groupNumber, groupPosition)} placement for each rank
 * position. No {@code teamId} is written by any distributor implementation (DEC-9, DEC-59 Clause C,
 * AC7).
 *
 * <p>The distributor operates on the abstract rank index: slot {@code i} is the target placement
 * for the team at rank {@code i} in the caller's flat ranked list. The distributor does not need the
 * team identities — it only needs the total count and the target group count (DEC-77 D-1).
 *
 * <p>DEC-58 Clause A + DEC-72: public interface in the bounded-context root package {@code
 * de.vvwt.tm.tournament}; implementations live in {@code de.vvwt.tm.tournament.internal} (DEC-35).
 *
 * @see Team2AvatarSlot
 * @see Team2AvatarDistributorRegistry
 * @see RankedTeamEntry
 * @see <a href="DEC-35">DEC-35 — interface in public package, impl in .internal</a>
 * @see <a href="DEC-58">DEC-58 — universal interface mandate</a>
 * @see <a href="DEC-73">DEC-73 D-2 — Team2AvatarDistributor strategy interface</a>
 * @see <a href="DEC-77">DEC-77 D-1 — distributor consumes flat ranked list for every phase</a>
 * @see <a href="E58S02">E58S02 — AC1, AC2, AC3</a>
 * @see <a href="E66S01">E66S01 — AC2, AC3 (interface updated)</a>
 */
public interface Team2AvatarDistributor {

    /**
     * Returns the registry key that uniquely identifies this distributor.
     *
     * <p>Known values: {@code "sequential"}, {@code "round_robin"}.
     *
     * @return the registry key; never {@code null}
     */
    String getKeyId();

    /**
     * Distributes {@code teamCount} ranked positions across {@code groupCount} groups and returns one
     * {@link Team2AvatarSlot} per rank position.
     *
     * <p>The returned list has {@code teamCount} elements. Element at index {@code i} is the target
     * slot for the team at rank {@code i} in the caller's flat ranked list. Each slot carries only
     * structural coordinates — no teamId (DEC-9, DEC-59 Clause C, AC7).
     *
     * @param teamCount the number of teams to distribute; must be ≥ 0
     * @param groupCount the number of target groups; must be ≥ 1
     * @return list of slots, one per rank position; never {@code null}
     * @throws IllegalArgumentException if {@code teamCount} is negative or {@code groupCount} is
     *     less than 1
     */
    List<Team2AvatarSlot> distribute(int teamCount, int groupCount);
}
