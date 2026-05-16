// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.phaselifecycle;

import java.util.UUID;

/**
 * Public port for the per-tournament job-drain loop (DEC-64 D-3, DEC-64 D-12, DEC-35, DEC-58).
 *
 * <p>The drain service is the glue layer between the {@link WorkerRegistry} (which owns the
 * executor) and the {@link PhaseLifecycleOrchestrator} (which owns the per-job pipeline). A single
 * {@code drainNext()} call: (1) claims the next pending job via {@link PhaseLifecycleJobRepository}
 * CAS, (2) delegates to {@link DefaultPhaseLifecycleOrchestrator#executeClaimed} for execution, (3)
 * repeats until the queue is empty or a concurrent worker claims the next row (multi-node CAS
 * fairness).
 *
 * <p>E55S06: the implementation was changed from a single-job tick to a FIFO loop so that a single
 * {@code afterCommit} drain hint (fired by {@link
 * de.vvwt.tm.phaselifecycle.DraftApplicationOrchestrator#applyDraft}) processes all N enqueued
 * phase jobs sequentially without requiring an external scheduler to re-trigger per phase.
 *
 * <p>The sole implementation is {@link de.vvwt.tm.phaselifecycle.internal.DefaultJobDrainService},
 * which lands in E55S04 and is updated in E55S06.
 *
 * <p>Authorizing decisions: DEC-64 D-3 (worker tick loop), DEC-64 D-12 (TX granularity per
 * orchestrator step), DEC-35 (interface in module-root), DEC-58 (universal interface mandate).
 *
 * @since E55S01
 */
public interface JobDrainService {

    /**
     * Claims and executes all pending jobs for the specified tournament in FIFO order (DEC-64 D-4).
     * Loops until the queue is empty or a concurrent worker claims the next row. If no pending job
     * exists, this method returns silently.
     *
     * @param tournamentId the tournament to drain
     */
    void drainNext(UUID tournamentId);
}
