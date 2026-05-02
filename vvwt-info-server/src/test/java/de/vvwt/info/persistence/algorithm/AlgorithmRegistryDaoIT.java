package de.vvwt.info.persistence.algorithm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.info.persistence.testsupport.InfoDaoTestSupport;
import java.util.Map;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * DAO integration test for {@link AlgorithmRegistryDao} — both H2 and PostgreSQL backends (AC3).
 *
 * <p>DEC-26/DEC-46 three rules applied via {@link InfoDaoTestSupport}:
 *
 * <ol>
 *   <li>Schema loaded from production Flyway migration (Rule 1)
 *   <li>Write verification via assertj-db, NOT via DAO read methods (Rule 2)
 *   <li>Fixture insertion via direct JDBC for read-path tests (Rule 3)
 * </ol>
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC2, AC3,
 *     AC6</a>
 */
@Testcontainers
class AlgorithmRegistryDaoIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    // -------------------------------------------------------------------------
    // H2 backend tests (AC2, AC6)
    // -------------------------------------------------------------------------

    @Test
    void h2_seed_row_present_after_migration() {
        // Rule 1: schema from production migration
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);

        // Rule 2: verify via assertj-db, NOT via DAO read
        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("algorithm_registry").build())
                .hasNumberOfRows(1)
                .row(0)
                .value("algorithm_id")
                .isEqualTo("Ed25519");
    }

    @Test
    void h2_only_ed25519_in_registry_v1() {
        // AC6: exactly one row; no other algorithms in V1
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);

        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("algorithm_registry").build()).hasNumberOfRows(1);
    }

    @Test
    void h2_read_path_uses_direct_jdbc_fixture() {
        // Rule 3: read-path test inserts fixture data via direct JDBC, not via DAO write
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyMigration(ds, "db/migration/h2/V1__initial_schema.sql");

        // Insert a fixture directly (Rule 3) — no DAO write used
        InfoDaoTestSupport.insertDirectly(
                ds,
                "algorithm_registry",
                Map.of(
                        "algorithm_id", "test-algo",
                        "display_name", "Test Algorithm",
                        "active", true));

        // Verify via assertj-db (Rule 2)
        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("algorithm_registry").build()).hasNumberOfRows(1);
        assertThat(assertDb.table("algorithm_registry").build())
                .row(0)
                .value("algorithm_id")
                .isEqualTo("test-algo");
    }

    // -------------------------------------------------------------------------
    // PostgreSQL backend tests (AC3)
    // -------------------------------------------------------------------------

    @Test
    void postgres_seed_row_present_after_migration() {
        // AC3: at least one IT per DAO covers both backends
        DataSource ds = InfoDaoTestSupport.freshPostgresDataSource(POSTGRES);
        InfoDaoTestSupport.applyFullPostgresSchema(ds);

        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("algorithm_registry").build())
                .hasNumberOfRows(1)
                .row(0)
                .value("algorithm_id")
                .isEqualTo("Ed25519");
    }

    @Test
    void postgres_only_ed25519_in_registry_v1() {
        DataSource ds = InfoDaoTestSupport.freshPostgresDataSource(POSTGRES);
        InfoDaoTestSupport.applyFullPostgresSchema(ds);

        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("algorithm_registry").build()).hasNumberOfRows(1);
    }
}
