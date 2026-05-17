// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public port for tenant-scoped {@link Phase} persistence (DEC-35, E31S01).
 *
 * <p>Hand-authored interface port per DEC-35 §2. The canonical implementation is {@link
 * de.vvwt.tm.tournament.internal.DefaultPhaseRepository}.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultPhaseRepository
 * @see <a href="DEC-35">DEC-35 — package layout: interfaces in public package</a>
 * @see <a href="E31S01">E31S01 — interface extraction</a>
 */
public interface PhaseRepository {

    /**
     * Persists a phase (upsert). Tenant scoping is enforced.
     *
     * @param phase the phase to save (id must be set by caller)
     * @return the saved phase
     */
    Phase save(Phase phase);

    /**
     * Returns the phase with the given id, scoped to the current tenant.
     *
     * @param id the phase UUID
     * @return Optional.of(phase) if found, Optional.empty() otherwise
     */
    Optional<Phase> findById(UUID id);

    /**
     * Returns all phases for the current tenant.
     *
     * @return list of phases; never null
     */
    List<Phase> findAll();

    /**
     * Returns all phases belonging to the given tournament, scoped to the current tenant.
     *
     * @param tournamentId the tournament to query
     * @return list of phases; never null
     */
    List<Phase> findByTournamentId(UUID tournamentId);

    /**
     * Returns the phase for the given tournament at the given sequence number, scoped to the
     * current tenant.
     *
     * <p>Used by {@link PhaseTransitionService} to resolve {@code fromPhase} from {@code
     * toPhase.sequenceNumber - 1} within the same tournament.
     *
     * @param tournamentId the owning tournament UUID
     * @param sequenceNumber the 1-indexed ordering of the phase within the tournament
     * @return Optional.of(phase) if found, Optional.empty() otherwise
     */
    Optional<Phase> findByTournamentIdAndSequenceNumber(UUID tournamentId, int sequenceNumber);

    /**
     * Column-scoped update: writes {@code last_job_state} for the phase with the given id WITHOUT
     * touching any other column (DEC-58 universal-interface-mandate, E55S09 H-B structural fix).
     *
     * <p>Executes: {@code UPDATE phase SET last_job_state = ? WHERE id = ?}
     *
     * <p>This is the sanctioned structural fix for the H-B stale-entity-save vulnerability
     * identified in E55S09. Callers that previously used {@code findById + setLastJobState + save}
     * MUST switch to this method to avoid overwriting concurrent operator-driven phase transitions
     * (e.g., ASSIGNED→ACTIVE) with stale entity values.
     *
     * <p>If the UPDATE affects 0 rows (phase deleted concurrently), the method is a graceful no-op
     * (consistent with the {@link
     * de.vvwt.tm.phaselifecycle.internal.DefaultPhaseLastJobStateWriter} no-op-on-deleted-phase
     * pattern per AC-ERROR-HANDLING-H-B-COLUMN-WRITE-ATOMIC).
     *
     * @param phaseId the phase UUID; must not be {@code null}
     * @param lastJobState the new value for the {@code last_job_state} column; may be {@code null}
     *     (to clear the column) but see DEC-66 D-2 for valid enum values
     * @since E55S09
     */
    void updateLastJobState(UUID phaseId, String lastJobState);

    /**
     * Deletes the phase with the given id, scoped to the current tenant.
     *
     * @param id the phase UUID
     */
    void deleteById(UUID id);
}
