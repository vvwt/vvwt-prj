package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.RoundSnapshot;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC delegate for {@link RoundSnapshot} persistence (implementation — inventory line
 * 299).
 *
 * <p>Lives at {@code de.vvwt.tm.tournament.internal} per DEC-21 internal-package discipline. Wired
 * into {@link de.vvwt.tm.tournament.RoundSnapshotRepository} as the low-level CRUD provider.
 *
 * <p><b>Activation note:</b> Not active during E21 reconstruction-in-place phase — dual entity
 * conflict same as {@link PhaseCrudRepository}. Activated at E21S13 cutover.
 *
 * @see de.vvwt.tm.tournament.RoundSnapshotRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 299)</a>
 */
public interface RoundSnapshotCrudRepository extends CrudRepository<RoundSnapshot, UUID> {}
