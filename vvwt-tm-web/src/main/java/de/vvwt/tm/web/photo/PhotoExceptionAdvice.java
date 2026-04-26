package de.vvwt.tm.web.photo;

import de.vvwt.tm.photo.PhotoFormatException;
import de.vvwt.tm.photo.PhotoSizeException;
import de.vvwt.tm.photo.PhotoStorageException;
import de.vvwt.tm.tournament.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Exception advice for {@code de.vvwt.tm.photo.*} domain exceptions thrown from the {@code
 * de.vvwt.tm.web.photo} primary-adapter controllers (E23S04, AC-ERROR-HANDLING).
 *
 * <p>The {@link de.vvwt.tm.tournament.internal.web.GlobalExceptionHandler} already covers {@code
 * de.vvwt.tm.web} as a base package (E22S07). However, it handles the pre-E36S01 legacy photo
 * exception types. The rebuilt {@link de.vvwt.tm.photo} Modulith module (E36S01, Q-1a TDD)
 * introduces canonical exception FQNs ({@link PhotoFormatException}, {@link PhotoSizeException},
 * {@link PhotoStorageException}); a separate advice located in {@code de.vvwt.tm.web} is required
 * to map those types.
 *
 * <h2>Modulith boundary compliance</h2>
 *
 * <p>This advice resides in {@code de.vvwt.tm.web.photo} — inside the {@code web} Modulith module —
 * which declares {@code allowedDependencies = {"photo"}} per DEC-40 Clause A. Importing from {@code
 * de.vvwt.tm.photo.*} is therefore within module-boundary rules. The legacy {@code
 * GlobalExceptionHandler} in {@code tournament.internal.web} cannot import from {@code photo}
 * because {@code tournament.allowedDependencies = {"tenant"}} only.
 *
 * <h2>HTTP mapping (AC-ERROR-HANDLING, preserved verbatim from E12S02)</h2>
 *
 * <ul>
 *   <li>{@link PhotoFormatException} → 400 Bad Request
 *   <li>{@link PhotoSizeException} → 400 Bad Request
 *   <li>{@link PhotoStorageException} → 500 Internal Server Error
 * </ul>
 *
 * @see de.vvwt.tm.tournament.internal.web.GlobalExceptionHandler
 * @see TeamPhotoController
 * @see DEC-40
 * @see E23S04
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@ControllerAdvice(basePackages = {"de.vvwt.tm.web.photo"})
public class PhotoExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(PhotoExceptionAdvice.class);

    /**
     * Maps {@link PhotoFormatException} to HTTP 400 Bad Request.
     *
     * <p>Preserves the HTTP mapping established in E12S02 for the legacy
     * {@link PhotoFormatException} handler (rebuilt at canonical FQN per E36S01).
     */
    @ExceptionHandler(PhotoFormatException.class)
    public ResponseEntity<ApiErrorResponse> handlePhotoFormat(
            PhotoFormatException ex, HttpServletRequest request) {
        log.debug("[tm-web] PhotoFormatException (photo module): {}", ex.getMessage());
        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                ex.getMessage(),
                                "error.photo.format")
                        .path(request.getRequestURI())
                        .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Maps {@link PhotoSizeException} to HTTP 400 Bad Request.
     *
     * <p>Preserves the HTTP mapping established in E12S02 for the legacy
     * {@link PhotoSizeException} handler (rebuilt at canonical FQN per E36S01).
     */
    @ExceptionHandler(PhotoSizeException.class)
    public ResponseEntity<ApiErrorResponse> handlePhotoSize(
            PhotoSizeException ex, HttpServletRequest request) {
        log.debug("[tm-web] PhotoSizeException (photo module): {}", ex.getMessage());
        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                ex.getMessage(),
                                "error.photo.tooLarge")
                        .path(request.getRequestURI())
                        .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Maps {@link PhotoStorageException} to HTTP 500 Internal Server Error.
     *
     * <p>Preserves the HTTP mapping established in E12S02 for the legacy
     * {@link PhotoStorageException} handler (rebuilt at canonical FQN per E36S01).
     */
    @ExceptionHandler(PhotoStorageException.class)
    public ResponseEntity<ApiErrorResponse> handlePhotoStorage(
            PhotoStorageException ex, HttpServletRequest request) {
        log.error("[tm-web] PhotoStorageException (photo module): {}", ex.getMessage(), ex);
        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                                "Photo storage error.",
                                "error.photo.storage")
                        .path(request.getRequestURI())
                        .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
