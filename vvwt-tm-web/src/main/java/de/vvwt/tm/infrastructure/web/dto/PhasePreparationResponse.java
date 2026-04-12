package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.PhasePreparationResult;

/**
 * REST response for {@code POST /api/phases/{phaseId}/prepare} (AC1 — E05S07).
 *
 * <p>Each step result contains a success flag and a human-readable message. The overall
 * {@code success} flag is {@code true} if and only if all three steps succeeded (AC12).
 */
public record PhasePreparationResponse(
        boolean generateMatchesSuccess,
        String  generateMatchesMessage,
        boolean optimizeSlotsSuccess,
        String  optimizeSlotsMessage,
        boolean assignRefereesSuccess,
        String  assignRefereesMessage,
        boolean success
) {
    /**
     * Converts a domain result to a REST response.
     *
     * @param result the domain result (must not be {@code null})
     * @return the REST response
     */
    public static PhasePreparationResponse from(PhasePreparationResult result) {
        return new PhasePreparationResponse(
                result.generateMatchesSuccess(),
                result.generateMatchesMessage(),
                result.optimizeSlotsSuccess(),
                result.optimizeSlotsMessage(),
                result.assignRefereesSuccess(),
                result.assignRefereesMessage(),
                result.overallSuccess()
        );
    }
}
