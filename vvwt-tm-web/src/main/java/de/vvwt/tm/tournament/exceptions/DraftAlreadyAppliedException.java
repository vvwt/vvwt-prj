package de.vvwt.tm.tournament.exceptions;

import java.util.UUID;

/**
 * Thrown by {@link de.vvwt.tm.tournament.DraftService#apply} when a draft has already been applied
 * to the given tournament (phases already exist).
 *
 * <p>Maps to HTTP 409 Conflict via {@link de.vvwt.tm.web.GlobalExceptionHandler}'s {@code
 * @ExceptionHandler(ConflictException.class)} handler. Extends {@link ConflictException} (E21S19)
 * so that the global handler can catch it uniformly without a dedicated per-exception mapping.
 *
 * <p>Idempotency choice: the reconstructed {@code DefaultDraftService} fails-fast on re-apply.
 * Legacy behaviour confirmed from {@code de.vvwt.tm.domain.DraftService.applyDraft()} lines 297–305
 * (checks {@code phaseRepository.findByTournamentId()} and throws {@code ConflictException} if
 * phases already exist). The reconstructed service preserves this semantics via a typed exception.
 *
 * @see de.vvwt.tm.tournament.DraftService
 * @see ConflictException
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E33S05">E33S05 — relocate to tournament.exceptions (DEC-35 retrofit)</a>
 * @see <a href="E21S07">E21S07 — AC-DRAFT-APPLY-IDEMPOTENCY</a>
 * @see <a href="E21S19">E21S19 — extends ConflictException for GlobalExceptionHandler unification</a>
 */
public class DraftAlreadyAppliedException extends ConflictException {

    /**
     * @param tournamentId the tournament for which the draft has already been applied
     * @param existingPhaseCount number of existing phases found
     */
    public DraftAlreadyAppliedException(UUID tournamentId, int existingPhaseCount) {
        super(
                "Cannot apply draft for tournament '"
                        + tournamentId
                        + "': draft already applied — "
                        + existingPhaseCount
                        + " phase(s) already exist. Apply is a one-time operation.");
    }
}
