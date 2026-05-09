package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * Public service interface for Phase preparation orchestration (DEC-58 Clause A
 * operationalization).
 *
 * <p>The sole implementation is {@link
 * de.vvwt.tm.tournament.internal.DefaultPhasePreparationService} in {@code tournament.internal} per
 * DEC-35 §1 (as amended by DEC-58).
 *
 * <p>Orchestrates Phase preparation: match generation via the {@link MatchGeneratorRegistry} (L1).
 * Called from {@link de.vvwt.tm.tournament.internal.DefaultMatchGenJobExecutor} as part of the
 * Background-Job-Pipeline (DEC-55 D-3, E51S03).
 *
 * <p>Registered as {@code @Service("tmPhasePreparationService")} on the implementation class.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultPhasePreparationService
 * @see <a href="DEC-55">DEC-55 D-3 — Background-Job-Pipeline (events-only)</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate for self-created Spring components</a>
 */
public interface PhasePreparationService {

    /**
     * Generates matches for a phase WITHOUT running referee assignment.
     *
     * <p>Idempotent: existing matches are deleted before new ones are generated.
     *
     * @param phaseId the phase UUID; must not be {@code null}
     * @param generatorKey the match generator bean id; must not be {@code null}
     * @throws IllegalArgumentException if either argument is null or the phase does not exist
     */
    void generateMatches(UUID phaseId, String generatorKey);
}
