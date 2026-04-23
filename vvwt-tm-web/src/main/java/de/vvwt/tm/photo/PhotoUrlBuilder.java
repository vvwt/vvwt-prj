package de.vvwt.tm.photo;

import java.util.UUID;

/**
 * Port for constructing team-photo URL paths (E23S02, D-17 decoupling).
 *
 * <p>Provides the single source of truth for the photo URL schema during the parallel phase
 * (E23S01–E23S04). Decouples {@code CertificateAssembler} (and any future consumer needing photo
 * URLs) from the photo URL schema, enabling the photo atomic cutover (E23S05) to update the URL
 * template in a single commit without coupling certificate's cutover to photo's URL changes.
 *
 * <p>Emits the OLD URL pattern during the parallel phase (byte-identical to the hardcoded string
 * formerly in {@code CertificateAssembler.java:377}): {@code
 * /api/tournaments/{tournamentId}/teams/{teamId}/photo}.
 *
 * <p>At E23S05 Cutover-1, the URL template is updated atomically in {@link
 * de.vvwt.tm.photo.internal.DefaultPhotoUrlBuilder} — the interface contract remains stable and no
 * consumer change is needed at cutover time.
 *
 * <p>Authorizing decisions: DEC-35 (interface in public Modulith package, implementation in {@code
 * .internal}; {@code Default*} naming canon), DEC-22 (TDD Iron Law — RED-first, E23S02).
 *
 * @throws NullPointerException if either argument to {@link #buildTeamPhotoUrl} is null
 * @see de.vvwt.tm.photo.internal.DefaultPhotoUrlBuilder
 * @since E23S02
 */
public interface PhotoUrlBuilder {

    /**
     * Builds the canonical photo URL for a given team in a tournament.
     *
     * <p>Invariant: {@code buildTeamPhotoUrl(x, y)} produces {@code "/api/tournaments/" + x +
     * "/teams/" + y + "/photo"} for all non-null inputs {@code x} and {@code y}. Cites Brief v4
     * D-17 (2026-04-23); URL template is updated atomically at E23S05 Cutover-1.
     *
     * @param tournamentId the tournament UUID (must not be null)
     * @param teamId the team UUID (must not be null)
     * @return the relative URL string — never null, never empty for valid inputs
     * @throws NullPointerException if {@code tournamentId} or {@code teamId} is null
     */
    String buildTeamPhotoUrl(UUID tournamentId, UUID teamId);
}
