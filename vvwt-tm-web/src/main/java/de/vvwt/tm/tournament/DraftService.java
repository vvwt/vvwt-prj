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
 *   <li>{@link #loadDraft(UUID)} — loads the current draft configuration from the tournament row
 *   <li>{@link #saveDraft(UUID, DraftConfig)} — persists the draft configuration to the tournament
 *       row and returns the saved state (E21S19, AC-TEST-GET-EMPTY-RED, AC-TEST-PUT-SUCCESS-RED)
 * </ul>
 *
 * @see de.vvwt.tm.tournament.internal.DefaultDraftService
 * @see DraftConfig
 * @see DraftPreviewResult
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout (interface in public package)</a>
 * @see <a href="E33S05">E33S05 — DraftService interface extraction (DEC-35 retrofit)</a>
 * @see <a href="E21S19">E21S19 — Restore GET + PUT mappings on /api/tournaments/{id}/draft</a>
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

    /**
     * Loads the current draft configuration for a tournament.
     *
     * <p>Returns an empty {@link DraftConfig} (no sections) if no draft has been saved yet (i.e.,
     * {@code Tournament.draftJson} is {@code null}). Returns a non-empty config if a draft was
     * previously saved via {@link #saveDraft}.
     *
     * <p>Tenant scoping is enforced at the repository layer via TenantContext (DEC-20).
     *
     * @param tournamentId the tournament UUID
     * @return the current draft config; never {@code null}; may have an empty sections list
     * @throws de.vvwt.tm.tournament.exceptions.TournamentNotFoundException if the tournament does
     *     not exist in the current tenant context
     * @see <a href="E21S19">E21S19 — AC-TEST-GET-EMPTY-RED, AC-TEST-GET-WITH-DATA-ROUND-TRIP-RED</a>
     */
    DraftConfig loadDraft(UUID tournamentId);

    /**
     * Saves the draft configuration for a tournament in {@code DRAFT} status.
     *
     * <p>Only tournaments in {@code DRAFT} status may have their draft configuration saved. Attempts
     * to save for non-{@code DRAFT} tournaments throw a 409 Conflict exception.
     *
     * <p>Tenant scoping is enforced at the repository layer via TenantContext (DEC-20).
     *
     * @param tournamentId the tournament UUID
     * @param config the draft configuration to save; must not be {@code null}
     * @return the saved draft configuration (round-trip read from persistence); never {@code null}
     * @throws de.vvwt.tm.tournament.exceptions.TournamentNotFoundException if the tournament does
     *     not exist in the current tenant context
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the tournament is not in
     *     {@code DRAFT} status
     * @see <a href="E21S19">E21S19 — AC-TEST-PUT-SUCCESS-RED, AC-TEST-PUT-NON-DRAFT-409-RED</a>
     */
    DraftConfig saveDraft(UUID tournamentId, DraftConfig config);
}
