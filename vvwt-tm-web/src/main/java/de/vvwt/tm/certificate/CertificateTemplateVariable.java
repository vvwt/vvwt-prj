package de.vvwt.tm.certificate;

/**
 * Describes a single available Mustache template variable for certificate generation (E12S04 AC6 —
 * variables endpoint).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.certificate.CertificateTemplateVariable} to the new
 * {@code de.vvwt.tm.certificate} Modulith module as part of E23S06 (Q-1b whole-class relocation per
 * DEC-22 §refactor-clause). The record structure is byte-equivalent to the legacy record.
 *
 * <p>The fixed set of variables is defined by the system's template contract. Template authors use
 * this information to know which Mustache placeholders ({@code {{name}}}) are available when
 * designing their certificate template.
 *
 * @param name Mustache placeholder name (without braces), e.g. {@code "placement"}
 * @param type human-readable data type, e.g. {@code "String"}
 * @param example representative example value, e.g. {@code "1"}
 * @see CertificateTemplateService
 */
public record CertificateTemplateVariable(String name, String type, String example) {}
