package de.vvwt.tm.tournament;

import java.util.Map;
import java.util.Set;

/**
 * Registry for {@link MatchGenerator} strategies.
 *
 * <p>Boundary-API Spring bean registry for {@link MatchGenerator} strategies (E21S08
 * reconstruction-in-place). Lives at the Modulith ROOT package {@code de.vvwt.tm.tournament} per
 * DEC-21 § Module layout — it is a boundary-API type exposed via the existing {@code
 * TournamentRulesController}.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @see MatchGenerator
 * @since E57S01
 */
public interface MatchGeneratorRegistry {

    /**
     * Returns the {@link MatchGenerator} registered under the given bean ID.
     *
     * @param beanId the registry key to look up
     * @return the matching generator (never null)
     * @throws IllegalArgumentException if no generator is registered under {@code beanId}
     */
    MatchGenerator get(String beanId);

    /**
     * Returns an unmodifiable snapshot of all registered bean IDs.
     *
     * @return immutable set of known IDs; never null
     */
    Set<String> knownIds();

    /**
     * Returns an unmodifiable view of the full registry map.
     *
     * @return immutable map from bean ID to generator instance; never null
     */
    Map<String, MatchGenerator> getAll();
}
