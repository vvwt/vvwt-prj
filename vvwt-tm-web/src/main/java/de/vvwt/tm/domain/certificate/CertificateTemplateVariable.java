package de.vvwt.tm.domain.certificate;

/**
 * Describes a single available Mustache template variable for certificate generation (E12S04 AC6 —
 * variables endpoint).
 *
 * <p>The fixed set of variables is defined by the system's template contract. Template authors use
 * this information to know which Mustache placeholders ({@code {{name}}}) are available when
 * designing their certificate template.
 *
 * @param name Mustache placeholder name (without braces), e.g. {@code "placement"}
 * @param type human-readable data type, e.g. {@code "String"}
 * @param example representative example value, e.g. {@code "1"}
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S04.story.md">Story
 *     E12S04</a>
 */
public record CertificateTemplateVariable(String name, String type, String example) {}
