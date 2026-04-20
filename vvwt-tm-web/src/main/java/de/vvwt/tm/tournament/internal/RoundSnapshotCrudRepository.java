package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.RoundSnapshot;

/**
 * Package-placement marker for {@link RoundSnapshot} persistence (DEC-21, DEC-22, E21S03).
 *
 * <p>Lives at {@code de.vvwt.tm.tournament.internal} per DEC-21 internal-package discipline.
 *
 * <p><b>Activation note:</b> This interface intentionally does NOT extend {@code CrudRepository}.
 * Dual entity conflict same as {@link PhaseCrudRepository} — both the new {@code
 * de.vvwt.tm.tournament.RoundSnapshot} and the legacy {@code de.vvwt.tm.domain.RoundSnapshot} map
 * to the same table. Package-placement marker only — {@link
 * de.vvwt.tm.tournament.RoundSnapshotRepository} uses JdbcTemplate directly until E21S13 cutover.
 *
 * @see de.vvwt.tm.tournament.RoundSnapshotRepository
 * @see RoundSnapshot
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 299)</a>
 */
public interface RoundSnapshotCrudRepository {
    // Package-placement marker only.
    // No Spring Data JDBC auto-registration — see Javadoc above.
}
