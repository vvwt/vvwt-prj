package de.vvwt.tm.web;

import de.vvwt.tm.scoring.PartialScoreInput;
import de.vvwt.tm.scoring.ScoreEntryResult;
import de.vvwt.tm.scoring.ScoreEntryService;
import de.vvwt.tm.scoring.SetSubmitInput;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the scoring-tablet API endpoints in the {@code web} Modulith module (E22S09,
 * DEC-40 Clause A, DEC-40 Clause D Q-1a TDD-reconstruction).
 *
 * <p>Reconstructed via RED-first TDD from legacy {@code
 * de.vvwt.tm.infrastructure.score.ScoreApiController}. URL mappings, HTTP verbs, response status
 * codes, and JSON wire format are preserved byte-equivalent to the legacy surface per
 * AC-URL-MAP-PARITY (C-14) and AC-JACKSON-WIRE-PARITY (Q-6).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>{@code GET /api/score/match?field={n}&token={t}} — resolve active match for a field
 *   <li>{@code POST /api/score/partial} — partial (live) score update
 *   <li>{@code POST /api/score/submit} — final set result submission
 * </ul>
 *
 * <h2>Security (AC-SECURITY-DEVICE-TOKEN)</h2>
 *
 * <p>All endpoints are accessible without admin session authentication (DEC-12,
 * AC-NO-ADMIN-AUTH-REGRESSION). Device identity is validated by {@link ScoreEntryService} via the
 * {@code token} parameter or the {@code deviceToken} field inside the request body. Invalid or
 * absent tokens → {@link de.vvwt.tm.tournament.exceptions.UnauthorizedException} → HTTP 401. Valid
 * token but wrong field → {@link de.vvwt.tm.tournament.exceptions.ForbiddenException} → HTTP 403.
 *
 * <h2>Error handling (AC-GLOBAL-EXCEPTION-HANDLER-REACH)</h2>
 *
 * <p>Exceptions are handled globally by {@link
 * de.vvwt.tm.web.GlobalExceptionHandler} which covers {@code de.vvwt.tm.web}
 * via its {@code @ControllerAdvice(basePackages = {..., "de.vvwt.tm.web"})} declaration:
 *
 * <ul>
 *   <li>{@link de.vvwt.tm.tournament.exceptions.UnauthorizedException} → HTTP 401
 *   <li>{@link de.vvwt.tm.tournament.exceptions.ForbiddenException} → HTTP 403
 *   <li>{@link de.vvwt.tm.tournament.exceptions.ValidationException} → HTTP 400 with message
 *   <li>{@link org.springframework.web.bind.MethodArgumentNotValidException} ({@code @Valid}
 *       failure) → HTTP 400 with structured error body
 * </ul>
 *
 * <h2>DTO decision (AC-DTO-DECISION-JUSTIFIED — DEC-40 Clause B)</h2>
 *
 * <p>No web-internal DTO is needed for the response body. {@link ScoreEntryResult} (scoring-public
 * record from E22S06) serves as the Jackson wire format directly. Its field names are identical to
 * the legacy {@code MatchScoreResponse}: {@code matchId}, {@code fieldNumber}, {@code lapNumber},
 * {@code setIndex}, {@code team1Name}, {@code team2Name}, {@code refereeTeamName}, {@code
 * team1Points}, {@code team2Points}. DEC-40 Clause B conditions (a) field omission, (d) field
 * aliasing do NOT fire — none of the 9 fields are omitted or aliased. "scoring-public record used
 * directly" is the governing decision.
 *
 * <p>Request bodies ({@link PartialScoreInput}, {@link SetSubmitInput}) are scoring-public records
 * (E22S06 + E22S12). They carry jakarta-validation annotations directly; no web-internal DTO
 * conversion layer is required (AC-DTO-VALIDATION-BOUNDARY-VERIFIED).
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-21 — imports ONLY from {@code de.vvwt.tm.scoring.*} (public interface + public DTOs).
 *       No {@code scoring.internal.*}, no {@code tournament.internal.*}, no {@code
 *       infrastructure.score.*} imports (AC-NO-INTERNAL-IMPORTS).
 *   <li>DEC-22 — RED-first: both test files committed (RED) before this commit (GREEN).
 *   <li>DEC-35 — controller at {@code de.vvwt.tm.web} root per DEC-40 Clause A.
 *   <li>DEC-40 Clause A — controller in dedicated {@code web} module; bounded contexts do not hold
 *       REST controllers.
 * </ul>
 *
 * @see ScoreEntryService
 * @see ScoreEntryResult
 * @see PartialScoreInput
 * @see SetSubmitInput
 * @see <a href="DEC-21">DEC-21 — Spring Modulith module boundaries</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S09">E22S09 — TDD-reconstruct ScoreApiController</a>
 */
@RestController("tmScoreApiController")
@RequestMapping("/api/score")
public class ScoreApiController {

    private final ScoreEntryService scoreEntryService;

    public ScoreApiController(ScoreEntryService scoreEntryService) {
        this.scoreEntryService = scoreEntryService;
    }

    // -------------------------------------------------------------------------
    // GET /api/score/match — match resolution (AC-URL-MAP-PARITY C-14, AC1/AC9)
    // -------------------------------------------------------------------------

    /**
     * Returns the active match display data for the given field and device token.
     *
     * <p>Validates the device token and field ownership via {@link ScoreEntryService}. Returns 200
     * with the match data if an active match exists, or 204 No Content if no match is currently
     * scheduled for the field (AC9 no-match state — tablet renders "waiting for match" UI).
     *
     * <p>Query parameter name: {@code token} (preserved verbatim from legacy controller per
     * AC-URL-MAP-PARITY wire-parity invariant — C-14).
     *
     * @param field the court field number (1-based)
     * @param deviceToken the tablet's opaque device token
     * @return 200 with {@link ScoreEntryResult} body, or 204 No Content if no active match
     * @throws de.vvwt.tm.tournament.exceptions.UnauthorizedException if the device token is invalid
     *     (→ HTTP 401 via GlobalExceptionHandler)
     * @throws de.vvwt.tm.tournament.exceptions.ForbiddenException if the device is assigned to a
     *     different field (→ HTTP 403 via GlobalExceptionHandler)
     */
    @GetMapping("/match")
    public ResponseEntity<ScoreEntryResult> getMatch(
            @RequestParam("field") int field, @RequestParam("token") String deviceToken) {
        Optional<ScoreEntryResult> result = scoreEntryService.getMatchForField(field, deviceToken);
        return result.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    // -------------------------------------------------------------------------
    // POST /api/score/partial — live score update (AC5)
    // -------------------------------------------------------------------------

    /**
     * Accepts a partial (in-progress) score update and broadcasts it via WebSocket.
     *
     * <p>The device token (inside {@code request.deviceToken()}) is validated by {@link
     * ScoreEntryService}. The score is NOT persisted — partial updates are transient live-score
     * signals broadcast to connected WebSocket subscribers. Returns 204 No Content on success.
     *
     * @param request the partial score input (NOT NULL; jakarta-validation applied per {@link
     *     PartialScoreInput} record annotations — AC-DTO-VALIDATION-BOUNDARY-VERIFIED)
     * @return 204 No Content
     */
    @PostMapping("/partial")
    public ResponseEntity<Void> partialScore(@Valid @RequestBody PartialScoreInput request) {
        scoreEntryService.handlePartialScore(request);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // POST /api/score/submit — final set result (AC7/AC8)
    // -------------------------------------------------------------------------

    /**
     * Submits a final set result and triggers cascade recompute (AC7, AC8).
     *
     * <p>The device token (inside {@code request.deviceToken()}) is validated by {@link
     * ScoreEntryService}, which delegates cascade recompute to {@link
     * de.vvwt.tm.scoring.ScoringService#registerMatchResult} (DEC-37 Clause B pessimistic-lock
     * pattern). Returns 204 No Content on success.
     *
     * @param request the set submission input (NOT NULL; jakarta-validation applied per {@link
     *     SetSubmitInput} record annotations — AC-DTO-VALIDATION-BOUNDARY-VERIFIED)
     * @return 204 No Content
     */
    @PostMapping("/submit")
    public ResponseEntity<Void> submitSet(@Valid @RequestBody SetSubmitInput request) {
        scoreEntryService.submitSetResult(request);
        return ResponseEntity.noContent().build();
    }
}
