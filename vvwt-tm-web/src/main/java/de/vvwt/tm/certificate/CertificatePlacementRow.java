package de.vvwt.tm.certificate;

import java.util.UUID;

/**
 * Data record for a single team's certificate placement data (E23S08, DEC-35, E46S03).
 *
 * <p>Relocated from the legacy {@code CertificatePlacementRow} class (deleted at E23S10 Cutover-2
 * per DEC-21) to the public API surface of the {@code certificate} bounded context per DEC-35 Item
 * 4 (VOs crossing module boundaries must reside in the public package).
 *
 * <h2>Template variables (E46S03 D-17 hard-cut: all variables tom_-prefixed)</h2>
 *
 * <ul>
 *   <li>{@code tom_placement} — 1-based ordinal integer string (e.g., {@code "1"})
 *   <li>{@code tom_team_name} — team description string
 *   <li>{@code tom_team_photo} — base64 data URI for SVG path, API URL for HTML path, or empty
 *       string if no photo
 *   <li>{@code tom_tournament_name} — tournament description string
 *   <li>{@code tom_date} — locale-formatted date string (e.g., {@code "15. April 2026"})
 *   <li>{@code tom_location} — location display name string
 *   <li>{@code tom_organizer} — organizer name (snapshot from tenants.display_name at INSERT); may
 *       be empty string for legacy tournaments
 * </ul>
 *
 * @param placement 1-based ordinal position (e.g., 1 = first place)
 * @param teamId team UUID (for photo lookup by the controller)
 * @param teamName human-readable team description
 * @param teamPhoto Mustache-ready photo value: base64 data URI (SVG), URL (HTML), or empty string
 * @param tournamentName tournament description
 * @param date locale-formatted tournament date
 * @param location location display name
 * @param organizer organizer name (tenant display_name snapshot); empty string for legacy rows
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
        String location,
        String organizer) {}
