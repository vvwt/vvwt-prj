package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Phase;

/**
 * Package-placement marker for {@link Phase} persistence (DEC-21, DEC-22, E21S03).
 *
 * <p>Lives at {@code de.vvwt.tm.tournament.internal} per DEC-21 internal-package discipline.
 *
 * <p><b>Activation note:</b> This interface intentionally does NOT extend {@code CrudRepository}.
 * Spring Data JDBC would attempt to scan {@code de.vvwt.tm.tournament.Phase} and the legacy {@code
 * de.vvwt.tm.domain.Phase} simultaneously — same {@code @Table("phase")} — and register the new
 * interface's bean, overriding the legacy {@code de.vvwt.tm.domain.repo.PhaseCrudRepository} bean.
 * This causes the legacy {@link de.vvwt.tm.domain.repo.PhaseRepository} to lose its delegate,
 * breaking the full application context. This interface is a package-placement marker only — {@link
 * de.vvwt.tm.tournament.PhaseRepository} uses JdbcTemplate + manual RowMapper until the E21S13
 * atomic cutover deletes the legacy entity.
 *
 * @see de.vvwt.tm.tournament.PhaseRepository
 * @see Phase
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 297)</a>
 */
public interface PhaseCrudRepository {
    // Package-placement marker only.
    // No Spring Data JDBC auto-registration — see Javadoc above.
}
