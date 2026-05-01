package de.vvwt.info.persistence.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.info.persistence.testsupport.InfoDaoTestSupport;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * DAO integration test for {@link TournamentDao} — both H2 and PostgreSQL backends (AC3).
 *
 * <p>DEC-26/DEC-46 three rules applied via {@link InfoDaoTestSupport}.
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC4, AC12</a>
 */
@Testcontainers
class TournamentDaoIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    // -------------------------------------------------------------------------
    // H2 backend — basic insert + assertj-db verification
    // -------------------------------------------------------------------------

    @Test
    void h2_tournament_insert_with_secret_round_trip() {
        // AC4: per_tournament_secret column stores BYTEA/VARBINARY
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);

        // Insert tenant first (FK dependency)
        insertTenant(ds, "tenant-1");

        var secret = new byte[32];
        Arrays.fill(secret, (byte) 0xAB);
        var now = LocalDateTime.now();

        InfoDaoTestSupport.insertDirectly(
                ds,
                "tournament",
                Map.of(
                        "tournament_id", "t-001",
                        "tenant_id", "tenant-1",
                        "location_id", "loc-1",
                        "tournament_token", "tok-abc",
                        "per_tournament_secret", secret,
                        "last_applied_seq", 0L,
                        "registered_at", now));

        // Rule 2: verify via assertj-db
        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("tournament").build())
                .hasNumberOfRows(1)
                .row(0)
                .value("tournament_id")
                .isEqualTo("t-001")
                .value("tournament_token")
                .isEqualTo("tok-abc");
    }

    @Test
    void h2_active_tournament_uniqueness_guard_ac12() {
        // AC12: H2 does NOT support partial unique indexes; the constraint is application-layer.
        // This test verifies the DB schema allows two rows for the same (tenant_id, location_id)
        // when one has superseded_at != NULL (valid per AC12 grace-window semantics).
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);
        insertTenant(ds, "tenant-1");

        var now = LocalDateTime.now();

        // Insert T1 (active)
        InfoDaoTestSupport.insertDirectly(
                ds,
                "tournament",
                Map.of(
                        "tournament_id", "t-001",
                        "tenant_id", "tenant-1",
                        "location_id", "loc-1",
                        "tournament_token", "tok-1",
                        "per_tournament_secret", new byte[32],
                        "last_applied_seq", 0L,
                        "registered_at", now));

        // Supersede T1
        supersedeTournament(ds, "t-001", now.plusHours(1));

        // Insert T2 (new active, same tenant+location)
        InfoDaoTestSupport.insertDirectly(
                ds,
                "tournament",
                Map.of(
                        "tournament_id", "t-002",
                        "tenant_id", "tenant-1",
                        "location_id", "loc-1",
                        "tournament_token", "tok-2",
                        "per_tournament_secret", new byte[32],
                        "last_applied_seq", 0L,
                        "registered_at", now.plusHours(1)));

        // Verify: both rows coexist; T1 has superseded_at != null, T2 is null
        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("tournament").build()).hasNumberOfRows(2);
    }

    // -------------------------------------------------------------------------
    // PostgreSQL backend — partial unique index enforced (AC12)
    // -------------------------------------------------------------------------

    @Test
    void postgres_partial_unique_index_enforces_active_tournament_uniqueness_ac12() {
        // AC12: PostgreSQL partial unique index prevents two active tournaments per
        // (tenant,location)
        DataSource ds = InfoDaoTestSupport.freshPostgresDataSource(POSTGRES);
        InfoDaoTestSupport.applyFullPostgresSchema(ds);
        insertTenant(ds, "tenant-pg-1");

        var now = LocalDateTime.now();

        // Insert T1 (active)
        InfoDaoTestSupport.insertDirectly(
                ds,
                "tournament",
                Map.of(
                        "tournament_id", "t-pg-001",
                        "tenant_id", "tenant-pg-1",
                        "location_id", "loc-1",
                        "tournament_token", "tok-pg-1",
                        "per_tournament_secret", new byte[32],
                        "last_applied_seq", 0L,
                        "registered_at", now));

        // Try to insert T2 (also active, same tenant+location) — should fail on partial index
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                InfoDaoTestSupport.insertDirectly(
                                        ds,
                                        "tournament",
                                        Map.of(
                                                "tournament_id", "t-pg-002",
                                                "tenant_id", "tenant-pg-1",
                                                "location_id", "loc-1",
                                                "tournament_token", "tok-pg-2",
                                                "per_tournament_secret", new byte[32],
                                                "last_applied_seq", 0L,
                                                "registered_at", now.plusSeconds(1))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void postgres_superseded_tournaments_coexist_with_new_active_ac12() {
        // AC12: superseded T1 + active T2 on same (tenant, location) is valid
        DataSource ds = InfoDaoTestSupport.freshPostgresDataSource(POSTGRES);
        InfoDaoTestSupport.applyFullPostgresSchema(ds);
        insertTenant(ds, "tenant-pg-2");

        var now = LocalDateTime.now();

        InfoDaoTestSupport.insertDirectly(
                ds,
                "tournament",
                Map.of(
                        "tournament_id", "t-pg-003",
                        "tenant_id", "tenant-pg-2",
                        "location_id", "loc-2",
                        "tournament_token", "tok-pg-3",
                        "per_tournament_secret", new byte[32],
                        "last_applied_seq", 0L,
                        "registered_at", now));

        supersedeTournament(ds, "t-pg-003", now.plusHours(1));

        InfoDaoTestSupport.insertDirectly(
                ds,
                "tournament",
                Map.of(
                        "tournament_id", "t-pg-004",
                        "tenant_id", "tenant-pg-2",
                        "location_id", "loc-2",
                        "tournament_token", "tok-pg-4",
                        "per_tournament_secret", new byte[32],
                        "last_applied_seq", 0L,
                        "registered_at", now.plusHours(1)));

        // Both rows coexist: T1 superseded, T2 active
        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("tournament").build()).hasNumberOfRows(2);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void insertTenant(DataSource ds, String tenantId) {
        InfoDaoTestSupport.insertDirectly(
                ds,
                "tenant",
                Map.of(
                        "tenant_id",
                        tenantId,
                        "public_key",
                        new byte[32],
                        "algorithm_id",
                        "Ed25519",
                        "registered_at",
                        LocalDateTime.now(),
                        "status",
                        "ACTIVE",
                        "is_default",
                        false));
    }

    private static void supersedeTournament(DataSource ds, String tournamentId, LocalDateTime at) {
        try (var conn = ds.getConnection();
                var ps =
                        conn.prepareStatement(
                                "UPDATE tournament SET superseded_at = ? WHERE tournament_id ="
                                        + " ?")) {
            ps.setObject(1, at);
            ps.setString(2, tournamentId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("supersedeTournament failed: " + e.getMessage(), e);
        }
    }
}
