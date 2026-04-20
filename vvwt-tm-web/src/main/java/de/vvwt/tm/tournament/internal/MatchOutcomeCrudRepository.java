package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.MatchOutcome;

/**
 * Package-placement marker for {@link MatchOutcome} persistence (DEC-21, DEC-22, E21S05).
 *
 * <p>INTERNAL to the {@code tournament} Modulith context per inventory line 292. Not a public API
 * surface.
 *
 * <p><b>Activation note:</b> This interface intentionally does NOT extend {@code CrudRepository}.
 * Both {@code de.vvwt.tm.tournament.MatchOutcome} and the legacy {@code
 * de.vvwt.tm.domain.MatchOutcome} are mapped to {@code @Table("match_outcome")}. Spring Data JDBC's
 * auto-registration of any {@code CrudRepository} for either entity causes bean-override collisions
 * that break the legacy {@code de.vvwt.tm.domain.repo.MatchOutcomeRepository}. This interface
 * exists for package-placement compliance (inventory line 292) only — {@link
 * de.vvwt.tm.tournament.MatchOutcomeRepository} uses JdbcTemplate directly until the E21S13 atomic
 * cutover deletes the legacy entity.
 *
 * @see de.vvwt.tm.tournament.MatchOutcomeRepository
 * @see MatchOutcome
 * @see <a href="DEC-21">DEC-21 — internal package discipline</a>
 * @see <a href="E21S05">E21S05 — inventory line 292</a>
 */
interface MatchOutcomeCrudRepository {
    // Package-placement marker only.
    // No Spring Data JDBC auto-registration — see Javadoc above.
}
