package de.vvwt.tm.infrastructure.web.certificate;

import de.vvwt.tm.domain.certificate.CertificateTemplateVariable;

/**
 * REST response DTO for a certificate template variable (E12S04 AC6).
 *
 * <p>Returned by {@code GET /api/certificate-template/variables} — the system-level endpoint that
 * documents available Mustache placeholders for template authors.
 *
 * @param name Mustache placeholder name (without braces), e.g. {@code "placement"}
 * @param type human-readable data type, e.g. {@code "String"}
 * @param example representative example value, e.g. {@code "1"}
 * @see CertificateTemplateController
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S04.story.md">Story
 *     E12S04</a>
 */
public record CertificateTemplateVariableResponse(String name, String type, String example) {

    /**
     * Factory method: maps a domain variable to a response DTO.
     *
     * @param variable the domain variable
     * @return the corresponding response
     */
    public static CertificateTemplateVariableResponse from(CertificateTemplateVariable variable) {
        return new CertificateTemplateVariableResponse(
                variable.name(), variable.type(), variable.example());
    }
}
