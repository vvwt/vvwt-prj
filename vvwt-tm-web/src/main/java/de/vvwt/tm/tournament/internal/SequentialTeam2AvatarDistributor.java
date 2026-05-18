// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Team2AvatarDistributor;
import de.vvwt.tm.tournament.Team2AvatarSlot;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Sequential team-to-avatar distributor: fills Group 1 fully before Group 2.
 *
 * <p>Algorithm (for rank index {@code i} of {@code teamCount} teams into {@code groupCount}
 * groups):
 *
 * <pre>
 *   positionsPerGroup = ceil(teamCount / groupCount)
 *   targetGroup       = (i / positionsPerGroup) + 1
 *   targetPosition    = (i % positionsPerGroup) + 1
 * </pre>
 *
 * <p>Registry key: {@code "sequential"} (default distribution mode, AC-TEST-DEFAULT-IS-SEQUENTIAL).
 *
 * <p>No {@code teamId} is set — slot coordinates only (DEC-9, DEC-59 Clause C, AC7).
 *
 * @see Team2AvatarDistributor
 * @see <a href="DEC-35">DEC-35 — impl in .internal</a>
 * @see <a href="DEC-73">DEC-73 D-2 — Team2AvatarDistributor strategy</a>
 * @see <a href="DEC-77">DEC-77 D-1 — distribute(int, int) for every phase</a>
 * @see <a href="E58S02">E58S02 — AC3</a>
 * @see <a href="E66S01">E66S01 — AC3 (interface updated to distribute(int, int))</a>
 */
@Component("tmSequentialTeam2AvatarDistributor")
class SequentialTeam2AvatarDistributor implements Team2AvatarDistributor {

    /** {@inheritDoc} */
    @Override
    public String getKeyId() {
        return "sequential";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fills Group 1 fully ({@code positionsPerGroup} slots) before moving to Group 2, and so on.
     * When {@code teamCount} is not evenly divisible by {@code groupCount}, the last group receives
     * fewer teams than the others.
     *
     * @throws IllegalArgumentException if {@code teamCount} is negative or {@code groupCount} is
     *     less than 1
     */
    @Override
    public List<Team2AvatarSlot> distribute(int teamCount, int groupCount) {
        if (teamCount < 0) {
            throw new IllegalArgumentException("teamCount must be ≥ 0, got: " + teamCount);
        }
        if (groupCount < 1) {
            throw new IllegalArgumentException("groupCount must be ≥ 1, got: " + groupCount);
        }
        if (teamCount == 0) {
            return List.of();
        }

        // positionsPerGroup = ceil(teamCount / groupCount)
        int positionsPerGroup = (teamCount + groupCount - 1) / groupCount;

        List<Team2AvatarSlot> slots = new ArrayList<>(teamCount);
        for (int i = 0; i < teamCount; i++) {
            int targetGroup = (i / positionsPerGroup) + 1;
            int targetPosition = (i % positionsPerGroup) + 1;
            slots.add(new Team2AvatarSlot(targetGroup, targetPosition));
        }
        return slots;
    }
}
