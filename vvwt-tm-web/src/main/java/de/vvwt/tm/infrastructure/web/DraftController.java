package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.DraftService;
import de.vvwt.tm.domain.draft.DraftBreak;
import de.vvwt.tm.domain.draft.DraftConfig;
import de.vvwt.tm.domain.draft.DraftPreviewResult;
import de.vvwt.tm.domain.draft.DraftSection;
import de.vvwt.tm.infrastructure.web.dto.DraftApplyResponse;
import de.vvwt.tm.infrastructure.web.dto.DraftBreakRequest;
import de.vvwt.tm.infrastructure.web.dto.DraftPreviewResponse;
import de.vvwt.tm.infrastructure.web.dto.DraftRequest;
import de.vvwt.tm.infrastructure.web.dto.DraftResponse;
import de.vvwt.tm.infrastructure.web.dto.DraftSectionRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for draft configuration, preview, and apply operations (E05S06).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>PUT  /api/tournaments/{tournamentId}/draft         — save draft (AC2)</li>
 *   <li>GET  /api/tournaments/{tournamentId}/draft         — get draft (AC3)</li>
 *   <li>POST /api/tournaments/{tournamentId}/draft/preview — preview draft (AC4)</li>
 *   <li>POST /api/tournaments/{tournamentId}/draft/apply   — apply draft (AC5)</li>
 * </ul>
 *
 * <h2>Error handling</h2>
 * <p>All exceptions are mapped to structured JSON responses by {@link GlobalExceptionHandler}:
 * <ul>
 *   <li>{@link java.util.NoSuchElementException}                              → 404</li>
 *   <li>{@link ConflictException}                                             → 409</li>
 *   <li>{@link org.springframework.web.bind.MethodArgumentNotValidException}  → 400</li>
 *   <li>{@link IllegalArgumentException}                                      → 400</li>
 * </ul>
 *
 * <h2>Security (AC14)</h2>
 * <p>All /api/** endpoints require HTTP Basic authentication. Tenant scoping is enforced at
 * the repository layer via TenantContext (DEC-5, DEC-17).
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story E05S06</a>
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/draft")
public class DraftController {

    private final DraftService draftService;

    public DraftController(DraftService draftService) {
        this.draftService = draftService;
    }

    // -------------------------------------------------------------------------
    // AC2 — PUT /api/tournaments/{tournamentId}/draft
    // -------------------------------------------------------------------------

    /**
     * Saves the draft configuration for a tournament.
     *
     * <p>Only DRAFT-status tournaments may be saved. Validates all section fields.
     *
     * @param tournamentId the tournament UUID (from path)
     * @param request      the draft configuration to save (validated via {@link Valid})
     * @return 200 OK with the saved draft configuration
     */
    @PutMapping
    public ResponseEntity<DraftResponse> saveDraft(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestBody @Valid DraftRequest request) {

        DraftConfig config = toDraftConfig(request);
        DraftConfig saved = draftService.saveDraft(tournamentId, config);
        return ResponseEntity.ok(DraftResponse.from(saved));
    }

    // -------------------------------------------------------------------------
    // AC3 — GET /api/tournaments/{tournamentId}/draft
    // -------------------------------------------------------------------------

    /**
     * Returns the current draft configuration for a tournament.
     *
     * <p>Returns an empty sections array if no draft has been configured yet.
     *
     * @param tournamentId the tournament UUID (from path)
     * @return 200 OK with the draft configuration (may have empty sections list)
     */
    @GetMapping
    public ResponseEntity<DraftResponse> getDraft(
            @PathVariable("tournamentId") UUID tournamentId) {

        DraftConfig config = draftService.getDraft(tournamentId);
        return ResponseEntity.ok(DraftResponse.from(config));
    }

    // -------------------------------------------------------------------------
    // AC4 — POST /api/tournaments/{tournamentId}/draft/preview
    // -------------------------------------------------------------------------

    /**
     * Calculates a preview of what the draft will produce WITHOUT creating any entities.
     *
     * <p>Returns phase/group/match/time estimates for each section in the draft.
     *
     * @param tournamentId the tournament UUID (from path)
     * @return 200 OK with the preview result for each section
     */
    @PostMapping("/preview")
    public ResponseEntity<DraftPreviewResponse> previewDraft(
            @PathVariable("tournamentId") UUID tournamentId) {

        DraftPreviewResult result = draftService.previewDraft(tournamentId);
        return ResponseEntity.ok(DraftPreviewResponse.from(result.sections(), result.timeline()));
    }

    // -------------------------------------------------------------------------
    // AC5 — POST /api/tournaments/{tournamentId}/draft/apply
    // -------------------------------------------------------------------------

    /**
     * Applies the draft configuration to create Phase entities and distribute Phase 1 TeamAvatars.
     *
     * <p>Transitions the tournament status from DRAFT to PLANNED (AC7).
     * The draft can no longer be modified after this call.
     *
     * @param tournamentId the tournament UUID (from path)
     * @return 200 OK with the list of created Phase IDs
     */
    @PostMapping("/apply")
    public ResponseEntity<DraftApplyResponse> applyDraft(
            @PathVariable("tournamentId") UUID tournamentId) {

        List<UUID> phaseIds = draftService.applyDraft(tournamentId);
        return ResponseEntity.ok(new DraftApplyResponse(phaseIds));
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    /**
     * Maps the REST request DTO to the domain {@link DraftConfig}.
     *
     * @param request the validated request DTO
     * @return the domain object
     */
    private DraftConfig toDraftConfig(DraftRequest request) {
        List<DraftSection> sections = request.sections().stream()
                .map(DraftController::toDraftSection)
                .toList();
        return new DraftConfig(sections);
    }

    private static DraftSection toDraftSection(DraftSectionRequest req) {
        List<DraftBreak> breaks = req.breaks() == null ? List.of() :
                req.breaks().stream().map(DraftController::toDraftBreak).toList();
        return new DraftSection(
                req.sectionNumber(),
                req.sortType(),
                req.groupCount(),
                req.gameMode(),
                req.lapBreakTimeMinutes(),
                req.sectionBreakTimeMinutes(),
                req.lapTimeMinutes(),
                req.setQuantity(),
                breaks
        );
    }

    private static DraftBreak toDraftBreak(DraftBreakRequest req) {
        return new DraftBreak(req.afterLapNumber(), req.durationMinutes(), req.label());
    }
}
