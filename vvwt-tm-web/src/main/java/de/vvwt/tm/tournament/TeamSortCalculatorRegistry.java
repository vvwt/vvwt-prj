package de.vvwt.tm.tournament;

import java.util.Set;

/**
 * Registry for {@link TeamSortCalculator} strategies.
 *
 * <p>Spring collects every {@link TeamSortCalculator} bean in the application context and passes
 * them to the implementation constructor. The registry indexes them by {@link
 * TeamSortCalculator#getKeyId()}. Known keys at runtime: {@code "team_number"}, {@code
 * "placement_group"}, {@code "group_placement"}.
 *
 * <p>DEC-58 Clause A + DEC-72: public interface in the bounded-context root package {@code
 * de.vvwt.tm.tournament}; implementation ({@code DefaultTeamSortCalculatorRegistry}) lives in
 * {@code de.vvwt.tm.tournament.internal} (DEC-35).
 *
 * <p>The E57S04 build-time interface-mandate guard ({@code InterfaceMandateGuardTest}) passes
 * because {@code DefaultTeamSortCalculatorRegistry} implements this first-party interface (AC2).
 *
 * @see TeamSortCalculator
 * @see <a href="DEC-35">DEC-35 — interface in public package, impl in .internal</a>
 * @see <a href="DEC-58">DEC-58 — universal interface mandate</a>
 * @see <a href="DEC-72">DEC-72 — machine-checked interface guard</a>
 * @see <a href="DEC-73">DEC-73 D-3 — TeamSortCalculatorRegistry</a>
 * @see <a href="E58S03">E58S03 — AC2</a>
 */
public interface TeamSortCalculatorRegistry {

    /**
     * Returns the {@link TeamSortCalculator} registered under the given key.
     *
     * @param key the registry key to look up (e.g., {@code "team_number"}, {@code
     *     "placement_group"}, {@code "group_placement"})
     * @return the matching calculator; never {@code null}
     * @throws IllegalArgumentException if no calculator is registered under {@code key}; the
     *     message contains the unknown key
     */
    TeamSortCalculator get(String key);

    /**
     * Returns an unmodifiable snapshot of all registered keys.
     *
     * @return immutable set of known keys; never {@code null}
     */
    Set<String> knownKeys();
}
