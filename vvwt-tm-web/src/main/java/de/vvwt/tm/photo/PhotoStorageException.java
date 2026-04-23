package de.vvwt.tm.photo;

/**
 * Thrown when a filesystem I/O error occurs during photo storage operations (E12S02 AC8, E23S01).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.photo.PhotoStorageException} to the canonical {@code
 * de.vvwt.tm.photo} Modulith module per DEC-21, DEC-35 (exceptions in context root per Brief v4
 * D-14), and E23S01. Javadoc and thrown-from semantics preserved verbatim
 * (AC-ERROR-HANDLING-UNCHANGED). The legacy package {@code de.vvwt.tm.domain.photo} remains on the
 * classpath until E23S05 Cutover-1.
 *
 * <p>Maps to HTTP 500 via {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}. The message
 * is included in the response body to aid operator diagnosis (AC8).
 *
 * @see PhotoStorageService
 * @since E12S02
 */
public class PhotoStorageException extends RuntimeException {

    public PhotoStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public PhotoStorageException(String message) {
        super(message);
    }
}
