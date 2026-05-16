// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.phaselifecycle;

import java.util.UUID;

/**
 * Interface for the T-job-step-A executor of the Saga-Orchestrator drain pipeline (DEC-64 D-12).
 *
 * <p>T-job-step-A runs MatchGen (L1) + round-assignment (L2) + PENDING→PREPARED transition in one
 * {@code REQUIRES_NEW} transaction. Extracted as a separate Spring bean so that {@link
 * de.vvwt.tm.phaselifecycle.PhaseLifecycleOrchestrator} can call it through the AOP proxy boundary.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @since E57S01
 */
public interface OrchestratorStepAExecutor {

    /**
     * Executes T-job-step-A for the given phase in a fresh {@code REQUIRES_NEW} transaction.
     *
     * @param tournamentId the tournament (for row-lock + fieldCount)
     * @param phaseId the phase to process
     * @param gameMode the generator key (from job row, set at enqueue time)
     * @throws RuntimeException on any failure — T-step-A TX rolls back; job stays RUNNING
     */
    void executeStepA(UUID tournamentId, UUID phaseId, String gameMode);
}
