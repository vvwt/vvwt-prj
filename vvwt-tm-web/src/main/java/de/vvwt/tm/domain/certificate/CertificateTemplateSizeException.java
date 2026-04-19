package de.vvwt.tm.domain.certificate;

/**
 * Thrown when an uploaded certificate template file exceeds the configured size limit (E12S04 AC7 —
 * file size does not exceed 2 MB).
 *
 * <p>Maps to HTTP 400 Bad Request via {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see CertificateTemplateService
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S04.story.md">Story
 *     E12S04</a>
 */
public class CertificateTemplateSizeException extends RuntimeException {

    public CertificateTemplateSizeException(String message) {
        super(message);
    }
}
