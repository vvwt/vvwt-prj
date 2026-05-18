// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.certificate;

/**
 * Response DTO for the certificate print-defaults pre-fill endpoint (E68S02 AC1).
 *
 * <p>Carries the organizer and venue values that the Svelte {@code Certificates.svelte} component
 * pre-populates into the override input fields on mount. Both fields are non-null (empty string
 * when the stored value is absent).
 *
 * <p>DEC-40 Clause B(a): field-omission grounds — this DTO exposes only the two fields needed for
 * pre-fill; the full {@code Tournament} entity is not exposed.
 *
 * @param organizer the tournament's stored organizer (empty string if null in DB)
 * @param venue the current live location display name from {@link
 *     de.vvwt.tm.tenant.LocationDisplayResolver}
 * @since E68S02
 */
public record CertificatePrintDefaultsResponse(String organizer, String venue) {}
