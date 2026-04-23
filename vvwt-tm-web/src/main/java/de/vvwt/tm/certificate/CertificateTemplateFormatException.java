package de.vvwt.tm.certificate;

/**
 * Thrown when an uploaded certificate template file has an unsupported format or is not well-formed
 * (E12S04 AC7).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.certificate.CertificateTemplateFormatException} to the
 * new {@code de.vvwt.tm.certificate} Modulith module as part of E23S06 (Q-1b whole-class relocation
 * per DEC-22 §refactor-clause). Javadoc and thrown-from semantics are preserved byte-equivalent
 * (AC-ERROR-HANDLING-UNCHANGED).
 *
 * <p>Accepted formats: {@code .html} (HTML + print-CSS) and {@code .svg} (SVG with Mustache
 * placeholders) per E12S01 spike findings. All other extensions are rejected.
 *
 * <p>Maps to HTTP 400 Bad Request via {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see CertificateTemplateService
 */
public class CertificateTemplateFormatException extends RuntimeException {

    public CertificateTemplateFormatException(String message) {
        super(message);
    }
}
