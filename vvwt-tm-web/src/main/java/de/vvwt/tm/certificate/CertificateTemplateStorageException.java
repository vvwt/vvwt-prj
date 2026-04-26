package de.vvwt.tm.certificate;

/**
 * Thrown when a filesystem I/O error occurs during certificate template storage (E12S04 AC9 —
 * meaningful error messages for storage failures).
 *
 * <p>Rebuilt under DEC-22 Iron Law Q-1a RED-first TDD discipline (E36S04). Constructor signature
 * preserved verbatim (single two-arg constructor) per AC-EXCEPTION-CONSTRUCTORS-PRESERVED and
 * Brief C-3.
 *
 * <p>Maps to HTTP 500 Internal Server Error via {@link
 * de.vvwt.tm.web.certificate.CertificateExceptionAdvice}.
 *
 * @see CertificateTemplateService
 * @see E36S04
 */
public class CertificateTemplateStorageException extends RuntimeException {

    public CertificateTemplateStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
