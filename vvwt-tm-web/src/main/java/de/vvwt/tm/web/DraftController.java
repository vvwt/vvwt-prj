package de.vvwt.tm.web;

import de.vvwt.tm.tournament.DraftService;
import de.vvwt.tm.tournament.draft.DraftBreak;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftPreviewResult;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.exceptions.DraftAlreadyAppliedException;
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
 * <p>Relocated whole-class from {@code de.vvwt.tm.tournament.DraftController} into {@code
 * de.vvwt.tm.web} per DEC-40 Clause A Q-1b whole-class relocation (DEC-22 §refactor-clause). URL
 * mapping {@code /api/tournaments/{tournamentId}/draft} preserved verbatim (C-14).
 * {@code @Qualifier("tmDraftService")} preserved verbatim (C-12).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>POST /api/tournaments/{id}/draft/preview → 200 + {@link DraftPreviewResponse}
 *   <li>POST /api/tournaments/{id}/draft/apply → 200 + {@link DraftApplyResponse}
 * </ul>
 *
 * <h2>Security</h2>
 *
 * <p>All {@code /api/**} endpoints require HTTP Basic authentication. Tenant scoping is enforced at
 * the service/repository layer via TenantContext (DEC-20).
 *
 * @see DraftService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law, Q-1b refactor-clause</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S08">E22S08 — relocate to de.vvwt.tm.web</a>
 */
@RestController("tmDraftController")
@RequestMapping("/api/tournaments/{tournamentId}/draft")
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
        DraftPreviewResult result = draftService.preview(config, 0);
        return ResponseEntity.ok(DraftPreviewResponse.from(result.sections(), result.timeline()));
    }

    // -------------------------------------------------------------------------
    // POST /apply — apply draft to create Phase entities
    // -------------------------------------------------------------------------

    /**
     * Applies the draft configuration to create Phase entities.
     *
     * <p>Fails-fast if phases already exist (409 via local exception handler).
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
    // Exception mapping
    // -------------------------------------------------------------------------

    /**
     * Maps {@link DraftAlreadyAppliedException} to HTTP 409 Conflict.
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
     * @param ex the exception thrown by Spring MVC when the request body cannot be deserialized
     * @return 400 Bad Request with a generic message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Malformed or unreadable request body.");
    }
}
