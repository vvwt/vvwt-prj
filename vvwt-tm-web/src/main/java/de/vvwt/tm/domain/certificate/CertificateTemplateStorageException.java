package de.vvwt.tm.domain.certificate;

/**
 * Thrown when a filesystem I/O error occurs during certificate template storage
 * (E12S04 AC9 — meaningful error messages for storage failures).
 *
 * <p>Maps to HTTP 500 Internal Server Error via
 * {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see CertificateTemplateService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S04.story.md">Story E12S04</a>
 */
public class CertificateTemplateStorageException extends RuntimeException {

    public CertificateTemplateStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
