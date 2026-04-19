package de.vvwt.tm.infrastructure.print;

import java.util.UUID;

/**
 * Data record for a single team's certificate placement data (E12S06).
 *
 * <p>Contains all 6 Session Brief D-4 template variables plus the team UUID (used for photo
 * lookup). Built by {@link CertificateAssembler} from the final-phase standings.
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
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S06.story.md">Story
 *     E12S06</a>
 */
public record CertificatePlacementRow(
        int placement,
        UUID teamId,
        String teamName,
        String teamPhoto,
        String tournamentName,
        String date,
        String location) {}
