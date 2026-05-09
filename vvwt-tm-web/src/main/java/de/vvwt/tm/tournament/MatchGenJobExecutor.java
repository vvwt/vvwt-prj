package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * Public interface for the Match-Gen job executor component (DEC-58 Clause A operationalization).
 *
 * <p>The sole implementation is {@link de.vvwt.tm.tournament.internal.DefaultMatchGenJobExecutor}
 * in {@code tournament.internal} per DEC-35 §1 (as amended by DEC-58).
 *
 * <p>Executes the Match-Gen job for a single phase in a dedicated {@code REQUIRES_NEW} transaction.
 * Extracted from {@code MatchGenJobListener} to avoid Spring AOP transaction-commit semantics
 * leaking into the {@code @Async} caller (DEC-37 Clause B).
 *
 * @see de.vvwt.tm.tournament.internal.DefaultMatchGenJobExecutor
 * @see <a href="DEC-55">DEC-55 D-3 — Background-Job-Pipeline (events-only)</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate for self-created Spring components</a>
 */
public interface MatchGenJobExecutor {

    /**
     * Executes the match-gen + L2 round-assignment job for the given phase in a fresh transaction.
     *
     * @param tournamentId the tournament UUID (for row-lock, fieldCount, and optimize flag)
     * @param phaseId the phase UUID (for match-generation and round-assignment)
     * @throws RuntimeException if match-generation or round-assignment fails; propagates to
     *     listener for failure write
     */
    void execute(UUID tournamentId, UUID phaseId);
}
