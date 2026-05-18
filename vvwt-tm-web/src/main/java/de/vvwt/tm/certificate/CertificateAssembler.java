// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
 * <p>Extracted from the legacy {@code CertificateAssembler} concrete class (deleted at E23S10
 * Cutover-2 per DEC-21) via TDD RED-first per DEC-22 Q-1a.
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
 *     <h2>E68S02 — Ephemeral per-print organizer/venue override</h2>
 *     <p>The overloaded {@link #buildSvgRows(Tournament, List, String, String)} and {@link
 *     #buildHtmlRows(Tournament, List, String, String)} methods accept an optional {@code
 *     organizerOverride}. When non-blank, the override replaces {@code tournament.getOrganizer()}
 *     in the rendered certificate; when null or blank, the stored value is used as before (AC5
 *     AC6). The venue is passed as {@code locationDisplayName} to both overloads; the controller is
 *     responsible for deciding whether to call {@link de.vvwt.tm.tenant.LocationDisplayResolver}
 *     live or to pass a supplied override (AC5 Notes: no-override path MUST remain a live read, not
 *     a screen-time snapshot).
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
     * <p>For phases that have {@link de.vvwt.tm.tournament.TeamAvatarRating}s (match-played
     * phases): placement is sorted by {@link de.vvwt.tm.tournament.TeamAvatarRating#compareTo}
     * (points DESC, setQuotient DESC, ballQuotient DESC, isWithoutAssessment last) per DEC-33.
     * Placement ordinals are 1-based.
     *
     * <p>For phases with no ratings (e.g. a Siegerehrung / award-ceremony phase that has zero
     * matches by design — E12S09 fix): placement is derived from each {@link
     * de.vvwt.tm.tournament.TeamAvatar}'s {@code groupPosition} (DEC-9 structural identity).
     * groupPosition 1..N maps directly to places 1..N within the single group. This fixes the false
     * HTTP 400 "no game results" for fully completed tournaments whose final phase is a
     * Siegerehrung phase.
     *
     * <p>Returns an empty list only when the final phase has no avatars with an assigned team
     * ({@code teamId == null} for all avatars) — this signals that team assignment has not been
     * completed yet (AC4 legitimate error case preserved).
     *
     * @param tournamentId the tournament UUID
     * @param finalPhase the phase to compute standings from
     * @return ordered list of AvatarPlacement tuples, placement = index + 1; empty iff no teams
     *     have been assigned to the final phase's avatar slots
     */
    List<AvatarPlacement> computePlacementOrder(UUID tournamentId, Phase finalPhase);

    /**
     * Builds {@link CertificatePlacementRow} list for SVG certificate rendering.
     *
     * <p>SVG path: {@code teamPhoto} is a base64 data URI embedded inline.
     *
     * <p>The organizer value is taken from {@code tournament.getOrganizer()} (null → empty string).
     *
     * @param tournament the tournament entity
     * @param placements the ordered placement list from {@link #computePlacementOrder}
     * @param locationDisplayName the location display name ({{location}} variable)
     * @return ordered list of placement rows with base64-encoded photo values
     */
    List<CertificatePlacementRow> buildSvgRows(
            Tournament tournament, List<AvatarPlacement> placements, String locationDisplayName);

    /**
     * Builds {@link CertificatePlacementRow} list for SVG certificate rendering with an optional
     * per-print organizer override (E68S02 AC2 AC3).
     *
     * <p>When {@code organizerOverride} is non-null and non-blank, it replaces {@code
     * tournament.getOrganizer()} in every row. Blank/null override falls back to the stored
     * organizer (AC5 AC6).
     *
     * <p>SVG path: {@code teamPhoto} is a base64 data URI embedded inline.
     *
     * @param tournament the tournament entity
     * @param placements the ordered placement list from {@link #computePlacementOrder}
     * @param locationDisplayName the location display name ({{location}} variable)
     * @param organizerOverride ephemeral organizer override; null or blank → use stored organizer
     * @return ordered list of placement rows with base64-encoded photo values
     * @since E68S02
     */
    List<CertificatePlacementRow> buildSvgRows(
            Tournament tournament,
            List<AvatarPlacement> placements,
            String locationDisplayName,
            String organizerOverride);

    /**
     * Builds {@link CertificatePlacementRow} list for HTML certificate rendering.
     *
     * <p>HTML path: {@code teamPhoto} is a relative URL to the photo API endpoint.
     *
     * <p>The organizer value is taken from {@code tournament.getOrganizer()} (null → empty string).
     *
     * @param tournament the tournament entity
     * @param placements the ordered placement list from {@link #computePlacementOrder}
     * @param locationDisplayName the location display name
     * @return ordered list of placement rows with photo URL values
     */
    List<CertificatePlacementRow> buildHtmlRows(
            Tournament tournament, List<AvatarPlacement> placements, String locationDisplayName);

    /**
     * Builds {@link CertificatePlacementRow} list for HTML certificate rendering with an optional
     * per-print organizer override (E68S02 AC2 AC3).
     *
     * <p>When {@code organizerOverride} is non-null and non-blank, it replaces {@code
     * tournament.getOrganizer()} in every row. Blank/null override falls back to the stored
     * organizer (AC5 AC6).
     *
     * <p>HTML path: {@code teamPhoto} is a relative URL to the photo API endpoint.
     *
     * @param tournament the tournament entity
     * @param placements the ordered placement list from {@link #computePlacementOrder}
     * @param locationDisplayName the location display name
     * @param organizerOverride ephemeral organizer override; null or blank → use stored organizer
     * @return ordered list of placement rows with photo URL values
     * @since E68S02
     */
    List<CertificatePlacementRow> buildHtmlRows(
            Tournament tournament,
            List<AvatarPlacement> placements,
            String locationDisplayName,
            String organizerOverride);

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
