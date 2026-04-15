package de.vvwt.tm.domain.certificate;

/**
 * Thrown when an uploaded certificate template file has an unsupported format
 * or is not well-formed (E12S04 AC7).
 *
 * <p>Accepted formats: {@code .html} (HTML + print-CSS) and {@code .svg} (SVG with Mustache
 * placeholders) per E12S01 spike findings. All other extensions are rejected.
 *
 * <p>Maps to HTTP 400 Bad Request via {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see CertificateTemplateService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S04.story.md">Story E12S04</a>
 */
public class CertificateTemplateFormatException extends RuntimeException {

    public CertificateTemplateFormatException(String message) {
        super(message);
    }
}
