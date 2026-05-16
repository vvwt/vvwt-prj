// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import de.vvwt.tm.tournament.internal.referee.RefereeAssignmentReport;
import java.util.UUID;

/**
 * Interface for the referee-assignment service.
 *
 * <p>Assigns referees to matches within a phase by applying preference ordering and availability
 * constraints.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @since E57S01
 */
public interface RefereeAssigner {

    /**
     * Assigns referees to all unassigned matches in the given phase.
     *
     * @param phaseId the phase to process; must not be {@code null}
     * @return a summary report; never {@code null}
     * @throws NullPointerException if {@code phaseId} is null
     * @throws IllegalArgumentException if the phase does not exist
     * @throws IllegalStateException if any match has null slot coordinates
     */
    RefereeAssignmentReport assignReferees(UUID phaseId);
}
