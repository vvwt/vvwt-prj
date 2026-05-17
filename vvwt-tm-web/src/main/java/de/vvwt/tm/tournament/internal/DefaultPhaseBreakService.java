// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseBreakService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Domain service for managing intra-phase break configuration (implementation — inventory line
 * 179).
 *
 * <p>Thin CRUD service for {@link PhaseBreak} creation and retrieval. This is the scoped
 * reconstruction-in-place implementation for E21S03 — providing the boundary-API contract that
 * {@code timer/TimerDataService} and other consumers depend on.
 *
 * <p>Scope: creation with duplicate-lap-boundary detection, and retrieval by phase. The full
 * validation of lap range (afterLapNumber &lt; totalLaps) requires Match data from E21S05 — that
 * enrichment is deferred to post-E21S05 integration. For E21S03, the duplicate-boundary guard is
 * the primary invariant.
 *
 * <p>Lives at {@code de.vvwt.tm.tournament.internal} per DEC-21 internal-package discipline
 * (inventory line 179: {@code tournament-core} classification).
 *
 * @see PhaseBreak
 * @see PhaseBreakRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate for self-created Spring components</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 179)</a>
 */
@Service("tmPhaseBreakService")
public class DefaultPhaseBreakService implements PhaseBreakService {

    private final PhaseBreakRepository phaseBreakRepository;

    public DefaultPhaseBreakService(PhaseBreakRepository phaseBreakRepository) {
        this.phaseBreakRepository = phaseBreakRepository;
    }

    /**
     * Creates a new intra-phase break for the given phase.
     *
     * <p>Validation: duplicate-lap-boundary detection — at most one break per lap boundary per
     * phase. Throws {@link IllegalArgumentException} if a break already exists at {@code
     * afterLapNumber} in the phase.
     *
     * <p>Note: lap-range validation ({@code afterLapNumber} &lt; totalLaps) requires Match data
     * from E21S05 and is deferred to post-E21S05 integration (PhaseBreakService enrichment story).
     *
     * @param phaseId the phase to add the break to
     * @param afterLapNumber the lap after which the break occurs (1-based)
     * @param durationMinutes the break duration in minutes (must be &gt; 0)
     * @param label optional display label (e.g., "Mittagspause"); may be null
     * @return the persisted {@link PhaseBreak}
     * @throws IllegalArgumentException if a break already exists at {@code afterLapNumber} in the
     *     phase (duplicate detection)
     */
    @Override
    public PhaseBreak createPhaseBreak(
            UUID phaseId, int afterLapNumber, int durationMinutes, String label) {
        // Duplicate detection — at most one break per lap boundary per phase
        phaseBreakRepository
                .findByPhaseIdAndAfterLapNumber(phaseId, afterLapNumber)
                .ifPresent(
                        existing -> {
                            throw new IllegalArgumentException(
                                    "duplicate: a phase break already exists"
                                            + " at lap boundary "
                                            + afterLapNumber
                                            + " for phase "
                                            + phaseId);
                        });

        PhaseBreak phaseBreak =
                new PhaseBreak(UUID.randomUUID(), phaseId, afterLapNumber, durationMinutes, label);
        return phaseBreakRepository.save(phaseBreak);
    }

    /**
     * Returns all phase breaks for the given phase, scoped to the active tenant.
     *
     * @param phaseId the phase to query
     * @return list of phase breaks for the phase; never null
     */
    @Override
    public List<PhaseBreak> findByPhaseId(UUID phaseId) {
        return phaseBreakRepository.findByPhaseId(phaseId);
    }
}
