package de.vvwt.tm.certificate;

/**
 * Thrown when an uploaded certificate template file exceeds the configured size limit (E12S04 AC7 —
 * file size does not exceed configured maximum).
 *
 * <p>Rebuilt under DEC-22 Iron Law Q-1a RED-first TDD discipline (E36S04). Constructor signature
 * preserved verbatim per AC-EXCEPTION-CONSTRUCTORS-PRESERVED and Brief C-3.
 *
 * <p>Maps to HTTP 400 Bad Request via {@link de.vvwt.tm.web.GlobalExceptionHandler} (E36S08 Phase 3
 * — {@code CertificateExceptionAdvice} deleted in Phase 2).
 *
 * @see CertificateTemplateService
 * @see E36S04
 */
public class CertificateTemplateSizeException extends RuntimeException {

    public CertificateTemplateSizeException(String message) {
        super(message);
    }
}
