package de.vvwt.tm.certificate;

/**
 * Thrown when a filesystem I/O error occurs during certificate template storage (E12S04 AC9 —
 * meaningful error messages for storage failures).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.certificate.CertificateTemplateStorageException} to
 * the new {@code de.vvwt.tm.certificate} Modulith module as part of E23S06 (Q-1b whole-class
 * relocation per DEC-22 §refactor-clause). Javadoc and thrown-from semantics are preserved
 * byte-equivalent (AC-ERROR-HANDLING-UNCHANGED).
 *
 * <p>Maps to HTTP 500 Internal Server Error via {@link
 * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see CertificateTemplateService
 */
public class CertificateTemplateStorageException extends RuntimeException {

    public CertificateTemplateStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
