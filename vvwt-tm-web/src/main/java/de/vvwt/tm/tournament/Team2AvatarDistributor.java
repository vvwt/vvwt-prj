package de.vvwt.tm.tournament;

import java.util.List;

/**
 * Strategy interface for distributing teams into avatar slots.
 *
 * <p>Each implementation produces a list of {@link Team2AvatarSlot} records — one per team —
 * describing the {@code (groupNumber, groupPosition)} placement for each team. No {@code teamId} is
 * written by any distributor implementation (DEC-9, DEC-59 Clause C, AC10).
 *
 * <p>DEC-58 Clause A + DEC-72: public interface in the bounded-context root package {@code
 * de.vvwt.tm.tournament}; implementations live in {@code de.vvwt.tm.tournament.internal} (DEC-35).
 *
 * @see Team2AvatarSlot
 * @see Team2AvatarDistributorRegistry
 * @see <a href="DEC-35">DEC-35 — interface in public package, impl in .internal</a>
 * @see <a href="DEC-58">DEC-58 — universal interface mandate</a>
 * @see <a href="DEC-73">DEC-73 D-2 — Team2AvatarDistributor strategy interface</a>
 * @see <a href="E58S02">E58S02 — AC1, AC2, AC3</a>
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
     * Distributes the given teams across {@code groupCount} groups and returns one {@link
     * Team2AvatarSlot} per team.
     *
     * <p>The returned list has the same size as {@code teams}. Each slot carries only structural
     * coordinates — no teamId (DEC-9, DEC-59 Clause C, AC10).
     *
     * @param teams the participating teams; must not be {@code null}; may be empty
     * @param groupCount the number of target groups; must be ≥ 1
     * @return list of slots, one per team, in the same order as the input list; never {@code null}
     * @throws NullPointerException if {@code teams} is {@code null}
     * @throws IllegalArgumentException if {@code groupCount} is less than 1
     */
    List<Team2AvatarSlot> distribute(List<Team> teams, int groupCount);
}
