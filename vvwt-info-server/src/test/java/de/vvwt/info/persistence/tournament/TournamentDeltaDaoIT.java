package de.vvwt.info.persistence.tournament;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.info.persistence.testsupport.InfoDaoTestSupport;
import java.time.LocalDateTime;
import java.util.Map;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DAO integration test for {@link TournamentDeltaDao} — both H2 and PostgreSQL (AC3, AC13).
 *
 * <p>AC13a: composite PK {@code (tournament_id, seq)} enforces uniqueness of sequence numbers
 * within a tournament at the DB level. A duplicate-seq INSERT must fail with a constraint
 * violation.
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC13</a>
 */
@Testcontainers
class TournamentDeltaDaoIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    // -------------------------------------------------------------------------
    // H2 backend — composite PK uniqueness (AC13a)
    // -------------------------------------------------------------------------

    @Test
    void h2_delta_insert_verified_by_assertjdb() {
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);
        insertTenantAndTournament(ds, "tenant-1", "t-001");

        var now = LocalDateTime.now();
        InfoDaoTestSupport.insertDirectly(
                ds,
                "tournament_delta",
                Map.of(
                        "tournament_id", "t-001",
                        "seq", 1L,
                        "event_type", "TEAM_SCORE_UPDATED",
                        "event_payload", "{\"score\":3}",
                        "applied_at", now));

        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("tournament_delta").build())
                .hasNumberOfRows(1)
                .row(0)
                .value("event_type")
                .isEqualTo("TEAM_SCORE_UPDATED");
    }

    @Test
    void h2_duplicate_seq_insert_fails_ac13a() {
        // AC13a: composite PK (tournament_id, seq) prevents duplicate seq
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);
        insertTenantAndTournament(ds, "tenant-1", "t-001");

        var now = LocalDateTime.now();
        InfoDaoTestSupport.insertDirectly(
                ds,
                "tournament_delta",
                Map.of(
                        "tournament_id", "t-001",
                        "seq", 1L,
                        "event_type", "FIRST",
                        "event_payload", "{}",
                        "applied_at", now));

        // Second insert with same (tournament_id=t-001, seq=1) must fail
        assertThatThrownBy(
                        () ->
                                InfoDaoTestSupport.insertDirectly(
                                        ds,
                                        "tournament_delta",
                                        Map.of(
                                                "tournament_id", "t-001",
                                                "seq", 1L,
                                                "event_type", "DUPLICATE",
                                                "event_payload", "{}",
                                                "applied_at", now.plusSeconds(1))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void h2_different_seq_inserts_succeed_independently() {
        // Different seq values for the same tournament can coexist
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);
        insertTenantAndTournament(ds, "tenant-1", "t-002");

        var now = LocalDateTime.now();
        InfoDaoTestSupport.insertDirectly(
                ds,
                "tournament_delta",
                Map.of(
                        "tournament_id", "t-002",
                        "seq", 1L,
                        "event_type", "E1",
                        "event_payload", "{}",
                        "applied_at", now));
        InfoDaoTestSupport.insertDirectly(
                ds,
                "tournament_delta",
                Map.of(
                        "tournament_id", "t-002",
                        "seq", 2L,
                        "event_type", "E2",
                        "event_payload", "{}",
                        "applied_at", now.plusSeconds(1)));

        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("tournament_delta").build()).hasNumberOfRows(2);
    }

    // -------------------------------------------------------------------------
    // PostgreSQL backend (AC3)
    // -------------------------------------------------------------------------

    @Test
    void postgres_duplicate_seq_insert_fails_ac13a() {
        // AC3: PostgreSQL backend coverage for AC13a
        DataSource ds = InfoDaoTestSupport.freshPostgresDataSource(POSTGRES);
        InfoDaoTestSupport.applyFullPostgresSchema(ds);
        insertTenantAndTournament(ds, "tenant-pg-1", "t-pg-001");

        var now = LocalDateTime.now();
        InfoDaoTestSupport.insertDirectly(
                ds,
                "tournament_delta",
                Map.of(
                        "tournament_id", "t-pg-001",
                        "seq", 1L,
                        "event_type", "FIRST",
                        "event_payload", "{}",
                        "applied_at", now));

        assertThatThrownBy(
                        () ->
                                InfoDaoTestSupport.insertDirectly(
                                        ds,
                                        "tournament_delta",
                                        Map.of(
                                                "tournament_id", "t-pg-001",
                                                "seq", 1L,
                                                "event_type", "DUPLICATE",
                                                "event_payload", "{}",
                                                "applied_at", now.plusSeconds(1))))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void insertTenantAndTournament(
            DataSource ds, String tenantId, String tournamentId) {
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

        InfoDaoTestSupport.insertDirectly(
                ds,
                "tournament",
                Map.of(
                        "tournament_id",
                        tournamentId,
                        "tenant_id",
                        tenantId,
                        "location_id",
                        "loc-1",
                        "tournament_token",
                        "tok-" + tournamentId,
                        "per_tournament_secret",
                        new byte[32],
                        "last_applied_seq",
                        0L,
                        "registered_at",
                        LocalDateTime.now()));
    }
}
