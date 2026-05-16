package de.vvwt.tm.tournament;

import java.util.Set;

/**
 * Registry for {@link Team2AvatarDistributor} strategies.
 *
 * <p>Spring collects every {@link Team2AvatarDistributor} bean in the application context and
 * passes them to the implementation constructor. The registry indexes them by {@link
 * Team2AvatarDistributor#getKeyId()}. Known keys at runtime: {@code "sequential"}, {@code
 * "round_robin"}.
 *
 * <p>DEC-58 Clause A + DEC-72: public interface in the bounded-context root package {@code
 * de.vvwt.tm.tournament}; implementation ({@code DefaultTeam2AvatarDistributorRegistry}) lives in
 * {@code de.vvwt.tm.tournament.internal} (DEC-35).
 *
 * @see Team2AvatarDistributor
 * @see <a href="DEC-35">DEC-35 — interface in public package, impl in .internal</a>
 * @see <a href="DEC-58">DEC-58 — universal interface mandate</a>
 * @see <a href="DEC-73">DEC-73 D-2 — Team2AvatarDistributorRegistry</a>
 * @see <a href="E58S02">E58S02 — AC2</a>
 */
public interface Team2AvatarDistributorRegistry {

    /**
     * Returns the {@link Team2AvatarDistributor} registered under the given key.
     *
     * @param key the registry key to look up (e.g., {@code "sequential"}, {@code "round_robin"})
     * @return the matching distributor; never {@code null}
     * @throws IllegalArgumentException if no distributor is registered under {@code key}; the
     *     message contains the unknown key
     */
    Team2AvatarDistributor get(String key);

    /**
     * Returns an unmodifiable snapshot of all registered keys.
     *
     * @return immutable set of known keys; never {@code null}
     */
    Set<String> knownKeys();
}
