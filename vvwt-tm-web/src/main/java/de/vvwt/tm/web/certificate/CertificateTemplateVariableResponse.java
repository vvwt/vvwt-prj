package de.vvwt.tm.web.certificate;

import de.vvwt.tm.certificate.CertificateTemplateVariable;

/**
 * REST response DTO for a certificate template variable (E23S09, DEC-40 Clause B(d)+B(b)).
 *
 * <p>Relocated from {@code
 * de.vvwt.tm.infrastructure.web.certificate.CertificateTemplateVariableResponse} to {@code
 * de.vvwt.tm.web.certificate.*} per DEC-40 Clause D + DEC-22 §refactor-clause (Q-1b).
 *
 * <p>DEC-40 Clause B(d)+B(b) — DTO preserved as web-internal type on field-aliasing grounds (JSON
 * field names may differ from domain enum shape) and contract-stability grounds (HTTP response
 * contract must remain stable against future domain enum refactors).
 *
 * <p>Returned by {@code GET /api/certificate-template/variables} — the system-level endpoint that
 * documents available Mustache placeholders for template authors.
 *
 * @param name Mustache placeholder name (without braces), e.g. {@code "placement"}
 * @param type human-readable data type, e.g. {@code "String"}
 * @param example representative example value, e.g. {@code "1"}
 * @see CertificateTemplateController
 * @see DEC-40
 * @see E23S09
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
