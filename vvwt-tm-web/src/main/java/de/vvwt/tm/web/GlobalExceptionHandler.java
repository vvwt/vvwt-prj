package de.vvwt.tm.web;

import de.vvwt.tm.domain.audio.AudioFormatException;
import de.vvwt.tm.domain.audio.AudioSizeLimitException;
import de.vvwt.tm.domain.audio.AudioStorageException;
import de.vvwt.tm.domain.timer.InvalidTimerUrlException;
import de.vvwt.tm.domain.timer.NoActiveTournamentException;
import de.vvwt.tm.infrastructure.display.NoActivePhaseException;
import de.vvwt.tm.photo.PhotoFormatException;
import de.vvwt.tm.photo.PhotoSizeException;
import de.vvwt.tm.photo.PhotoStorageException;
import de.vvwt.tm.tournament.ApiErrorResponse;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import de.vvwt.tm.tournament.exceptions.ForbiddenException;
import de.vvwt.tm.tournament.exceptions.TooManyRequestsException;
import de.vvwt.tm.tournament.exceptions.TournamentNotFoundException;
import de.vvwt.tm.tournament.exceptions.UnauthorizedException;
import de.vvwt.tm.tournament.exceptions.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global exception handler for the {@code tournament} bounded context (E21S10,
 * AC-TDD-GlobalExceptionHandler, AC-PKG-GlobalExceptionHandler, inventory row 450).
 *
 * <p>Translates the five S09 boundary-API exceptions and unhandled {@link RuntimeException}s into
 * consistent {@link ApiErrorResponse} JSON bodies. Photo-domain exceptions are handled by {@link
 * de.vvwt.tm.web.photo.PhotoExceptionAdvice} (E23S05 Cutover-1 — Spring Modulith boundary
 * compliance; {@code tournament.allowedDependencies = {"tenant"}} forbids direct {@code photo}
 * imports here). Certificate-domain exceptions are handled by {@link
 * de.vvwt.tm.web.certificate.CertificateExceptionAdvice} (E23S10 Cutover-2 — same boundary
 * rationale; {@code tournament.allowedDependencies = {"tenant"}} forbids direct {@code certificate}
 * imports here).
 *
 * <h2>DEC-21 package discipline</h2>
 *
 * <p>Placed at {@code de.vvwt.tm.tournament.internal.web.*} because while its effects cross
 * contexts (HTTP error responses are global), its implementation is tournament-internal (per D-8).
 * Only the contract — {@link ApiErrorResponse} — is public.
 *
 * <h2>Scope-bounded (AC-GLOBAL-EXCEPTION-HANDLER-SCOPE-BOUNDED)</h2>
 *
 * <p>{@code @ControllerAdvice(basePackages = {"de.vvwt.tm.tournament", "de.vvwt.tm.infrastructure",
 * "de.vvwt.tm.web"})} covers controllers in the tournament, infrastructure, and web package trees.
 * Extended to include {@code de.vvwt.tm.web} in E22S07 (DEC-40 Clause A controller relocation).
 * Legacy exceptions ({@code de.vvwt.tm.domain.timer.InvalidTimerUrlException}, {@code
 * de.vvwt.tm.domain.timer.NoActiveTournamentException}, {@code
 * de.vvwt.tm.infrastructure.display.NoActivePhaseException}) are NOT handled here — they remain in
 * the legacy {@code GlobalExceptionHandler} during parallel-development phase (E21S13 atomic
 * cutover scope).
 *
 * <h2>Security (AC-SEC-NO-EXCEPTION-LEAK)</h2>
 *
 * <p>The 500-fallback handler logs the full exception but returns ONLY a generic message in the
 * response body — no stack traces, no internal class FQNs, no SQL statement text.
 *
 * <h2>S09 exception imports (AC-S09-EXCEPTION-IMPORT)</h2>
 *
 * <p>All five exceptions are imported from {@code de.vvwt.tm.tournament.exceptions.*} (S09 public
 * package) — NOT from {@code .internal.exceptions.*}.
 *
 * @see ApiErrorResponse
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, internal vs public package</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-29">DEC-29 — Compiler hygiene</a>
 * @see <a href="DEC-30">DEC-30 — Spotless formatting</a>
 * @see <a href="E21S10">E21S10 — inventory row 450</a>
 */
@Component("tmGlobalExceptionHandler")
@ControllerAdvice(
        basePackages = {"de.vvwt.tm.tournament", "de.vvwt.tm.infrastructure", "de.vvwt.tm.web"})
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // =========================================================================
    // Five S09 boundary-API exception mappings (AC-GLOBAL-EXCEPTION-HANDLER-INTEGRATION)
    // =========================================================================

    /** Maps {@link ForbiddenException} to HTTP 403 (AC-S09-EXCEPTION-IMPORT). */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiErrorResponse> handleForbidden(
            ForbiddenException ex, HttpServletRequest request) {
        log.debug("[tm-web] ForbiddenException: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage(), "error.forbidden", request);
    }

    /** Maps {@link TooManyRequestsException} to HTTP 429. */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiErrorResponse> handleTooManyRequests(
            TooManyRequestsException ex, HttpServletRequest request) {
        log.debug("[tm-web] TooManyRequestsException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), "error.tooManyRequests", request);
    }

    /** Maps {@link UnauthorizedException} to HTTP 401. */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnauthorized(
            UnauthorizedException ex, HttpServletRequest request) {
        log.debug("[tm-web] UnauthorizedException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.UNAUTHORIZED, ex.getMessage(), "error.unauthorized", request);
    }

    /** Maps {@link ValidationException} to HTTP 400. */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            ValidationException ex, HttpServletRequest request) {
        log.debug("[tm-web] ValidationException: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), "error.validation", request);
    }

    /** Maps {@link ConflictException} to HTTP 409. */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(
            ConflictException ex, HttpServletRequest request) {
        log.debug("[tm-web] ConflictException: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), "error.conflict", request);
    }

    /**
     * Maps {@link TournamentNotFoundException} to HTTP 404 (AC-TNFE-HANDLER-ADD, E24S05).
     *
     * <p>Added as a NEW method to this existing class per E24S05 scope. Class location ({@code
     * tournament.internal.web}), {@code basePackages}, and existing handler methods are UNCHANGED.
     * GlobalExceptionHandler migration to {@code de.vvwt.tm.web.*} is DEFERRED to E34
     * retro-correction epic per Brief v6 Seq-C.
     *
     * <p>Covers {@link TournamentNotFoundException} thrown from {@code web.certificate.*}
     * (CertificateRenderController, E24S05), {@code web.*} (PrintController, E24S06), and any other
     * controller in the three covered package trees ({@code tournament}, {@code infrastructure},
     * {@code web}) — via {@code GlobalExceptionHandler.basePackages}.
     *
     * <p>Debug-level log (not WARN/ERROR): this is an expected business-condition 404 (wrong UUID
     * in URL), not a system error.
     *
     * @see de.vvwt.tm.tournament.exceptions.TournamentNotFoundException
     * @since E24S05
     */
    @ExceptionHandler(TournamentNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleTournamentNotFound(
            TournamentNotFoundException ex, HttpServletRequest request) {
        log.debug("[tm-web] TournamentNotFoundException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.NOT_FOUND, ex.getMessage(), "error.tournament.notFound", request);
    }

    /**
     * Maps {@link NoSuchElementException} to HTTP 404.
     *
     * <p>Migrated from the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} during E21S13 cutover (DEC-22 refactor
     * phase — behavior-preserving). Consumer controllers (e.g., {@code
     * ActivityAssignmentPreviewController}, {@code ActivityTypeController}) throw {@code
     * NoSuchElementException} when a referenced entity is not found.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            NoSuchElementException ex, HttpServletRequest request) {
        log.debug("[tm-web] NoSuchElementException: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), "error.notFound", request);
    }

    /**
     * Maps Spring Security's {@link AuthorizationDeniedException} to HTTP 403.
     *
     * <p>Spring Security 6 throws {@code AuthorizationDeniedException} (a subclass of {@link
     * RuntimeException}) for method-security {@code @PreAuthorize} failures. Without this specific
     * handler, the catch-all {@code RuntimeException} handler would return 500 instead of 403.
     *
     * <p>Migrated from the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} during E21S13 cutover (DEC-22 refactor
     * phase — behavior-preserving).
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthorizationDenied(
            AuthorizationDeniedException ex, HttpServletRequest request) {
        log.debug("[tm-web] AuthorizationDeniedException: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "Access denied.", "error.forbidden", request);
    }

    /**
     * Maps {@link IllegalArgumentException} to HTTP 400 Bad Request.
     *
     * <p>Migrated from the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} during E21S13 cutover (DEC-22 refactor
     * phase — behavior-preserving). Consumer services (e.g., {@code ActivityTypeService}) throw
     * {@code IllegalArgumentException} for invalid input (e.g., unknown rule id, wrong device
     * type).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        log.debug("[tm-web] IllegalArgumentException: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), "error.badRequest", request);
    }

    /**
     * Maps {@link MethodArgumentNotValidException} (Spring MVC {@code @Valid} failures) to HTTP 400
     * Bad Request.
     *
     * <p>Migrated from the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} during E21S13 cutover (DEC-22 refactor
     * phase — behavior-preserving). Spring MVC throws this exception when a {@code @Valid}
     * annotation triggers a validation failure on a {@code @RequestBody}.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        log.debug("[tm-web] MethodArgumentNotValidException: {}", ex.getMessage());
        String message =
                ex.getBindingResult().getFieldErrors().stream()
                        .findFirst()
                        .map(org.springframework.validation.FieldError::getDefaultMessage)
                        .orElse("Validation failed");
        return buildResponse(HttpStatus.BAD_REQUEST, message, "error.validation", request);
    }

    // =========================================================================
    // Audio exceptions (E10S02 — AudioControllerIT)
    // =========================================================================

    /**
     * Maps {@link AudioFormatException} to HTTP 415 Unsupported Media Type.
     *
     * <p>Migrated from the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} during E21S13 cutover (DEC-22 refactor
     * phase — behavior-preserving).
     */
    @ExceptionHandler(AudioFormatException.class)
    public ResponseEntity<ApiErrorResponse> handleAudioFormat(
            AudioFormatException ex, HttpServletRequest request) {
        log.debug("[tm-web] AudioFormatException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage(), "error.audio.format", request);
    }

    /**
     * Maps {@link AudioSizeLimitException} to HTTP 413 Payload Too Large.
     *
     * <p>Migrated from the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} during E21S13 cutover (DEC-22 refactor
     * phase — behavior-preserving).
     */
    @ExceptionHandler(AudioSizeLimitException.class)
    public ResponseEntity<ApiErrorResponse> handleAudioSizeLimit(
            AudioSizeLimitException ex, HttpServletRequest request) {
        log.debug("[tm-web] AudioSizeLimitException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), "error.audio.tooLarge", request);
    }

    /**
     * Maps Spring's {@link MaxUploadSizeExceededException} to HTTP 413 Payload Too Large.
     *
     * <p>Spring throws this exception before the controller is reached when the multipart size
     * limit is exceeded. Migrated from the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} during E21S13 cutover (DEC-22 refactor
     * phase — behavior-preserving).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.debug("[tm-web] MaxUploadSizeExceededException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "File size exceeds the maximum allowed limit.",
                "error.audio.tooLarge",
                request);
    }

    /**
     * Maps {@link AudioStorageException} to HTTP 500 Internal Server Error.
     *
     * <p>Migrated from the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} during E21S13 cutover (DEC-22 refactor
     * phase — behavior-preserving).
     */
    @ExceptionHandler(AudioStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleAudioStorage(
            AudioStorageException ex, HttpServletRequest request) {
        log.error("[tm-web] AudioStorageException: {}", ex.getMessage(), ex);
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Audio storage error.",
                "error.audio.storage",
                request);
    }

    // =========================================================================
    // Timer exceptions (E11S02 — TimerControllerIT)
    // =========================================================================

    /**
     * Maps {@link InvalidTimerUrlException} to HTTP 404 with the exception's error code as the
     * {@code messageKey} (e.g., {@code "INVALID_TIMER_URL"}).
     *
     * <p>Migrated from the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} during E21S13 cutover (DEC-22 refactor
     * phase — behavior-preserving).
     */
    @ExceptionHandler(InvalidTimerUrlException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidTimerUrl(
            InvalidTimerUrlException ex, HttpServletRequest request) {
        log.debug("[tm-web] InvalidTimerUrlException: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode(), request);
    }

    /**
     * Maps {@link NoActiveTournamentException} to HTTP 404 with the exception's error code as the
     * {@code messageKey} (e.g., {@code "NO_ACTIVE_TOURNAMENT"}).
     *
     * <p>Migrated from the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} during E21S13 cutover (DEC-22 refactor
     * phase — behavior-preserving).
     */
    @ExceptionHandler(NoActiveTournamentException.class)
    public ResponseEntity<ApiErrorResponse> handleNoActiveTournament(
            NoActiveTournamentException ex, HttpServletRequest request) {
        log.debug("[tm-web] NoActiveTournamentException: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode(), request);
    }

    // =========================================================================
    // Spring MVC / infrastructure exceptions
    // =========================================================================

    /**
     * Maps {@link MissingServletRequestParameterException} to HTTP 400 Bad Request.
     *
     * <p>Spring MVC throws this when a required {@code @RequestParam} is absent from the request.
     * Migrated from the deleted legacy {@code de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}
     * during E21S13 cutover (DEC-22 refactor phase — behavior-preserving).
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        log.debug("[tm-web] MissingServletRequestParameterException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.BAD_REQUEST, ex.getMessage(), "error.missingParameter", request);
    }

    /**
     * Maps Spring's {@link NoResourceFoundException} (404 from DispatcherServlet) to HTTP 404.
     *
     * <p>Spring MVC 6 throws this when no handler mapping matches. Without this handler, the
     * catch-all {@code RuntimeException} would return 500. Migrated from the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} during E21S13 cutover (DEC-22 refactor
     * phase — behavior-preserving).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        log.debug("[tm-web] NoResourceFoundException: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), "error.notFound", request);
    }

    /**
     * Maps {@link DataIntegrityViolationException} to HTTP 409 Conflict.
     *
     * <p>Spring wraps DB unique-constraint violations (e.g., duplicate keys) in this exception.
     * Migrated from the deleted legacy {@code de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}
     * during E21S13 cutover (DEC-22 refactor phase — behavior-preserving).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.debug("[tm-web] DataIntegrityViolationException: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "Data conflict.", "error.conflict", request);
    }

    // =========================================================================
    // Unknown RuntimeException → 500, no stack trace (AC-GLOBAL-EXCEPTION-HANDLER-UNKNOWN)
    // SQL exception → generic message, no SQL content (AC-SEC-NO-EXCEPTION-LEAK)
    // =========================================================================

    /**
     * Fallback handler for any unhandled {@link RuntimeException} or {@link SQLException}.
     *
     * <p>Logs the full exception at ERROR level but returns ONLY a generic message — no stack
     * trace, no class FQNs, no SQL statement text in the response body (AC-SEC-NO-EXCEPTION-LEAK).
     */
    @ExceptionHandler({RuntimeException.class, SQLException.class})
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error(
                "[tm-web] Unexpected error on {}: {}",
                request.getRequestURI(),
                ex.getMessage(),
                ex);
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.",
                "error.internal",
                request);
    }

    // =========================================================================
    // Photo domain exceptions (E36S08 Phase 3 — absorbed from deleted PhotoExceptionAdvice)
    // HTTP mappings verbatim per AC-C11-BEHAVIORAL-EQUIVALENCE
    // =========================================================================

    /**
     * Maps {@link PhotoFormatException} to HTTP 400 Bad Request.
     *
     * <p>Absorbed from the deleted {@code PhotoExceptionAdvice} (E36S08 Phase 2) per
     * AC-C11-BEHAVIORAL-EQUIVALENCE. HTTP mapping and messageKey preserved verbatim from the
     * deleted advice's {@code handlePhotoFormat} method.
     *
     * @see <a href="E36S08">E36S08 — Phase 3 RED-first photo exception absorption</a>
     */
    @ExceptionHandler(PhotoFormatException.class)
    public ResponseEntity<ApiErrorResponse> handlePhotoFormat(
            PhotoFormatException ex, HttpServletRequest request) {
        log.debug("[tm-web] PhotoFormatException (photo module): {}", ex.getMessage());
        return buildResponse(
                HttpStatus.BAD_REQUEST, ex.getMessage(), "error.photo.format", request);
    }

    /**
     * Maps {@link PhotoSizeException} to HTTP 400 Bad Request.
     *
     * <p>Absorbed from the deleted {@code PhotoExceptionAdvice} (E36S08 Phase 2) per
     * AC-C11-BEHAVIORAL-EQUIVALENCE. HTTP mapping and messageKey preserved verbatim.
     *
     * @see <a href="E36S08">E36S08 — Phase 3 RED-first photo exception absorption</a>
     */
    @ExceptionHandler(PhotoSizeException.class)
    public ResponseEntity<ApiErrorResponse> handlePhotoSize(
            PhotoSizeException ex, HttpServletRequest request) {
        log.debug("[tm-web] PhotoSizeException (photo module): {}", ex.getMessage());
        return buildResponse(
                HttpStatus.BAD_REQUEST, ex.getMessage(), "error.photo.tooLarge", request);
    }

    /**
     * Maps {@link PhotoStorageException} to HTTP 500 Internal Server Error.
     *
     * <p>Absorbed from the deleted {@code PhotoExceptionAdvice} (E36S08 Phase 2) per
     * AC-C11-BEHAVIORAL-EQUIVALENCE. HTTP mapping and messageKey preserved verbatim. Logs at ERROR
     * level (storage failures are system errors, not client errors).
     *
     * @see <a href="E36S08">E36S08 — Phase 3 RED-first photo exception absorption</a>
     */
    @ExceptionHandler(PhotoStorageException.class)
    public ResponseEntity<ApiErrorResponse> handlePhotoStorage(
            PhotoStorageException ex, HttpServletRequest request) {
        log.error("[tm-web] PhotoStorageException (photo module): {}", ex.getMessage(), ex);
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Photo storage error.",
                "error.photo.storage",
                request);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpStatus status, String message, String messageKey, HttpServletRequest request) {
        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                status.value(), status.getReasonPhrase(), message, messageKey)
                        .path(request.getRequestURI())
                        .build();
        return ResponseEntity.status(status).body(body);
    }

    // =========================================================================
    // Display context: NoActivePhaseException → 404 (E21S13 cutover — DEC-22 refactor phase)
    // =========================================================================

    /**
     * Maps {@link NoActivePhaseException} to HTTP 404 with a {@link NoActivePhaseResponse} body.
     *
     * <p>Previously handled by the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler#handleNoActivePhase}. Migrated here
     * during E21S13 atomic cutover (DEC-22 refactor phase — behavior-preserving).
     */
    @ExceptionHandler(NoActivePhaseException.class)
    public ResponseEntity<NoActivePhaseResponse> handleNoActivePhase(NoActivePhaseException ex) {
        log.debug("[tm-web] NoActivePhaseException: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new NoActivePhaseResponse("NO_ACTIVE_PHASE"));
    }

    /**
     * Response body for {@link NoActivePhaseException} (AC7 of E07S04).
     *
     * <p>Previously a nested record in the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}. Migrated here during E21S13 atomic
     * cutover (DEC-22 refactor phase — behavior-preserving).
     *
     * @param status the error status string, e.g. {@code "NO_ACTIVE_PHASE"}
     */
    public record NoActivePhaseResponse(String status) {}
}
