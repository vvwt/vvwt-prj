package de.vvwt.tm.tournament.internal.web;

import de.vvwt.tm.tournament.ApiErrorResponse;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import de.vvwt.tm.tournament.exceptions.ForbiddenException;
import de.vvwt.tm.tournament.exceptions.TooManyRequestsException;
import de.vvwt.tm.tournament.exceptions.UnauthorizedException;
import de.vvwt.tm.tournament.exceptions.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Global exception handler for the {@code tournament} bounded context (E21S10,
 * AC-TDD-GlobalExceptionHandler, AC-PKG-GlobalExceptionHandler, inventory row 450).
 *
 * <p>Translates the five S09 boundary-API exceptions and unhandled {@link RuntimeException}s into
 * consistent {@link ApiErrorResponse} JSON bodies.
 *
 * <h2>DEC-21 package discipline</h2>
 *
 * <p>Placed at {@code de.vvwt.tm.tournament.internal.web.*} because while its effects cross
 * contexts (HTTP error responses are global), its implementation is tournament-internal (per D-8).
 * Only the contract — {@link ApiErrorResponse} — is public.
 *
 * <h2>Scope-bounded (AC-GLOBAL-EXCEPTION-HANDLER-SCOPE-BOUNDED)</h2>
 *
 * <p>{@code @ControllerAdvice(basePackages = "de.vvwt.tm.tournament")} limits this handler to
 * exceptions thrown from controllers in the {@code tournament} package tree. Legacy exceptions
 * ({@code de.vvwt.tm.domain.timer.InvalidTimerUrlException}, {@code
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
@ControllerAdvice(basePackages = "de.vvwt.tm.tournament")
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
}
