package de.vvwt.tm.certificate;

/**
 * Thrown when an uploaded certificate template file has an unsupported format or is not well-formed
 * (E12S04 AC7).
 *
 * <p>Rebuilt under DEC-22 Iron Law Q-1a RED-first TDD discipline (E36S04). Constructor signature
 * preserved verbatim per AC-EXCEPTION-CONSTRUCTORS-PRESERVED and Brief C-3.
 *
 * <p>Accepted formats: {@code .html} (HTML + print-CSS) and {@code .svg} (SVG with Mustache
 * placeholders) per E12S01 spike findings. All other extensions are rejected.
 *
 * <p>Maps to HTTP 400 Bad Request via {@link de.vvwt.tm.web.certificate.CertificateExceptionAdvice}.
 *
 * @see CertificateTemplateService
 * @see E36S04
 */
public class CertificateTemplateFormatException extends RuntimeException {

    public CertificateTemplateFormatException(String message) {
        super(message);
    }
}
