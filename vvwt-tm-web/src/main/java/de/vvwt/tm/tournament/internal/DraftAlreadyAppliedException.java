package de.vvwt.tm.tournament.internal;

import java.util.UUID;

/**
 * Thrown by {@link DraftService#apply} when a draft has already been applied to the given
 * tournament (phases already exist).
 *
 * <p>Maps to HTTP 409 Conflict via the global exception handler.
 *
 * <p>Idempotency choice: the reconstructed {@code DraftService} fails-fast on re-apply. Legacy
 * behaviour confirmed from {@code de.vvwt.tm.domain.DraftService.applyDraft()} lines 297–305
 * (checks {@code phaseRepository.findByTournamentId()} and throws {@code ConflictException} if
 * phases already exist). The reconstructed service preserves this semantics via a typed exception.
 *
 * @see DraftService
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S07">E21S07 — AC-DRAFT-APPLY-IDEMPOTENCY</a>
 */
public class DraftAlreadyAppliedException extends RuntimeException {

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
