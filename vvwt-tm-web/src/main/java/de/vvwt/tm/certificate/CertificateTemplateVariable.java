// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.certificate;

/**
 * Describes a single available Mustache template variable for certificate generation (E12S04 AC6 —
 * variables endpoint).
 *
 * <p>Rebuilt under DEC-22 Iron Law Q-1a RED-first TDD discipline (E36S04). All field names, types,
 * and order are preserved verbatim per AC-RECORD-FIELDS-PRESERVED-VARIABLE and Brief C-3
 * (signature-preservation).
 *
 * <p>The fixed set of variables is defined by the system's template contract. Template authors use
 * this information to know which Mustache placeholders ({@code {{name}}}) are available when
 * designing their certificate template.
 *
 * @param name Mustache placeholder name (without braces), e.g. {@code "placement"}
 * @param type human-readable data type, e.g. {@code "String"}
 * @param example representative example value, e.g. {@code "1"}
 * @see CertificateTemplateService
 * @see E36S04
 */
public record CertificateTemplateVariable(String name, String type, String example) {}
