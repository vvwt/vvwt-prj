package de.vvwt.info.persistence.tournament;

import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity record for the {@code tournament} table.
 *
 * <p>Represents a registered tournament per tenant+location. Key design points:
 *
 * <ul>
 *   <li>{@code tournament_token}: opaque bearer token distributed via QR code to participants.
 *   <li>{@code per_tournament_secret}: 32-byte HMAC secret per D-X3 c1. Used by the server to
 *       recompute HMAC for URL team-token validation in E38S06. NEVER returned to clients except to
 *       TM at tournament-registration response time.
 *   <li>{@code state}: plain TEXT/CLOB blob per DEC-42 D4 + Brief T-6. Contains the full tournament
 *       state as JSON-encoded UTF-8 envelope.
 *   <li>{@code superseded_at}: {@code null} = active tournament; non-null = superseded (24h grace
 *       per E38S06). Composite uniqueness (tenant_id, location_id) WHERE superseded_at IS NULL is
 *       enforced by a partial unique index on PostgreSQL (AC12) and by a service-layer guard on H2.
 * </ul>
 *
 * @param tournamentId stable unique tournament identifier — PK
 * @param tenantId FK → tenant.tenant_id
 * @param locationId location identifier (operator-assigned)
 * @param tournamentToken opaque bearer token for QR codes
 * @param perTournamentSecret 32-byte HMAC secret (D-X3 c1) — server-side only
 * @param state serialized tournament state as JSON envelope or {@code null} before first snapshot
 * @param lastAppliedSeq sequence number of the last applied delta
 * @param registeredAt UTC timestamp of tournament registration
 * @param supersededAt UTC timestamp when superseded; {@code null} if active
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-42.md">DEC-42</a>
 */
@Table("tournament")
public record TournamentRecord(
        @Id @Column("tournament_id") String tournamentId,
        @Column("tenant_id") String tenantId,
        @Column("location_id") String locationId,
        @Column("tournament_token") String tournamentToken,
        @Column("per_tournament_secret") byte[] perTournamentSecret,
        @Column("state") String state,
        @Column("last_applied_seq") long lastAppliedSeq,
        @Column("registered_at") LocalDateTime registeredAt,
        @Column("superseded_at") LocalDateTime supersededAt) {}
