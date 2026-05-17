// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.List;

/**
 * Strategy interface for generating {@link Match} entities for a phase (E21S08 reconstruction).
 *
 * <p>Reconstruction-in-place counterpart of {@code de.vvwt.tm.domain.generator.MatchGenerator}
 * (inventory row 256). Lives at {@code de.vvwt.tm.tournament} — the Modulith public root package
 * per DEC-35 (promoted from {@code tournament.internal} by E33S06 — DEC-35 retrofit: types exposed
 * in the public API of {@code MatchGeneratorRegistry} must themselves live in the public package).
 * Uses new {@code de.vvwt.tm.tournament.*} types instead of the legacy {@code de.vvwt.tm.domain.*}
 * types.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>The generator receives already-persisted {@link TeamAvatar} instances (ID-bearing).
 *   <li>The generator returns NEW {@link Match} instances (not yet persisted). The caller ({@link
 *       PhasePreparationService}) persists them.
 *   <li>The generator MUST NOT mutate the {@link Phase} or the {@link TeamAvatar} entities.
 *   <li>The returned list has a deterministic ordering for the same input.
 *   <li>If the avatar list has fewer than 2 entries, the generator returns an empty list.
 *   <li>{@code null} phase, {@code null} avatars list, {@code null} entries in the list, or
 *       duplicate avatar IDs throw {@link IllegalArgumentException}.
 * </ul>
 *
 * <p>The registry key ({@link #getKeyId()}) must match the value stored in {@code
 * Tournament.matchGeneratorId} and the qualifier passed to {@link
 * MatchGeneratorRegistry#get(String)}.
 *
 * <p>Legacy {@code de.vvwt.tm.domain.generator.MatchGenerator} remains untouched until E21S13
 * atomic cutover per DEC-32.
 *
 * @see MatchGeneratorRegistry
 * @see MatchGeneratorInfo
 * @see de.vvwt.tm.tournament.internal.RoundRobinMatchGenerator
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (reconstruction-in-place)</a>
 * @see <a href="DEC-35">DEC-35 — Public package required content</a>
 * @see <a href="DEC-73">DEC-73 — D-1 getKeyId rename; D-2 isLastPhaseGenerator</a>
 * @see <a href="E21S08">E21S08 — inventory row 256</a>
 * @see <a href="E58S01">E58S01 — AC1 rename; AC2 isLastPhaseGenerator</a>
 */
public interface MatchGenerator {

    /**
     * Generates a list of new, unpersisted {@link Match} entities for the given phase.
     *
     * <p>The returned matches have {@code state = OPEN}, {@code lapNumber = null}, and {@code
     * fieldNumber = null}. The {@code setLimit} is derived from the tournament's {@link
     * de.vvwt.tm.tournament.MatchFormat#getMaxSets()} at match generation time. The caller is
     * responsible for persisting the returned list.
     *
     * @param phase the phase for which matches are to be generated; must not be {@code null}
     * @param avatars the already-persisted avatars participating in this phase; must not be {@code
     *     null}; must contain no {@code null} entries; must contain no duplicate {@code id} values;
     *     may be empty (returns empty list for fewer than 2)
     * @return an immutable, deterministically-ordered list of new {@link Match} entities; empty
     *     list if {@code avatars.size() < 2}
     * @throws IllegalArgumentException if {@code phase} is null, if {@code avatars} is null, if any
     *     entry is null, or if {@code avatars} contains duplicate IDs
     */
    List<Match> generate(Phase phase, List<TeamAvatar> avatars);

    /**
     * Returns the registry key of this generator, used as the lookup key in {@link
     * de.vvwt.tm.tournament.MatchGeneratorRegistry}.
     *
     * <p>Renamed from {@code getBeanId()} by E58S01 (DEC-73 D-1) to use domain vocabulary ("key")
     * rather than infrastructure vocabulary ("bean").
     *
     * @return non-null, non-empty key ID (e.g., {@code "roundRobin"}, {@code "awardCeremony"})
     */
    String getKeyId();

    /**
     * Returns {@code true} if this generator is intended for the last phase of a tournament (i.e.,
     * the phase that signals the end of competition and the start of the awards ceremony).
     *
     * <p>Exactly one registered generator must return {@code true} — the {@code awardCeremony}
     * generator (renamed from {@code siegerehrung} by E58S04 — DEC-73 D-7). All other generators
     * return {@code false}.
     *
     * <p>Added by E58S01 (DEC-73 D-2 capability predicate).
     *
     * @return {@code true} if this generator marks the last phase; {@code false} otherwise
     * @see MatchGeneratorInfo
     * @see <a href="DEC-73">DEC-73 D-2</a>
     */
    boolean isLastPhaseGenerator();
}
