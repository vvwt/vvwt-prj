package de.vvwt.tm.certificate;

import java.util.UUID;

/**
 * Data record for a single team's certificate placement data (E23S08, DEC-35).
 *
 * <p>Relocated from {@code de.vvwt.tm.infrastructure.print.CertificatePlacementRow} to the public
 * API surface of the {@code certificate} bounded context per DEC-35 Item 4 (VOs crossing module
 * boundaries must reside in the public package). The legacy class at {@code
 * infrastructure.print.CertificatePlacementRow} is retained during the parallel phase; it is
 * deleted at E23S10 Cutover-2 per DEC-21.
 *
 * <h2>Template variables (AC6)</h2>
 *
 * <ul>
 *   <li>{@code placement} — 1-based ordinal integer string (e.g., {@code "1"})
 *   <li>{@code teamName} — team description string
 *   <li>{@code teamPhoto} — base64 data URI for SVG path, API URL for HTML path, or empty string if
 *       no photo
 *   <li>{@code tournamentName} — tournament description string
 *   <li>{@code date} — formatted date string (e.g., {@code "15. April 2026"})
 *   <li>{@code location} — location display name string
 * </ul>
 *
 * @param placement 1-based ordinal position (e.g., 1 = first place)
 * @param teamId team UUID (for photo lookup by the controller)
 * @param teamName human-readable team description
 * @param teamPhoto Mustache-ready photo value: base64 data URI (SVG), URL (HTML), or empty string
 * @param tournamentName tournament description
 * @param date formatted tournament date
 * @param location location display name
 * @see CertificateAssembler
 * @since E23S08
 */
public record CertificatePlacementRow(
        int placement,
        UUID teamId,
        String teamName,
        String teamPhoto,
        String tournamentName,
        String date,
        String location) {}
