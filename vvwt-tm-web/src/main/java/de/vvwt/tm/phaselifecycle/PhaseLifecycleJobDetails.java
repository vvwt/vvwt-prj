package de.vvwt.tm.phaselifecycle;

import java.util.UUID;

/**
 * Projection of a claimed {@code phase_lifecycle_job} row carrying the fields needed by
 * {@link de.vvwt.tm.phaselifecycle.internal.DefaultPhaseLifecycleOrchestrator} to execute
 * the MatchGen → L1+L2 → SlotOpt pipeline.
 *
 * <p>Not a Spring bean — a plain value object returned by {@link
 * PhaseLifecycleJobRepository#findJobDetailsById(UUID)}.
 *
 * @param jobId   the primary key of the claimed {@code phase_lifecycle_job} row
 * @param phaseId the phase to be processed
 * @param gameMode the {@code game_mode} value stored at enqueue time (e.g., {@code "roundRobin"},
 *     {@code "siegerehrung"}); used to select the correct MatchGenerator (DEC-59 Clause F)
 * @param tournamentId the parent tournament
 * @since E55S04
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-64.md">DEC-64 D-12</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-59.md">DEC-59 Clause F</a>
 */
public record PhaseLifecycleJobDetails(
        UUID jobId, UUID phaseId, String gameMode, UUID tournamentId) {}
