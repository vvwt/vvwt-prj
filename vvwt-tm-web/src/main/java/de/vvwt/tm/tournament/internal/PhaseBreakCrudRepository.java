package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.PhaseBreak;

/**
 * Package-placement marker for {@link PhaseBreak} persistence (DEC-21, DEC-22, E21S03).
 *
 * <p>Lives at {@code de.vvwt.tm.tournament.internal} per DEC-21 internal-package discipline.
 *
 * <p><b>Activation note:</b> This interface intentionally does NOT extend {@code CrudRepository}.
 * Dual entity conflict same as {@link PhaseCrudRepository} — both the new {@code
 * de.vvwt.tm.tournament.PhaseBreak} and the legacy {@code de.vvwt.tm.domain.PhaseBreak} map to the
 * same table. Extending {@code CrudRepository} causes the new interface's bean to override the
 * legacy {@code de.vvwt.tm.domain.repo.PhaseBreakCrudRepository} bean, breaking the legacy context.
 * Package-placement marker only — {@link de.vvwt.tm.tournament.PhaseBreakRepository} uses
 * JdbcTemplate directly until E21S13 cutover.
 *
 * @see de.vvwt.tm.tournament.PhaseBreakRepository
 * @see PhaseBreak
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 295)</a>
 */
public interface PhaseBreakCrudRepository {
    // Package-placement marker only.
    // No Spring Data JDBC auto-registration — see Javadoc above.
}
