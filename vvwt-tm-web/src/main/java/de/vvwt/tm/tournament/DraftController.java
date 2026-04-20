package de.vvwt.tm.tournament;

import de.vvwt.tm.tournament.internal.DraftAlreadyAppliedException;
import de.vvwt.tm.tournament.internal.DraftService;
import de.vvwt.tm.tournament.internal.draft.DraftBreak;
import de.vvwt.tm.tournament.internal.draft.DraftConfig;
import de.vvwt.tm.tournament.internal.draft.DraftPreviewResult;
import de.vvwt.tm.tournament.internal.draft.DraftSection;
import de.vvwt.tm.tournament.internal.dto.draft.DraftApplyResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftBreakRequest;
import de.vvwt.tm.tournament.internal.dto.draft.DraftPreviewResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftRequest;
import de.vvwt.tm.tournament.internal.dto.draft.DraftSectionRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for draft phase-planning operations (preview and apply).
 *
 * <h2>Package placement (AC-PACKAGE-D8, DEC-21)</h2>
 *
 * <p>{@code DraftController} is placed at the public API root {@code de.vvwt.tm.tournament} per
 * D-8. All internal types ({@link DraftService}, VOs, DTOs) live under {@code
 * de.vvwt.tm.tournament.internal.*} and are hidden from other modules by Spring Modulith.
 *
 * <h2>URL mapping (reconstruction-in-place)</h2>
 *
 * <p>Uses {@code /api/tm/tournaments/{tournamentId}/draft} during reconstruction-in-place to avoid
 * {@code RequestMappingHandlerMapping} ambiguity with the legacy {@code
 * de.vvwt.tm.infrastructure.web.DraftController} at {@code /api/tournaments/{...}/draft}. Mapping
 * normalizes to {@code /api/tournaments} at the E21S13 atomic cutover (DEC-32).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>POST /api/tm/tournaments/{id}/draft/preview → 200 + {@link DraftPreviewResponse}
 *       (AC-TDD-DraftController)
 *   <li>POST /api/tm/tournaments/{id}/draft/apply → 200 + {@link DraftApplyResponse}
 *       (AC-TDD-DraftController)
 * </ul>
 *
 * <h2>Security</h2>
 *
 * <p>All {@code /api/**} endpoints require HTTP Basic authentication. Tenant scoping is enforced at
 * the service/repository layer via TenantContext (DEC-20).
 *
 * <p>Inventory: E21S01 line 424. Reconstructed under {@code de.vvwt.tm.tournament} per DEC-21.
 *
 * @see DraftService
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
@RestController("tmDraftController")
@RequestMapping("/api/tm/tournaments/{tournamentId}/draft")
public class DraftController {

    private final DraftService draftService;

    /**
     * @param draftService the draft service ({@code "tmDraftService"} qualifier)
     */
    public DraftController(@Qualifier("tmDraftService") DraftService draftService) {
        this.draftService = draftService;
    }

    // -------------------------------------------------------------------------
    // POST /preview — preview draft computation
    // -------------------------------------------------------------------------

    /**
     * Calculates a preview of what the draft will produce WITHOUT creating any entities.
     *
     * @param tournamentId the tournament UUID (from path)
     * @param request the draft configuration to preview (validated via {@link Valid})
     * @return 200 OK with the preview result for each section
     */
    @PostMapping("/preview")
    public ResponseEntity<DraftPreviewResponse> previewDraft(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestBody @Valid DraftRequest request) {

        DraftConfig config = toDraftConfig(request);
        // participatingTeamCount=0 during preview — section math uses provided groupCount
        // (future: load from tournament repository; for now DraftService computes with 0)
        DraftPreviewResult result = draftService.preview(config, 0);
        return ResponseEntity.ok(DraftPreviewResponse.from(result.sections(), result.timeline()));
    }

    // -------------------------------------------------------------------------
    // POST /apply — apply draft to create Phase entities
    // -------------------------------------------------------------------------

    /**
     * Applies the draft configuration to create Phase entities.
     *
     * <p>Fails-fast if phases already exist (AC-DRAFT-APPLY-IDEMPOTENCY → 409 via global exception
     * handler).
     *
     * @param tournamentId the tournament UUID (from path)
     * @param request the draft configuration to apply (validated via {@link Valid})
     * @return 200 OK with the list of created Phase IDs
     */
    @PostMapping("/apply")
    public ResponseEntity<DraftApplyResponse> applyDraft(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestBody @Valid DraftRequest request) {

        DraftConfig config = toDraftConfig(request);
        List<UUID> phaseIds = draftService.apply(tournamentId, config);
        return ResponseEntity.ok(new DraftApplyResponse(phaseIds));
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private DraftConfig toDraftConfig(DraftRequest request) {
        List<DraftSection> sections =
                request.sections().stream().map(DraftController::toDraftSection).toList();
        return new DraftConfig(sections);
    }

    private static DraftSection toDraftSection(DraftSectionRequest req) {
        List<DraftBreak> breaks =
                req.breaks() == null
                        ? List.of()
                        : req.breaks().stream().map(DraftController::toDraftBreak).toList();
        return new DraftSection(
                req.sectionNumber(),
                req.sortType(),
                req.groupCount(),
                req.gameMode(),
                req.lapBreakTimeMinutes(),
                req.sectionBreakTimeMinutes(),
                req.lapTimeMinutes(),
                req.setQuantity(),
                breaks);
    }

    private static DraftBreak toDraftBreak(DraftBreakRequest req) {
        return new DraftBreak(req.afterLapNumber(), req.durationMinutes(), req.label());
    }

    // -------------------------------------------------------------------------
    // Exception mapping — scoped to this controller until E21S10
    // -------------------------------------------------------------------------

    /**
     * Maps {@link DraftAlreadyAppliedException} to HTTP 409 Conflict.
     *
     * <p>Scoped locally here until the tournament-module {@code GlobalExceptionHandler} arrives in
     * E21S10 (DEC-32). At E21S10 this handler will be removed and replaced by the module-level
     * {@code @RestControllerAdvice}.
     *
     * @param ex the exception thrown by {@link DraftService#apply}
     * @return 409 Conflict with the exception message as body
     */
    @ExceptionHandler(DraftAlreadyAppliedException.class)
    public ResponseEntity<String> handleDraftAlreadyApplied(DraftAlreadyAppliedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    /**
     * Maps {@link HttpMessageNotReadableException} (malformed / unparseable JSON body) to HTTP 400
     * Bad Request.
     *
     * <p>Scoped locally here until the tournament-module {@code GlobalExceptionHandler} arrives in
     * E21S10. At E21S10 this handler will be removed and replaced by the module-level
     * {@code @RestControllerAdvice}.
     *
     * @param ex the exception thrown by Spring MVC when the request body cannot be deserialized
     * @return 400 Bad Request with a generic message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Malformed or unreadable request body.");
    }
}
