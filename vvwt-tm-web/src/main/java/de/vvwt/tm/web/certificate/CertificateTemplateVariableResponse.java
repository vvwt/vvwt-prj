package de.vvwt.tm.web.certificate;

import de.vvwt.tm.certificate.CertificateTemplateVariable;

/**
 * REST response DTO for a certificate template variable (E36S06, DEC-40 Clause B(d)+B(b)).
 *
 * <p>Q-1a TDD rebuild under DEC-22 Iron Law RED-first discipline (E36S06). Replaces the Q-1b
 * relocated version from E23S09.
 *
 * <p>Field names, types, and order are preserved verbatim per AC-C3-SIGNATURE-PRESERVATION (Brief
 * C-3 — DTO JSON wire contract must match legacy). Wire contract: Jackson serializes all three
 * fields to/from JSON using their Java names.
 *
 * <p>DEC-40 Clause B(d)+B(b) — DTO preserved as web-internal type on field-aliasing grounds (JSON
 * field names may differ from domain enum shape in future evolution) and contract-stability grounds
 * (HTTP response contract must remain stable against future domain type refactors). Clause B(d) and
 * B(b) both apply.
 *
 * <p>Returned by GET {@code /api/certificate/variables} (AC6) — the system-level endpoint that
 * documents available Mustache placeholders for template authors.
 *
 * @param name Mustache placeholder name (without braces), e.g. {@code "placement"}
 * @param type human-readable data type, e.g. {@code "String"}
 * @param example representative example value, e.g. {@code "1"}
 * @see CertificateTemplateController
 * @see DEC-40
 * @see E36S06
 */
public record CertificateTemplateVariableResponse(String name, String type, String example) {

    /**
     * Factory method: maps a domain variable to a response DTO.
     *
     * @param variable the domain variable (never null)
     * @return the corresponding response DTO with matching field values
     */
    public static CertificateTemplateVariableResponse from(CertificateTemplateVariable variable) {
        return new CertificateTemplateVariableResponse(
                variable.name(), variable.type(), variable.example());
    }
}
