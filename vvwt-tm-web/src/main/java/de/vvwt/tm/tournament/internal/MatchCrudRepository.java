package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Match;

/**
 * Package-placement marker for {@link Match} persistence (DEC-21, DEC-22, E21S05).
 *
 * <p>INTERNAL to the {@code tournament} Modulith context per inventory line 291. Not a public API
 * surface — other contexts MUST NOT import this interface.
 *
 * <p><b>Activation note:</b> This interface intentionally does NOT extend {@code CrudRepository}.
 * Both {@code de.vvwt.tm.tournament.Match} and the legacy {@code de.vvwt.tm.domain.Match} are
 * mapped to {@code @Table("match")}. Spring Data JDBC's auto-registration of any {@code
 * CrudRepository} for either entity causes bean-override collisions that break the legacy {@code
 * de.vvwt.tm.domain.repo.MatchRepository}. This interface exists for package-placement compliance
 * (inventory line 291) only — {@link de.vvwt.tm.tournament.MatchRepository} uses JdbcTemplate
 * directly until the E21S13 atomic cutover deletes the legacy entity.
 *
 * @see de.vvwt.tm.tournament.MatchRepository
 * @see Match
 * @see <a href="DEC-21">DEC-21 — internal package discipline</a>
 * @see <a href="E21S05">E21S05 — inventory line 291</a>
 */
interface MatchCrudRepository {
    // Package-placement marker only.
    // No Spring Data JDBC auto-registration — see Javadoc above.
}
