package de.vvwt.tm.tournament;

import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftPreviewResult;
import java.util.List;
import java.util.UUID;

/**
 * Public service interface for draft phase-planning operations.
 *
 * <p>Exposed at the {@code de.vvwt.tm.tournament} root package per DEC-35 pragmatic-hexagonal
 * layout. The canonical implementation is {@code
 * de.vvwt.tm.tournament.internal.DefaultDraftService}.
 *
 * <h2>Operations</h2>
 *
 * <ul>
 *   <li>{@link #preview(DraftConfig, int)} — pure computation, no DB side effect
 *   <li>{@link #apply(UUID, DraftConfig)} — creates Phase entities via the Phase-aggregate
 *       collaborators from E21S03
 * </ul>
 *
 * @see de.vvwt.tm.tournament.internal.DefaultDraftService
 * @see DraftConfig
 * @see DraftPreviewResult
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout (interface in public package)</a>
 * @see <a href="E33S05">E33S05 — DraftService interface extraction (DEC-35 retrofit)</a>
 */
public interface DraftService {

    /**
     * Calculates a preview of what the draft will produce without creating any entities.
     *
     * @param config the draft configuration to preview; must not be {@code null}
     * @param participatingTeamCount number of participating teams
     * @return preview result; never {@code null}
     */
    DraftPreviewResult preview(DraftConfig config, int participatingTeamCount);

    /**
     * Applies the draft configuration to create Phase entities.
     *
     * <p>Fails-fast if phases already exist for the tournament (AC-DRAFT-APPLY-IDEMPOTENCY).
     *
     * @param tournamentId the tournament UUID
     * @param config the draft configuration to apply; must not be {@code null}
     * @return ordered list of created Phase IDs (one per section); never empty
     * @throws de.vvwt.tm.tournament.exceptions.DraftAlreadyAppliedException if phases already exist
     */
    List<UUID> apply(UUID tournamentId, DraftConfig config);
}
