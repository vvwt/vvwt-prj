package de.vvwt.tm.photo;

/**
 * Thrown when an uploaded photo exceeds the configured size limit (E12S02 AC7, E23S01).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.photo.PhotoSizeException} to the canonical {@code
 * de.vvwt.tm.photo} Modulith module per DEC-21, DEC-35 (exceptions in context root per Brief v4
 * D-14), and E23S01. Javadoc and thrown-from semantics preserved verbatim
 * (AC-ERROR-HANDLING-UNCHANGED). The legacy package {@code de.vvwt.tm.domain.photo} remains on the
 * classpath until E23S05 Cutover-1.
 *
 * <p>Maps to HTTP 400 via {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see PhotoStorageService
 * @since E12S02
 */
public class PhotoSizeException extends RuntimeException {

    public PhotoSizeException(String message) {
        super(message);
    }
}
