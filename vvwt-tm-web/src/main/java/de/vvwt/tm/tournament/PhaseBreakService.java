// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.List;
import java.util.UUID;

/**
 * Public service interface for managing intra-phase break configuration (DEC-58 Clause A
 * operationalization).
 *
 * <p>The sole implementation is {@link de.vvwt.tm.tournament.internal.DefaultPhaseBreakService} in
 * {@code tournament.internal} per DEC-35 §1 (as amended by DEC-58).
 *
 * <p>Thin CRUD service for {@link PhaseBreak} creation and retrieval. Registered as
 * {@code @Service("tmPhaseBreakService")} on the implementation class.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultPhaseBreakService
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate for self-created Spring components</a>
 */
public interface PhaseBreakService {

    /**
     * Creates a new intra-phase break for the given phase.
     *
     * @param phaseId the phase to add the break to
     * @param afterLapNumber the lap after which the break occurs (1-based)
     * @param durationMinutes the break duration in minutes (must be &gt; 0)
     * @param label optional display label; may be null
     * @return the persisted {@link PhaseBreak}
     * @throws IllegalArgumentException if a break already exists at {@code afterLapNumber}
     */
    PhaseBreak createPhaseBreak(
            UUID phaseId, int afterLapNumber, int durationMinutes, String label);

    /**
     * Returns all phase breaks for the given phase.
     *
     * @param phaseId the phase to query
     * @return list of phase breaks; never null
     */
    List<PhaseBreak> findByPhaseId(UUID phaseId);
}
