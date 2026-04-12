package de.vvwt.tm.domain.generator;

import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.TeamAvatar;

import java.util.List;

/**
 * Strategy interface for generating {@link Match} entities for a phase (D-27, AC1).
 *
 * <p>Each V1 match-generator implementation is a stateless Spring bean registered with a
 * unique bean ID. The phase-preparation service (E03S12) resolves the applicable generator via
 * {@code Tournament.matchGeneratorId} through the {@link MatchGeneratorRegistry}, then calls
 * this method to produce the full set of matches for a phase.
 *
 * <p>V1 implementations:
 * <ul>
 *   <li>{@code roundRobin} — every team plays every other team exactly once
 *       ({@link RoundRobinMatchGenerator})</li>
 * </ul>
 *
 * <p>Post-V1 formats (Swiss system, single-elimination, double round-robin) can be added by
 * implementing this interface and registering a new Spring bean — no schema changes required.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>The generator receives already-persisted {@link TeamAvatar} instances (ID-bearing).</li>
 *   <li>The generator returns NEW {@link Match} instances (not yet persisted). The caller
 *       (E03S12 phase-preparation service) persists them.</li>
 *   <li>The generator MUST NOT mutate the {@code Phase} or the {@link TeamAvatar} entities.</li>
 *   <li>The returned {@code List} has a deterministic ordering for the same input (AC5).</li>
 *   <li>If the avatar list has fewer than 2 entries, the generator returns an empty list (AC12).</li>
 *   <li>{@code null} phase, {@code null} avatars list, {@code null} entries in the list, or
 *       duplicate avatar IDs throw {@link IllegalArgumentException} (AC13).</li>
 * </ul>
 *
 * @see MatchGeneratorRegistry
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S09.story.md">Story E03S09</a>
 */
public interface MatchGenerator {

    /**
     * Generates a list of new, unpersisted {@link Match} entities for the given phase.
     *
     * <p>The returned matches have {@code state = OPEN(0)}, {@code lapNumber = null}, and
     * {@code fieldNumber = null}. The {@code set_limit} is derived from the tournament's
     * {@code MatchFormat.maxSets}. The caller is responsible for persisting the returned list.
     *
     * @param phase   the phase for which matches are to be generated; must not be {@code null}
     * @param avatars the already-persisted avatars participating in this phase; must not be
     *                {@code null}, must contain no {@code null} entries, must contain no
     *                duplicate {@code id} values; may be empty (returns empty list for &lt; 2)
     * @return an immutable, deterministically-ordered list of new {@link Match} entities;
     *         empty list if {@code avatars.size() < 2}
     * @throws IllegalArgumentException if {@code phase} is null, if {@code avatars} is null,
     *                                  if any entry in {@code avatars} is null, or if
     *                                  {@code avatars} contains duplicate IDs
     */
    List<Match> generate(Phase phase, List<TeamAvatar> avatars);

    /**
     * Returns the Spring bean ID of this generator, used as the key in
     * {@link MatchGeneratorRegistry}.
     *
     * <p>The returned value must match the {@code @Component} name declared on the
     * implementation class and the value stored in {@code Tournament.matchGeneratorId}.
     *
     * @return non-null, non-empty Spring bean ID (e.g., {@code "roundRobin"})
     */
    String getBeanId();
}
