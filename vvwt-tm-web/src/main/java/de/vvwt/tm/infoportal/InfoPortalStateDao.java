package de.vvwt.tm.infoportal;

import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC DAO for the {@code info_portal_state} table (AC12).
 *
 * <p>Managed by Spring as a {@code @Repository}. Per-tenant DataSource is provided by the TM
 * per-tenant routing infrastructure (DEC-20 DB-per-Tenant).
 *
 * <p>Key contract (AC12):
 *
 * <ul>
 *   <li>Seq generation: atomic {@code UPDATE ... SET last_published_seq = last_published_seq + 1
 *       RETURNING last_published_seq} — TM assigns seq BEFORE posting to info-server.
 *   <li>On 409 FULL_RESYNC: seq is NOT reset to 1; snapshot carries current {@code
 *       last_published_seq}; server overwrites its {@code last_applied_seq} to that value.
 *   <li>DEC-26 three rules enforced via the DEC-26+DEC-46 DAO IT test support in tests.
 *   <li>DEC-46 scope extension: three rules apply to this table per DEC-46 clause #1.
 * </ul>
 *
 * <p>E45S04 — DEC-39/DEC-50 predicate removal: {@code tenant_id = ?} WHERE predicates removed from
 * SELECT/UPDATE operations. {@code upsertRegistration} retains {@code tenantId} parameter
 * (INSERT/MERGE side, AC-INSERT-UPDATE-UNTOUCHED). Under DEC-20 DB-per-Tenant, connection-level
 * routing ensures all rows in the per-tenant DataSource belong to the bound tenant — the
 * discriminator predicate is redundant.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09
 *     AC12</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-26.md">DEC-26</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-46.md">DEC-46</a>
 */
@Repository
public class InfoPortalStateDao {

    private final JdbcTemplate jdbc;

    public InfoPortalStateDao(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Upsert a registration row. If the row already exists (same PK), it is updated with the new
     * token and secret. {@code last_published_seq} is preserved on update (not reset to 0).
     *
     * <p>{@code tenantId} is retained in this method signature (AC-INSERT-UPDATE-UNTOUCHED,
     * E45S04): the MERGE KEY includes {@code tenant_id} as part of the 3-column PK {@code
     * (tenant_id, location_id, tournament_id)}; the INSERT side writes the tenant_id column value;
     * the COALESCE sub-select preserves the existing seq for upsert semantics. These
     * INSERT/MERGE-side writes remain until the Wave-2 Big-Bang-Reset (DEC-50 S05).
     *
     * @param tenantId tenant identifier
     * @param locationId location identifier
     * @param tournamentId tournament identifier
     * @param tournamentToken opaque bearer token from info-server
     * @param perTournamentSecret 32-byte HMAC secret from info-server
     */
    public void upsertRegistration(
            String tenantId,
            String locationId,
            String tournamentId,
            String tournamentToken,
            byte[] perTournamentSecret) {
        jdbc.update(
                """
                MERGE INTO info_portal_state (
                    tenant_id, location_id, tournament_id,
                    tournament_token, per_tournament_secret,
                    last_published_seq, registration_status)
                KEY (tenant_id, location_id, tournament_id)
                VALUES (?, ?, ?, ?, ?, COALESCE(
                    (SELECT last_published_seq FROM info_portal_state
                     WHERE tenant_id = ? AND location_id = ? AND tournament_id = ?), 0),
                    'REGISTERED')
                """,
                tenantId,
                locationId,
                tournamentId,
                tournamentToken,
                perTournamentSecret,
                tenantId,
                locationId,
                tournamentId);
    }

    /**
     * Atomically increments {@code last_published_seq} by 1 and returns the new value (AC12).
     *
     * <p>TM assigns seq BEFORE posting to info-server. This is the production seq-generation path.
     *
     * <p>E45S04 — DEC-39/DEC-50: {@code tenantId} param removed; {@code tenant_id = ?} WHERE
     * predicate dropped. Per DEC-20 DB-per-Tenant, connection-level routing guarantees all rows
     * belong to the bound tenant.
     *
     * @return the new (incremented) sequence number
     * @throws IllegalStateException if the tournament row is not found
     */
    public long incrementAndGetSeq(String locationId, String tournamentId) {
        // H2 supports UPDATE ... SET col = col + 1; then SELECT for updated value
        jdbc.update(
                "UPDATE info_portal_state SET last_published_seq = last_published_seq + 1"
                        + " WHERE location_id = ? AND tournament_id = ?",
                locationId,
                tournamentId);
        Long seq =
                jdbc.queryForObject(
                        "SELECT last_published_seq FROM info_portal_state"
                                + " WHERE location_id = ? AND tournament_id = ?",
                        Long.class,
                        locationId,
                        tournamentId);
        if (seq == null) {
            throw new IllegalStateException(
                    "Tournament row not found for seq increment: "
                            + locationId
                            + "/"
                            + tournamentId);
        }
        return seq;
    }

    /**
     * Returns the current {@code last_published_seq} without incrementing. Used for snapshot-post
     * payloads (seq NOT reset on FULL_RESYNC per AC12).
     *
     * <p>E45S04 — DEC-39/DEC-50: {@code tenantId} param removed; {@code tenant_id = ?} WHERE
     * predicate dropped.
     */
    public long findCurrentSeq(String locationId, String tournamentId) {
        Long seq =
                jdbc.queryForObject(
                        "SELECT last_published_seq FROM info_portal_state"
                                + " WHERE location_id = ? AND tournament_id = ?",
                        Long.class,
                        locationId,
                        tournamentId);
        return seq != null ? seq : 0L;
    }

    /**
     * Updates {@code last_published_at} to the given instant after a successful publish.
     *
     * <p>E45S04 — DEC-39/DEC-50: {@code tenantId} param removed; {@code tenant_id = ?} WHERE
     * predicate dropped.
     */
    public void updateLastPublishedAt(String locationId, String tournamentId, Instant at) {
        jdbc.update(
                "UPDATE info_portal_state SET last_published_at = ?"
                        + " WHERE location_id = ? AND tournament_id = ?",
                java.sql.Timestamp.from(at),
                locationId,
                tournamentId);
    }

    /**
     * Finds the state record for a specific tournament. Returns empty if not registered.
     *
     * <p>E45S04 — DEC-39/DEC-50: {@code tenantId} param removed; {@code tenant_id = ?} WHERE
     * predicate dropped.
     */
    public Optional<InfoPortalStateRecord> findByTournament(
            String locationId, String tournamentId) {
        try {
            InfoPortalStateRecord rec =
                    jdbc.queryForObject(
                            "SELECT tenant_id, location_id, tournament_id,"
                                    + " last_published_seq, tournament_token,"
                                    + " per_tournament_secret, last_published_at,"
                                    + " registration_status"
                                    + " FROM info_portal_state"
                                    + " WHERE location_id = ?"
                                    + "   AND tournament_id = ?",
                            (rs, rowNum) ->
                                    new InfoPortalStateRecord(
                                            rs.getString("tenant_id"),
                                            rs.getString("location_id"),
                                            rs.getString("tournament_id"),
                                            rs.getLong("last_published_seq"),
                                            rs.getString("tournament_token"),
                                            rs.getBytes("per_tournament_secret"),
                                            rs.getTimestamp("last_published_at") != null
                                                    ? rs.getTimestamp("last_published_at")
                                                            .toInstant()
                                                    : null,
                                            rs.getString("registration_status")),
                            locationId,
                            tournamentId);
            return Optional.ofNullable(rec);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
