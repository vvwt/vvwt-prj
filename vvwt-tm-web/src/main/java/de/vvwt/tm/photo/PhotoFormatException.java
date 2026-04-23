package de.vvwt.tm.photo;

/**
 * Thrown when an uploaded file is not a JPEG or PNG image (E12S02 AC7, E23S01).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.photo.PhotoFormatException} to the canonical {@code
 * de.vvwt.tm.photo} Modulith module per DEC-21, DEC-35 (exceptions in context root per Brief v4
 * D-14), and E23S01. Javadoc and thrown-from semantics preserved verbatim
 * (AC-ERROR-HANDLING-UNCHANGED). The legacy package {@code de.vvwt.tm.domain.photo} remains on the
 * classpath until E23S05 Cutover-1.
 *
 * <p>Maps to HTTP 400 via {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}. Only {@code
 * image/jpeg} and {@code image/png} are accepted (AC7).
 *
 * @see PhotoStorageService
 * @since E12S02
 */
public class PhotoFormatException extends RuntimeException {

    public PhotoFormatException(String message) {
        super(message);
    }
}
