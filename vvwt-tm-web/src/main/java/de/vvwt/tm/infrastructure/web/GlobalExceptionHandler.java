package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.ForbiddenException;
import de.vvwt.tm.domain.TooManyRequestsException;
import de.vvwt.tm.domain.UnauthorizedException;
import de.vvwt.tm.domain.ValidationException;
import de.vvwt.tm.domain.audio.AudioFormatException;
import de.vvwt.tm.domain.audio.AudioSizeLimitException;
import de.vvwt.tm.domain.audio.AudioStorageException;
import de.vvwt.tm.domain.certificate.CertificateTemplateFormatException;
import de.vvwt.tm.domain.certificate.CertificateTemplateSizeException;
import de.vvwt.tm.domain.certificate.CertificateTemplateStorageException;
import de.vvwt.tm.domain.photo.PhotoFormatException;
import de.vvwt.tm.domain.photo.PhotoSizeException;
import de.vvwt.tm.domain.photo.PhotoStorageException;
import de.vvwt.tm.domain.timer.InvalidTimerUrlException;
import de.vvwt.tm.domain.timer.NoActiveTournamentException;
import de.vvwt.tm.infrastructure.display.NoActivePhaseException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Global exception handler for all REST API controllers (AC1, E05S03).
 *
 * <p>Translates exceptions into consistent JSON error responses using {@link ApiErrorResponse}. All
 * responses include a {@code messageKey} field for SPA i18n translation (AC9).
 *
 * <h2>Exception mapping (AC1)</h2>
 *
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} → HTTP 400 with field-level error details
 *   <li>{@link NoSuchElementException} → HTTP 404 (entity not found)
 *   <li>{@link DataIntegrityViolationException} → HTTP 409 Conflict (constraint violation, e.g.,
 *       DEC-5 single-active-tournament)
 *   <li>{@link ConflictException} → HTTP 409 Conflict (domain-level constraint violation)
 *   <li>{@link Exception} → HTTP 500 with a generic message (no stack trace in body — AC1)
 * </ul>
 *
 * <h2>Security (AC1)</h2>
 *
 * <p>The 500 handler logs the full exception at ERROR level (for operator diagnosis) but returns
 * only a generic message in the response body. No internal details (class names, SQL, stack traces)
 * are exposed to callers.
 *
 * <h2>i18n (AC9)</h2>
 *
 * <p>Each response includes a {@code messageKey} that the SPA maps to a locale-specific string via
 * its translation layer. Message keys follow the convention {@code error.{domain}}.
 *
 * @see ApiErrorResponse
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story
 *     E05S03</a>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // -------------------------------------------------------------------------
    // AC1 — 400: Validation errors
    // -------------------------------------------------------------------------

    /**
     * Handles Bean Validation errors from {@code @Valid} / {@code @Validated} on request bodies
     * (AC1 — validation errors → HTTP 400 with field-level error details).
     *
     * @param ex the validation exception
     * @param request the current HTTP request
     * @return 400 response with per-field error details
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<ApiErrorResponse.FieldError> fieldErrors =
                ex.getBindingResult().getAllErrors().stream()
                        .map(
                                error -> {
                                    if (error instanceof FieldError fe) {
                                        return new ApiErrorResponse.FieldError(
                                                fe.getField(),
                                                fe.getRejectedValue(),
                                                fe.getDefaultMessage(),
                                                "error.validation.field");
                                    }
                                    // Object-level constraint violation (e.g. @AssertTrue on the
                                    // class)
                                    return new ApiErrorResponse.FieldError(
                                            error.getObjectName(),
                                            null,
                                            error.getDefaultMessage(),
                                            "error.validation.object");
                                })
                        .collect(Collectors.toList());

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                "Request validation failed",
                                "error.validation")
                        .path(request.getRequestURI())
                        .fieldErrors(fieldErrors)
                        .build();

        return ResponseEntity.badRequest().body(body);
    }

    // -------------------------------------------------------------------------
    // AC1 — 404: Entity not found
    // -------------------------------------------------------------------------

    /**
     * Handles {@link NoSuchElementException} (entity not found → HTTP 404).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 404 response
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            NoSuchElementException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.NOT_FOUND.value(),
                                HttpStatus.NOT_FOUND.getReasonPhrase(),
                                ex.getMessage() != null ? ex.getMessage() : "Resource not found",
                                "error.notFound")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    // -------------------------------------------------------------------------
    // AC1 — 409: Constraint violations (DEC-5 single-active-tournament etc.)
    // -------------------------------------------------------------------------

    /**
     * Handles database-level constraint violations (AC1 — DEC-5 invariants → HTTP 409).
     *
     * <p>Example: inserting a second active tournament for the default tenant violates the partial
     * unique index (DEC-5 single-active-tournament invariant).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 409 response
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {

        // Log at WARN (not ERROR) — data integrity violations are expected in normal operation
        log.warn(
                "[tm-api] Data integrity violation at {}: {}",
                request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.CONFLICT.value(),
                                HttpStatus.CONFLICT.getReasonPhrase(),
                                "The operation conflicts with an existing constraint",
                                "error.conflict")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Handles domain-level constraint violations (AC1 — DEC-5 and similar → HTTP 409).
     *
     * <p>{@link ConflictException} is thrown by domain services when a business rule is violated
     * (e.g., activating a second tournament when one is already active).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 409 response
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(
            ConflictException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.CONFLICT.value(),
                                HttpStatus.CONFLICT.getReasonPhrase(),
                                ex.getMessage(),
                                "error.conflict")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    // -------------------------------------------------------------------------
    // E05S04 — 400: Invalid argument (bad enum value, unknown bean ID)
    // -------------------------------------------------------------------------

    /**
     * Handles {@link IllegalArgumentException} — thrown by domain services when an argument is
     * invalid (e.g., unknown {@code MatchFormat} value, unregistered strategy bean ID).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 400 response with the exception message
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                ex.getMessage() != null ? ex.getMessage() : "Invalid argument",
                                "error.validation")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.badRequest().body(body);
    }

    // -------------------------------------------------------------------------
    // E06S06 — 400: Missing required query parameter
    // -------------------------------------------------------------------------

    /**
     * Handles {@link MissingServletRequestParameterException} — thrown by Spring MVC when a
     * required {@code @RequestParam} is absent from the request (e.g., missing {@code token} on
     * {@code GET /api/score/match}). Returns HTTP 400.
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 400 response
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingRequestParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                "Required parameter '" + ex.getParameterName() + "' is not present",
                                "error.validation")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.badRequest().body(body);
    }

    // -------------------------------------------------------------------------
    // E06S03 AC8 — 401: Invalid or expired device token
    // -------------------------------------------------------------------------

    /**
     * Handles {@link UnauthorizedException} — thrown when a device token is invalid, expired, or
     * belongs to a different tenant (AC8 — invalid device token → HTTP 401).
     *
     * <p>Returns 401 rather than 404 to avoid oracle attacks (not informing callers whether a token
     * exists but belongs to a different tenant vs. does not exist at all).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 401 response with i18n message key
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnauthorized(
            UnauthorizedException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.UNAUTHORIZED.value(),
                                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                                ex.getMessage() != null ? ex.getMessage() : "Unauthorized",
                                "error.device.unauthorized")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    // -------------------------------------------------------------------------
    // E06S06 AC6 — 400: Set validation failure (invalid score for volleyball rule)
    // -------------------------------------------------------------------------

    /**
     * Handles {@link ValidationException} — thrown by {@link
     * de.vvwt.tm.domain.CascadeRecomputeService} when step 1 set validation rejects the submitted
     * score (AC6 — 400 with descriptive error).
     *
     * @param ex the validation exception
     * @param request the current HTTP request
     * @return 400 response with the validation reason
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationFailure(
            ValidationException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                ex.getValidationReason(),
                                "error.score.validation")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.badRequest().body(body);
    }

    // -------------------------------------------------------------------------
    // E06S06 AC12 — 403: Device not authorized for the requested field
    // -------------------------------------------------------------------------

    /**
     * Handles {@link ForbiddenException} — thrown when a device token is valid but the device is
     * not authorized for the requested operation (AC12 — wrong field → HTTP 403).
     *
     * @param ex the forbidden exception
     * @param request the current HTTP request
     * @return 403 response
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiErrorResponse> handleForbidden(
            ForbiddenException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.FORBIDDEN.value(),
                                HttpStatus.FORBIDDEN.getReasonPhrase(),
                                ex.getMessage() != null ? ex.getMessage() : "Forbidden",
                                "error.score.forbidden")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    // -------------------------------------------------------------------------
    // E07S02 AC2, AC8 — 429: Device registration limit exceeded
    // -------------------------------------------------------------------------

    /**
     * Handles {@link TooManyRequestsException} — thrown when DISPLAY device registration exceeds
     * {@code vvwt.devices.max-display-count} (E07S02 AC2 → HTTP 429).
     *
     * <p>Returns a {@link DeviceLimitErrorResponse} with {@code currentCount} and {@code maxCount}
     * fields as required by AC8.
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 429 response with device limit details
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<DeviceLimitErrorResponse> handleTooManyRequests(
            TooManyRequestsException ex, HttpServletRequest request) {

        DeviceLimitErrorResponse body =
                new DeviceLimitErrorResponse(
                        429,
                        "Too Many Requests",
                        ex.getMessage(),
                        "error.device.limitExceeded",
                        request.getRequestURI(),
                        ex.getCurrentCount(),
                        ex.getMaxCount());

        return ResponseEntity.status(429).body(body);
    }

    // -------------------------------------------------------------------------
    // E07S04 AC7 — 404: No active phase
    // -------------------------------------------------------------------------

    /**
     * Handles {@link NoActivePhaseException} — thrown when no active phase exists for the current
     * tenant's tournament (E07S04 AC7 — 404 with structured body).
     *
     * <p>Returns a specific body format per AC7: {@code { "status": "NO_ACTIVE_PHASE" }}. This is
     * achieved via the structured {@link ApiErrorResponse} with {@code message = "NO_ACTIVE_PHASE"}
     * and a matching {@code messageKey} for SPA i18n (AC10).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 404 response with {@code {"status":"NO_ACTIVE_PHASE"}} body
     */
    @ExceptionHandler(NoActivePhaseException.class)
    public ResponseEntity<NoActivePhaseResponse> handleNoActivePhase(
            NoActivePhaseException ex, HttpServletRequest request) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new NoActivePhaseResponse("NO_ACTIVE_PHASE"));
    }

    /**
     * Structured 404 body for the display overview endpoints when no phase is active (E07S04 AC7).
     *
     * <p>The AC7 requirement specifies {@code { "status": "NO_ACTIVE_PHASE" }} as the exact body
     * shape. This record serializes directly to that JSON.
     *
     * @param status always {@code "NO_ACTIVE_PHASE"}
     */
    public record NoActivePhaseResponse(String status) {}

    // -------------------------------------------------------------------------
    // E07S04 AC11 — 405: HTTP method not supported
    // -------------------------------------------------------------------------

    /**
     * Handles {@link HttpRequestMethodNotSupportedException} — thrown by Spring MVC when a
     * controller does not declare a handler for the requested HTTP method (e.g., POST to a GET-only
     * display endpoint — E07S04 AC11).
     *
     * <p>Without this handler, the catch-all {@link #handleUnexpected} would intercept the
     * exception and return 500. This handler restores the correct 405 semantics.
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 405 response
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.METHOD_NOT_ALLOWED.value(),
                                HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase(),
                                "HTTP method '"
                                        + ex.getMethod()
                                        + "' is not supported for this endpoint",
                                "error.methodNotAllowed")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    // -------------------------------------------------------------------------
    // E11S01 — 415: Audio format rejection
    // -------------------------------------------------------------------------

    /**
     * Handles {@link AudioFormatException} — thrown when an uploaded file is not .mp3 (E11S01 AC7 →
     * HTTP 415 Unsupported Media Type).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 415 response with message naming allowed formats
     */
    @ExceptionHandler(AudioFormatException.class)
    public ResponseEntity<ApiErrorResponse> handleAudioFormat(
            AudioFormatException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                                HttpStatus.UNSUPPORTED_MEDIA_TYPE.getReasonPhrase(),
                                ex.getMessage(),
                                "error.audio.format")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body);
    }

    // -------------------------------------------------------------------------
    // E11S01 — 413: Audio file too large
    // -------------------------------------------------------------------------

    /**
     * Handles {@link AudioSizeLimitException} — thrown when an audio file exceeds the configured
     * size limit (E11S01 AC7 → HTTP 413 Content Too Large).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 413 response
     */
    @ExceptionHandler(AudioSizeLimitException.class)
    public ResponseEntity<ApiErrorResponse> handleAudioSizeLimit(
            AudioSizeLimitException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                                HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase(),
                                ex.getMessage(),
                                "error.audio.tooLarge")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    /**
     * Handles Spring's {@link MaxUploadSizeExceededException} — thrown by the multipart resolver
     * when a request exceeds {@code spring.servlet.multipart.max-file-size} (E11S01 AC7 → HTTP 413
     * Content Too Large).
     *
     * <p>This catches size violations detected at the servlet layer (before the controller is
     * reached), complementing the service-layer {@link AudioSizeLimitException} guard.
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 413 response
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSize(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                                HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase(),
                                "Uploaded file exceeds the maximum allowed size",
                                "error.audio.tooLarge")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    // -------------------------------------------------------------------------
    // E11S01 — 500: Audio I/O error
    // -------------------------------------------------------------------------

    /**
     * Handles {@link AudioStorageException} — thrown when a disk I/O error occurs during audio file
     * storage (E11S01 AC7 → HTTP 500 with descriptive message).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 500 response with the descriptive message from the exception
     */
    @ExceptionHandler(AudioStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleAudioStorage(
            AudioStorageException ex, HttpServletRequest request) {

        log.error("[tm-api] Audio storage I/O error at {}", request.getRequestURI(), ex);

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                                ex.getMessage(),
                                "error.audio.storage")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // -------------------------------------------------------------------------
    // E11S02 — 404: Timer URL not found / tournament not accessible
    // -------------------------------------------------------------------------

    /**
     * Handles {@link InvalidTimerUrlException} — thrown when no tournament exists for the given
     * timer URL UUID (E11S02 AC7 → HTTP 404 with {@code errorCode: "INVALID_TIMER_URL"}).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 404 response with {@code errorCode: "INVALID_TIMER_URL"}
     */
    @ExceptionHandler(InvalidTimerUrlException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidTimerUrl(
            InvalidTimerUrlException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.NOT_FOUND.value(),
                                HttpStatus.NOT_FOUND.getReasonPhrase(),
                                ex.getMessage(),
                                ex.getErrorCode())
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Handles {@link NoActiveTournamentException} — thrown when the tournament is in DRAFT or
     * CANCELLED status (E11S02 AC7 → HTTP 404 with {@code errorCode: "NO_ACTIVE_TOURNAMENT"}).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 404 response with {@code errorCode: "NO_ACTIVE_TOURNAMENT"}
     */
    @ExceptionHandler(NoActiveTournamentException.class)
    public ResponseEntity<ApiErrorResponse> handleNoActiveTournament(
            NoActiveTournamentException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.NOT_FOUND.value(),
                                HttpStatus.NOT_FOUND.getReasonPhrase(),
                                ex.getMessage(),
                                ex.getErrorCode())
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    // -------------------------------------------------------------------------
    // E12S02 — 400: Photo format rejection (AC7)
    // -------------------------------------------------------------------------

    /**
     * Handles {@link PhotoFormatException} — thrown when an uploaded file is not JPEG or PNG
     * (E12S02 AC7 → HTTP 400 Bad Request).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 400 response with message naming allowed formats
     */
    @ExceptionHandler(PhotoFormatException.class)
    public ResponseEntity<ApiErrorResponse> handlePhotoFormat(
            PhotoFormatException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                ex.getMessage(),
                                "error.photo.format")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.badRequest().body(body);
    }

    // -------------------------------------------------------------------------
    // E12S02 — 400: Photo file too large (AC7)
    // -------------------------------------------------------------------------

    /**
     * Handles {@link PhotoSizeException} — thrown when a photo file exceeds the configured size
     * limit (E12S02 AC7 → HTTP 400 Bad Request).
     *
     * <p>HTTP 400 is used (not 413) because the limit is story-specific (5 MB) and is enforced at
     * the service layer before Spring's multipart limit is reached. The global multipart limit (10
     * MB for audio) must not be reduced to avoid breaking E11S01.
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 400 response
     */
    @ExceptionHandler(PhotoSizeException.class)
    public ResponseEntity<ApiErrorResponse> handlePhotoSize(
            PhotoSizeException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                ex.getMessage(),
                                "error.photo.tooLarge")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.badRequest().body(body);
    }

    // -------------------------------------------------------------------------
    // E12S02 — 500: Photo I/O error (AC8)
    // -------------------------------------------------------------------------

    /**
     * Handles {@link PhotoStorageException} — thrown when a disk I/O error occurs during photo file
     * storage (E12S02 AC8 → HTTP 500 with meaningful message, no stack trace).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 500 response with the descriptive message from the exception
     */
    @ExceptionHandler(PhotoStorageException.class)
    public ResponseEntity<ApiErrorResponse> handlePhotoStorage(
            PhotoStorageException ex, HttpServletRequest request) {

        log.error("[tm-api] Photo storage I/O error at {}", request.getRequestURI(), ex);

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                                ex.getMessage(),
                                "error.photo.storage")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // -------------------------------------------------------------------------
    // E12S04 — 400: Certificate template format rejection (AC7)
    // -------------------------------------------------------------------------

    /**
     * Handles {@link CertificateTemplateFormatException} — thrown when an uploaded file is not HTML
     * or SVG, or is not well-formed (E12S04 AC7 → HTTP 400 Bad Request).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 400 response with message naming allowed formats
     */
    @ExceptionHandler(CertificateTemplateFormatException.class)
    public ResponseEntity<ApiErrorResponse> handleCertificateTemplateFormat(
            CertificateTemplateFormatException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                ex.getMessage(),
                                "error.certificateTemplate.format")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.badRequest().body(body);
    }

    // -------------------------------------------------------------------------
    // E12S04 — 400: Certificate template file too large (AC7)
    // -------------------------------------------------------------------------

    /**
     * Handles {@link CertificateTemplateSizeException} — thrown when a certificate template file
     * exceeds the configured size limit (E12S04 AC7 → HTTP 400 Bad Request).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 400 response
     */
    @ExceptionHandler(CertificateTemplateSizeException.class)
    public ResponseEntity<ApiErrorResponse> handleCertificateTemplateSize(
            CertificateTemplateSizeException ex, HttpServletRequest request) {

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                ex.getMessage(),
                                "error.certificateTemplate.tooLarge")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.badRequest().body(body);
    }

    // -------------------------------------------------------------------------
    // E12S04 — 500: Certificate template I/O error (AC9)
    // -------------------------------------------------------------------------

    /**
     * Handles {@link CertificateTemplateStorageException} — thrown when a disk I/O error occurs
     * during certificate template storage (E12S04 AC9 → HTTP 500).
     *
     * @param ex the exception
     * @param request the current HTTP request
     * @return 500 response with descriptive message, no stack trace
     */
    @ExceptionHandler(CertificateTemplateStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleCertificateTemplateStorage(
            CertificateTemplateStorageException ex, HttpServletRequest request) {

        log.error(
                "[tm-api] Certificate template storage I/O error at {}",
                request.getRequestURI(),
                ex);

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                                ex.getMessage(),
                                "error.certificateTemplate.storage")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // E21S06 — 403: Access denied (@PreAuthorize violations)
    // -------------------------------------------------------------------------

    /**
     * Maps Spring Security {@link AccessDeniedException} to HTTP 403 Forbidden (E21S06, DEC-24).
     *
     * <p>Without this handler, the catch-all {@code Exception} handler would intercept {@code
     * AccessDeniedException} before Spring Security's {@code ExceptionTranslationFilter} can
     * convert it, resulting in a spurious HTTP 500. This handler ensures that
     * {@code @PreAuthorize}-enforced role violations return 403 as expected.
     *
     * @param ex the access denied exception
     * @param request the current HTTP request
     * @return 403 response
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.FORBIDDEN.value(),
                                HttpStatus.FORBIDDEN.getReasonPhrase(),
                                "Access denied.",
                                "error.forbidden")
                        .path(request.getRequestURI())
                        .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    // AC1 — 500: Unexpected errors (no stack trace in response — AC1)
    // -------------------------------------------------------------------------

    /**
     * Catch-all handler for unexpected exceptions (AC1 — unexpected errors → HTTP 500).
     *
     * <p>Logs the full exception at ERROR level for operator diagnosis. Returns only a generic
     * message in the response body — no stack trace, no internal class names, no SQL details are
     * exposed to callers (AC1 security requirement).
     *
     * @param ex the unexpected exception
     * @param request the current HTTP request
     * @return 500 response with a generic message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {

        log.error("[tm-api] Unexpected error at {}", request.getRequestURI(), ex);

        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                                "An unexpected error occurred. Please contact the administrator.",
                                "error.internal")
                        .path(request.getRequestURI())
                        .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
