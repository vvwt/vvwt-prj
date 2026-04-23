package de.vvwt.tm.certificate;

/**
 * Thrown when an uploaded certificate template file exceeds the configured size limit (E12S04 AC7 —
 * file size does not exceed 2 MB).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.certificate.CertificateTemplateSizeException} to the
 * new {@code de.vvwt.tm.certificate} Modulith module as part of E23S06 (Q-1b whole-class relocation
 * per DEC-22 §refactor-clause). Javadoc and thrown-from semantics are preserved byte-equivalent
 * (AC-ERROR-HANDLING-UNCHANGED).
 *
 * <p>Maps to HTTP 400 Bad Request via {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see CertificateTemplateService
 */
public class CertificateTemplateSizeException extends RuntimeException {

    public CertificateTemplateSizeException(String message) {
        super(message);
    }
}
