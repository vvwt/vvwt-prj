// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.Team2AvatarDistributor;
import de.vvwt.tm.tournament.Team2AvatarSlot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Round-Robin team-to-avatar distributor: distributes one team per group before advancing position.
 *
 * <p>Algorithm (for team at 0-based index {@code i} of {@code N} teams into {@code groupCount}
 * groups):
 *
 * <pre>
 *   targetGroup    = (i % groupCount) + 1
 *   targetPosition = (i / groupCount) + 1
 * </pre>
 *
 * <p>Registry key: {@code "round_robin"}.
 *
 * <p>No {@code teamId} is set — slot coordinates only (DEC-9, DEC-59 Clause C, AC10).
 *
 * @see Team2AvatarDistributor
 * @see <a href="DEC-35">DEC-35 — impl in .internal</a>
 * @see <a href="DEC-73">DEC-73 D-2 — Team2AvatarDistributor strategy</a>
 * @see <a href="E58S02">E58S02 — AC3</a>
 */
@Component("tmRoundRobinTeam2AvatarDistributor")
class RoundRobinTeam2AvatarDistributor implements Team2AvatarDistributor {

    /** {@inheritDoc} */
    @Override
    public String getKeyId() {
        return "round_robin";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cycles through groups in order, placing one team per group before advancing to the next
     * position. When {@code N} is not evenly divisible by {@code groupCount}, some groups receive
     * one more team than others.
     *
     * @throws NullPointerException if {@code teams} is {@code null}
     * @throws IllegalArgumentException if {@code groupCount} is less than 1
     */
    @Override
    public List<Team2AvatarSlot> distribute(List<Team> teams, int groupCount) {
        Objects.requireNonNull(teams, "teams must not be null");
        if (groupCount < 1) {
            throw new IllegalArgumentException("groupCount must be ≥ 1, got: " + groupCount);
        }
        if (teams.isEmpty()) {
            return List.of();
        }

        int teamCount = teams.size();
        List<Team2AvatarSlot> slots = new ArrayList<>(teamCount);
        for (int i = 0; i < teamCount; i++) {
            int targetGroup = (i % groupCount) + 1;
            int targetPosition = (i / groupCount) + 1;
            slots.add(new Team2AvatarSlot(targetGroup, targetPosition));
        }
        return slots;
    }
}
