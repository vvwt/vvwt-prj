// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import de.vvwt.tm.certificate.CertificateTemplateFormatException;
import de.vvwt.tm.certificate.CertificateTemplateSizeException;
import de.vvwt.tm.certificate.CertificateTemplateStorageException;
import de.vvwt.tm.display.NoActivePhaseException;
import de.vvwt.tm.photo.PhotoFormatException;
import de.vvwt.tm.photo.PhotoSizeException;
import de.vvwt.tm.photo.PhotoStorageException;
import de.vvwt.tm.timer.InvalidTimerUrlException;
import de.vvwt.tm.timer.NoActiveTournamentException;
import de.vvwt.tm.timer.audio.AudioFormatException;
import de.vvwt.tm.timer.audio.AudioSizeLimitException;
import de.vvwt.tm.timer.audio.AudioStorageException;
import de.vvwt.tm.tournament.ApiErrorResponse;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import de.vvwt.tm.tournament.exceptions.ForbiddenException;
import de.vvwt.tm.tournament.exceptions.MatchCanceledException;
import de.vvwt.tm.tournament.exceptions.MatchStateGuardException;
import de.vvwt.tm.tournament.exceptions.PhaseStateGuardException;
import de.vvwt.tm.tournament.exceptions.StandoffFormatMismatchException;
import de.vvwt.tm.tournament.exceptions.TooManyRequestsException;
import de.vvwt.tm.tournament.exceptions.TournamentCascadeDeleteActiveException;
import de.vvwt.tm.tournament.exceptions.TournamentCascadeDeleteCompletedException;
import de.vvwt.tm.tournament.exceptions.TournamentNotFoundException;
import de.vvwt.tm.tournament.exceptions.TournamentNotInDraftException;
import de.vvwt.tm.tournament.exceptions.TournamentResetPlanActiveException;
import de.vvwt.tm.tournament.exceptions.TournamentResetPlanCancelledException;
import de.vvwt.tm.tournament.exceptions.TournamentResetPlanCompletedException;
import de.vvwt.tm.tournament.exceptions.TournamentResetPlanDraftIdempotentException;
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
import org.springframework.http.converter.HttpMessageNotReadableException;
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
 * consistent {@link ApiErrorResponse} JSON bodies. Photo-domain exceptions ({@link
 * de.vvwt.tm.photo.PhotoFormatException}, {@link de.vvwt.tm.photo.PhotoSizeException}, {@link
 * de.vvwt.tm.photo.PhotoStorageException}) and certificate-domain exceptions ({@link
 * de.vvwt.tm.certificate.CertificateTemplateFormatException}, {@link
 * de.vvwt.tm.certificate.CertificateTemplateSizeException}, {@link
 * de.vvwt.tm.certificate.CertificateTemplateStorageException}) are consolidated here (E36S08 Phase
 * 3) — absorbed from the deleted {@code PhotoExceptionAdvice} and {@code
 * CertificateExceptionAdvice} (E36S08 Phase 2). Now resident in the {@code web} module, this
 * handler has direct {@code photo} and {@code certificate} dependency access per {@code
 * web.allowedDependencies}.
 *
 * <h2>DEC-21 + DEC-35 package discipline</h2>
 *
 * <p>Relocated from {@code de.vvwt.tm.tournament.internal.web.*} to {@code de.vvwt.tm.web.*} in
 * E36S08 Phase 1 (FQN-relocation per DEC-35 + DEC-40 Clause A: cross-cutting web infrastructure
 * lives at the {@code web} module root). Only the contract — {@link ApiErrorResponse} — is public
 * (at {@code de.vvwt.tm.tournament.ApiErrorResponse}).
 *
 * <h2>Scope-bounded (AC-GLOBAL-EXCEPTION-HANDLER-SCOPE-BOUNDED)</h2>
 *
 * <p>{@code @ControllerAdvice(basePackages = {"de.vvwt.tm.tournament", "de.vvwt.tm.infrastructure",
 * "de.vvwt.tm.web"})} covers controllers in the tournament, infrastructure, and web package trees.
 * Extended to include {@code de.vvwt.tm.web} in E22S07 (DEC-40 Clause A controller relocation).
 * Legacy exceptions ({@code de.vvwt.tm.timer.InvalidTimerUrlException}, {@code
 * de.vvwt.tm.timer.NoActiveTournamentException}, {@code de.vvwt.tm.display.NoActivePhaseException})
 * are NOT handled here — they remain in the legacy {@code GlobalExceptionHandler} during
 * parallel-development phase (E21S13 atomic cutover scope).
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
     * Maps {@link MatchCanceledException} to HTTP 409 Conflict (E48S04,
     * AC-ERROR-HANDLING-CANCELED-MATCH-MESSAGE).
     *
     * <p>Score submissions on CANCELED matches are rejected with an operator-actionable message.
     */
    @ExceptionHandler(MatchCanceledException.class)
    public ResponseEntity<ApiErrorResponse> handleMatchCanceled(
            MatchCanceledException ex, HttpServletRequest request) {
        log.debug("[tm-web] MatchCanceledException: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), "error.match.canceled", request);
    }

    // =========================================================================
    // E48S25 — Correction guard exceptions (AC-IMPL-CORRECTION-EXCEPTION-HANDLERS)
    // =========================================================================

    /**
     * Maps {@link PhaseStateGuardException} to HTTP 409 Conflict (E48S25,
     * AC-IMPL-CORRECTION-EXCEPTION-HANDLERS).
     *
     * <p>Correction was attempted on a match in a non-ACTIVE phase. The messageKey {@code
     * error.correction.phase-not-active} enables localised error display in the Admin SPA.
     */
    @ExceptionHandler(PhaseStateGuardException.class)
    public ResponseEntity<ApiErrorResponse> handlePhaseStateGuard(
            PhaseStateGuardException ex, HttpServletRequest request) {
        log.debug("[tm-web] PhaseStateGuardException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.CONFLICT, ex.getMessage(), "error.correction.phase-not-active", request);
    }

    /**
     * Maps {@link MatchStateGuardException} to HTTP 409 Conflict (E48S25,
     * AC-IMPL-CORRECTION-EXCEPTION-HANDLERS).
     *
     * <p>Correction was attempted on a match in INPROGRESS or ONCHECK state (live-scoring active).
     * The messageKey {@code error.correction.match-live-scoring} enables localised error display.
     */
    @ExceptionHandler(MatchStateGuardException.class)
    public ResponseEntity<ApiErrorResponse> handleMatchStateGuard(
            MatchStateGuardException ex, HttpServletRequest request) {
        log.debug("[tm-web] MatchStateGuardException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                "error.correction.match-live-scoring",
                request);
    }

    /**
     * Maps {@link StandoffFormatMismatchException} to HTTP 422 Unprocessable Entity (E48S25,
     * AC-IMPL-CORRECTION-EXCEPTION-HANDLERS).
     *
     * <p>Submitted set scores produce a tied outcome on a match format that does not allow ties
     * (i.e., BEST_OF_N). The messageKey {@code error.correction.standoff-format-mismatch} enables
     * localised error display.
     */
    @ExceptionHandler(StandoffFormatMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleStandoffFormatMismatch(
            StandoffFormatMismatchException ex, HttpServletRequest request) {
        log.debug("[tm-web] StandoffFormatMismatchException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ex.getMessage(),
                "error.correction.standoff-format-mismatch",
                request);
    }

    // =========================================================================
    // E48S13 — Six typed ConflictException subclasses (AC-IMPL-TYPED-EXCEPTIONS-AND-HANDLERS)
    // Each carries a differentiated i18n messageKey per Brief Q-2.
    // Pattern precedent: MatchCanceledException handler above.
    // The generic ConflictException handler (above, line ~137) is RETAINED for non-typed throws.
    // =========================================================================

    /**
     * Maps {@link TournamentCascadeDeleteActiveException} to HTTP 409 Conflict (E48S13,
     * AC-IMPL-TYPED-EXCEPTIONS-AND-HANDLERS).
     *
     * <p>Cascade-delete was attempted on an ACTIVE tournament. Message prompts the two-step Cancel
     * → Delete path.
     */
    @ExceptionHandler(TournamentCascadeDeleteActiveException.class)
    public ResponseEntity<ApiErrorResponse> handleCascadeDeleteActive(
            TournamentCascadeDeleteActiveException ex, HttpServletRequest request) {
        log.debug("[tm-web] TournamentCascadeDeleteActiveException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                "error.tournament.cascadeDelete.activeRejected",
                request);
    }

    /**
     * Maps {@link TournamentCascadeDeleteCompletedException} to HTTP 409 Conflict (E48S13,
     * AC-IMPL-TYPED-EXCEPTIONS-AND-HANDLERS).
     *
     * <p>Cascade-delete was attempted on a COMPLETED tournament — not permitted.
     */
    @ExceptionHandler(TournamentCascadeDeleteCompletedException.class)
    public ResponseEntity<ApiErrorResponse> handleCascadeDeleteCompleted(
            TournamentCascadeDeleteCompletedException ex, HttpServletRequest request) {
        log.debug("[tm-web] TournamentCascadeDeleteCompletedException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                "error.tournament.cascadeDelete.completedRejected",
                request);
    }

    /**
     * Maps {@link TournamentResetPlanActiveException} to HTTP 409 Conflict (E48S13,
     * AC-IMPL-TYPED-EXCEPTIONS-AND-HANDLERS).
     *
     * <p>Reset-plan was attempted on an ACTIVE tournament — not permitted.
     */
    @ExceptionHandler(TournamentResetPlanActiveException.class)
    public ResponseEntity<ApiErrorResponse> handleResetPlanActive(
            TournamentResetPlanActiveException ex, HttpServletRequest request) {
        log.debug("[tm-web] TournamentResetPlanActiveException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                "error.tournament.resetPlan.activeRejected",
                request);
    }

    /**
     * Maps {@link TournamentResetPlanCancelledException} to HTTP 409 Conflict (E48S13,
     * AC-IMPL-TYPED-EXCEPTIONS-AND-HANDLERS).
     *
     * <p>Reset-plan was attempted on a CANCELLED tournament — not permitted.
     */
    @ExceptionHandler(TournamentResetPlanCancelledException.class)
    public ResponseEntity<ApiErrorResponse> handleResetPlanCancelled(
            TournamentResetPlanCancelledException ex, HttpServletRequest request) {
        log.debug("[tm-web] TournamentResetPlanCancelledException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                "error.tournament.resetPlan.cancelledRejected",
                request);
    }

    /**
     * Maps {@link TournamentResetPlanCompletedException} to HTTP 409 Conflict (E48S13,
     * AC-IMPL-TYPED-EXCEPTIONS-AND-HANDLERS).
     *
     * <p>Reset-plan was attempted on a COMPLETED tournament — not permitted.
     */
    @ExceptionHandler(TournamentResetPlanCompletedException.class)
    public ResponseEntity<ApiErrorResponse> handleResetPlanCompleted(
            TournamentResetPlanCompletedException ex, HttpServletRequest request) {
        log.debug("[tm-web] TournamentResetPlanCompletedException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                "error.tournament.resetPlan.completedRejected",
                request);
    }

    /**
     * Maps {@link TournamentResetPlanDraftIdempotentException} to HTTP 409 Conflict (E48S13,
     * AC-IMPL-TYPED-EXCEPTIONS-AND-HANDLERS).
     *
     * <p>Reset-plan was attempted on an already-DRAFT tournament — already in the desired state; no
     * reset needed (idempotent path).
     */
    @ExceptionHandler(TournamentResetPlanDraftIdempotentException.class)
    public ResponseEntity<ApiErrorResponse> handleResetPlanDraftIdempotent(
            TournamentResetPlanDraftIdempotentException ex, HttpServletRequest request) {
        log.debug("[tm-web] TournamentResetPlanDraftIdempotentException: {}", ex.getMessage());
        return buildResponse(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                "error.tournament.resetPlan.draftIdempotent",
                request);
    }

    /**
     * Maps {@link TournamentNotInDraftException} to HTTP 409 Conflict (E48S22,
     * AC-ERROR-HANDLING-NON-DRAFT-STATUS-TYPED-EXCEPTION).
     *
     * <p>Apply was attempted on a tournament that is not in {@code DRAFT} status (e.g., already
     * {@code PLANNED}, {@code ACTIVE}, or {@code CANCELLED}). The operator-actionable message
     * includes the current status and the tournament ID.
     *
     * <p>Uses the typed messageKey {@code draft.error.notInDraftStatus} so the SPA can display a
     * localised error message (de.json {@code draft.error.notInDraftStatus} key).
     */
    @ExceptionHandler(TournamentNotInDraftException.class)
    public ResponseEntity<ApiErrorResponse> handleNotInDraft(
            TournamentNotInDraftException ex, HttpServletRequest request) {
        log.debug("[tm-web] TournamentNotInDraftException: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), ex.getMessageKey(), request);
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
                HttpStatus.CONTENT_TOO_LARGE, ex.getMessage(), "error.audio.tooLarge", request);
    }

    /**
     * Maps Spring's {@link MaxUploadSizeExceededException} to HTTP 413 Payload Too Large.
     *
     * <p>Spring throws this exception before the controller is reached when the multipart size
     * limit is exceeded. Routes to a domain-specific i18n key based on the request URI:
     *
     * <ul>
     *   <li>{@code /api/photo/**} paths → {@code error.photo.tooLarge} (E12S08 AC3/AC4)
     *   <li>All other paths → {@code error.audio.tooLarge} (original audio-upload behaviour,
     *       preserved verbatim)
     * </ul>
     *
     * <p>Migrated from the deleted legacy {@code
     * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} during E21S13 cutover (DEC-22 refactor
     * phase — behavior-preserving). Path-aware routing added by E12S08 to fix the root cause of
     * HTTP 413 on the Mannschaftsfotos page showing a bare audio-domain error message.
     *
     * @since E12S08 (path-aware routing)
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.debug(
                "[tm-web] MaxUploadSizeExceededException on {}: {}",
                request.getRequestURI(),
                ex.getMessage());
        String messageKey =
                request.getRequestURI().startsWith("/api/photo/")
                        ? "error.photo.tooLarge"
                        : "error.audio.tooLarge";
        return buildResponse(
                HttpStatus.CONTENT_TOO_LARGE,
                "File size exceeds the maximum allowed limit.",
                messageKey,
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
     * Maps Spring MVC's {@link HttpMessageNotReadableException} (unparseable / malformed JSON body)
     * to HTTP 400 Bad Request.
     *
     * <p>Spring throws this when Jackson cannot deserialize a {@code @RequestBody}. Without this
     * handler, the catch-all {@link RuntimeException} handler would intercept it first and return
     * 500 — because {@code HttpMessageNotReadableException} extends {@code RuntimeException} (via
     * {@code HttpMessageConversionException}). Explicit handling here short-circuits that path and
     * returns the correct 400 status (E21S19 Scenario E: DraftController PUT with malformed body;
     * DraftController POST /preview with malformed body).
     *
     * @see org.springframework.http.converter.HttpMessageNotReadableException
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug(
                "[tm-web] HttpMessageNotReadableException (malformed request body): {}",
                ex.getMessage());
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Malformed or unreadable request body.",
                "error.badRequest",
                request);
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
    // Certificate domain exceptions (E36S08 Phase 3 — absorbed from deleted
    // CertificateExceptionAdvice)
    // HTTP mappings verbatim per AC-C11-BEHAVIORAL-EQUIVALENCE
    // =========================================================================

    /**
     * Maps {@link CertificateTemplateFormatException} to HTTP 400 Bad Request.
     *
     * <p>Absorbed from the deleted {@code CertificateExceptionAdvice} (E36S08 Phase 2) per
     * AC-C11-BEHAVIORAL-EQUIVALENCE. HTTP mapping and messageKey preserved verbatim.
     *
     * @see <a href="E36S08">E36S08 — Phase 3 RED-first certificate exception absorption</a>
     */
    @ExceptionHandler(CertificateTemplateFormatException.class)
    public ResponseEntity<ApiErrorResponse> handleCertTemplateFormat(
            CertificateTemplateFormatException ex, HttpServletRequest request) {
        log.debug(
                "[tm-web] CertificateTemplateFormatException (certificate module): {}",
                ex.getMessage());
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                "error.certificateTemplate.format",
                request);
    }

    /**
     * Maps {@link CertificateTemplateSizeException} to HTTP 400 Bad Request.
     *
     * <p>Absorbed from the deleted {@code CertificateExceptionAdvice} (E36S08 Phase 2) per
     * AC-C11-BEHAVIORAL-EQUIVALENCE. HTTP mapping and messageKey preserved verbatim.
     *
     * @see <a href="E36S08">E36S08 — Phase 3 RED-first certificate exception absorption</a>
     */
    @ExceptionHandler(CertificateTemplateSizeException.class)
    public ResponseEntity<ApiErrorResponse> handleCertTemplateSize(
            CertificateTemplateSizeException ex, HttpServletRequest request) {
        log.debug(
                "[tm-web] CertificateTemplateSizeException (certificate module): {}",
                ex.getMessage());
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                "error.certificateTemplate.tooLarge",
                request);
    }

    /**
     * Maps {@link CertificateTemplateStorageException} to HTTP 500 Internal Server Error.
     *
     * <p>Absorbed from the deleted {@code CertificateExceptionAdvice} (E36S08 Phase 2) per
     * AC-C11-BEHAVIORAL-EQUIVALENCE. HTTP mapping and messageKey preserved verbatim. Logs at ERROR
     * level.
     *
     * @see <a href="E36S08">E36S08 — Phase 3 RED-first certificate exception absorption</a>
     */
    @ExceptionHandler(CertificateTemplateStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleCertTemplateStorage(
            CertificateTemplateStorageException ex, HttpServletRequest request) {
        log.error(
                "[tm-web] CertificateTemplateStorageException (certificate module): {}",
                ex.getMessage(),
                ex);
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Certificate template storage error.",
                "error.certificateTemplate.storage",
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
