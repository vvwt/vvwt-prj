package de.vvwt.tm.certificate;

import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.Tournament;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for certificate placement data assembly and Mustache rendering (E23S08, DEC-35).
 *
 * <p>This interface is the public API of the {@code certificate} module's assembler port. The sole
 * production implementation is {@code de.vvwt.tm.certificate.internal.DefaultCertificateAssembler}
 * per DEC-35 naming canon ({@code Default*} prefix, implementation in {@code .internal}).
 *
 * <p>Extracted from {@code de.vvwt.tm.infrastructure.print.CertificateAssembler} via TDD RED-first
 * per DEC-22 Q-1a. The legacy concrete class is retained during the parallel phase; it is deleted
 * at E23S10 Cutover-2 per DEC-21.
 *
 * <p>Value objects {@link CertificatePlacementRow} and {@link AvatarPlacement} reside on the public
 * type surface per DEC-35 Item 4 (VOs crossing module boundaries). {@code AvatarPlacement} is a
 * nested record on this interface to preserve existing consumer import sites.
 *
 * <h2>Usage contract</h2>
 *
 * <p>Cross-module consumers (e.g., {@code PrintController} in {@code de.vvwt.tm.web}) MUST
 * reference this interface type, never the concrete {@code DefaultCertificateAssembler} (DEC-36).
 *
 * @see CertificatePlacementRow
 * @see AvatarPlacement
 * @since E23S08
 */
public interface CertificateAssembler {

    /**
     * Returns the final phase (highest sequenceNumber) for the given tournament, or empty if the
     * tournament has no phases.
     *
     * @param tournamentId the tournament UUID (tenant-scoped)
     * @return the final phase, or empty if no phases exist
     */
    Optional<Phase> getFinalPhase(UUID tournamentId);

    /**
     * Computes the certificate placement list for the given final phase.
     *
     * <p>Returns an empty list if the phase has no {@link de.vvwt.tm.tournament.TeamAvatarRating}s
     * — this signals that no matches have been played yet.
     *
     * <p>Placement ordering follows the named algebraic invariant: result is sorted by {@link
     * de.vvwt.tm.tournament.TeamAvatarRating#compareTo} (points DESC, setQuotient DESC,
     * ballQuotient DESC, isWithoutAssessment last) per DEC-33. Placement ordinals are 1-based.
     *
     * @param tournamentId the tournament UUID
     * @param finalPhase the phase to compute standings from
     * @return ordered list of AvatarPlacement tuples, placement = index + 1
     */
    List<AvatarPlacement> computePlacementOrder(UUID tournamentId, Phase finalPhase);

    /**
     * Builds {@link CertificatePlacementRow} list for SVG certificate rendering.
     *
     * <p>SVG path: {@code teamPhoto} is a base64 data URI embedded inline.
     *
     * @param tournament the tournament entity
     * @param placements the ordered placement list from {@link #computePlacementOrder}
     * @param locationDisplayName the location display name ({{location}} variable)
     * @return ordered list of placement rows with base64-encoded photo values
     */
    List<CertificatePlacementRow> buildSvgRows(
            Tournament tournament, List<AvatarPlacement> placements, String locationDisplayName);

    /**
     * Builds {@link CertificatePlacementRow} list for HTML certificate rendering.
     *
     * <p>HTML path: {@code teamPhoto} is a relative URL to the photo API endpoint.
     *
     * @param tournament the tournament entity
     * @param placements the ordered placement list from {@link #computePlacementOrder}
     * @param locationDisplayName the location display name
     * @return ordered list of placement rows with photo URL values
     */
    List<CertificatePlacementRow> buildHtmlRows(
            Tournament tournament, List<AvatarPlacement> placements, String locationDisplayName);

    /**
     * Renders a certificate SVG template for a single team placement row.
     *
     * <p>Uses jmustache with {@code escapeHTML(false)} and {@code defaultValue("")} per E12S01 AC6
     * finding (prevents base64 data URI corruption).
     *
     * @param templateContent the raw Mustache template string (SVG content)
     * @param row the placement row with all template variable values
     * @return the rendered SVG as a UTF-8 string
     * @throws com.samskivert.mustache.MustacheException if the template is malformed
     */
    String renderSvgTemplate(String templateContent, CertificatePlacementRow row);

    /**
     * Converts a {@link CertificatePlacementRow} to a jmustache-compatible attribute map.
     *
     * @param row the placement row
     * @return attribute map for Mustache model
     */
    Map<String, Object> toMustacheMap(CertificatePlacementRow row);

    // -------------------------------------------------------------------------
    // Nested value object — on public interface surface per DEC-35 Item 4
    // -------------------------------------------------------------------------

    /**
     * Intermediate record linking a placement ordinal to an avatar's team ID (DEC-35 Item 4 VO).
     *
     * <p>Nested on the interface to preserve existing consumer import sites in {@code
     * PrintController} which currently imports {@code CertificateAssembler.AvatarPlacement}.
     *
     * @param placement 1-based ordinal
     * @param teamId the team UUID
     * @param avatarId the avatar UUID (for identity tracing)
     */
    record AvatarPlacement(int placement, UUID teamId, UUID avatarId) {}
}
