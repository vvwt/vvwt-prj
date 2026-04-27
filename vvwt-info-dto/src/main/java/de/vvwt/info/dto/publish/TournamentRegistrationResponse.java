package de.vvwt.info.dto.publish;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response payload for a successful tournament registration (AC2 / Brief D-X2 c).
 *
 * <p>Returned inside an {@code Envelope<TournamentRegistrationResponse>} by the server:
 *
 * <ul>
 *   <li>{@code tournament_token}: opaque Base64URL-encoded bearer token (256-bit random) for QR
 *       code distribution.
 *   <li>{@code per_tournament_secret}: 32-byte HMAC secret (D-X3 c1). The TM uses this to derive
 *       per-team URLs: {@code team_token = HMAC-SHA256(per_tournament_secret, team_uuid)}. The
 *       server stores this secret; it is NOT returned to any party other than the TM at
 *       registration response time.
 *   <li>{@code schema_version}: wire schema version (always {@code "1.0"} in Phase 1).
 * </ul>
 *
 * @param tournamentToken opaque bearer token for QR-code distribution
 * @param perTournamentSecret 32-byte raw HMAC secret; TM MUST NOT store this on disk in plaintext
 * @param schemaVersion wire schema version for this response
 * @see <a href="../../../../../../../../docs/governance/stories/E38S05.story.md">E38S05 AC2</a>
 */
public record TournamentRegistrationResponse(
        @JsonProperty("tournament_token") String tournamentToken,
        @JsonProperty("per_tournament_secret") byte[] perTournamentSecret,
        @JsonProperty("schema_version") String schemaVersion) {}
