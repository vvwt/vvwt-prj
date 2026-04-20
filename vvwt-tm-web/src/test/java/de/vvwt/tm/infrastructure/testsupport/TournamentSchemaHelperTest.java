package de.vvwt.tm.infrastructure.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link TenantDaoTestSupport#applyTournamentSchema(DataSource)} (E21S02,
 * AC-DAO-SCHEMA-HELPER).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link TenantDaoTestSupport#applyTournamentSchema(DataSource)}
 * did not exist at commit time, causing a compile error — satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>All 14 migrations load successfully into an empty test DataSource (AC-DAO-SCHEMA-HELPER)
 *   <li>Version order is honoured: {@code tournament} table exists after load (V1+V2 required)
 *   <li>Key tables from the full C-15 subset are present: tenants, tournament, phase, match, etc.
 * </ul>
 *
 * @see TenantDaoTestSupport
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 */
@DisplayName("TenantDaoTestSupport.applyTournamentSchema — E21S02 AC-DAO-SCHEMA-HELPER")
class TournamentSchemaHelperTest {

    @Test
    @DisplayName("applyTournamentSchema loads all 14 migrations without error")
    void applyTournamentSchemaLoadsAllMigrationsSuccessfully() {
        DataSource ds = TenantDaoTestSupport.freshDataSource();

        assertThatNoException()
                .as("All 14 C-15 migrations must apply cleanly in version order")
                .isThrownBy(() -> TenantDaoTestSupport.applyTournamentSchema(ds));
    }

    @Test
    @DisplayName("applyTournamentSchema produces the tournament table (V2 schema present)")
    void applyTournamentSchemaCreatesTournamentTable() throws Exception {
        DataSource ds = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyTournamentSchema(ds);

        List<String> tables = listTables(ds);
        assertThat(tables)
                .as("tournament table must exist after applyTournamentSchema (V2 migration)")
                .contains("TOURNAMENT");
    }

    @Test
    @DisplayName("applyTournamentSchema produces the phase table (V2 schema present)")
    void applyTournamentSchemaCreatesPhaseTables() throws Exception {
        DataSource ds = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyTournamentSchema(ds);

        List<String> tables = listTables(ds);
        assertThat(tables)
                .as("phase table must exist after applyTournamentSchema (V2 migration)")
                .contains("PHASE");
    }

    @Test
    @DisplayName("applyTournamentSchema produces the match table (V3 migration applied)")
    void applyTournamentSchemaCreatesMatchTable() throws Exception {
        DataSource ds = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyTournamentSchema(ds);

        List<String> tables = listTables(ds);
        assertThat(tables)
                .as("match_entry table must exist after applyTournamentSchema (V3 migration)")
                .anyMatch(t -> t.equalsIgnoreCase("MATCH_ENTRY") || t.equalsIgnoreCase("MATCH"));
    }

    @Test
    @DisplayName("applyTournamentSchema produces the tenants table (V1 base schema)")
    void applyTournamentSchemaCreatesTenantsTable() throws Exception {
        DataSource ds = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyTournamentSchema(ds);

        List<String> tables = listTables(ds);
        assertThat(tables)
                .as("tenants table must exist after applyTournamentSchema (V1 base schema)")
                .contains("TENANTS");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns all table names (uppercase) from the DataSource's default schema. */
    private static List<String> listTables(DataSource ds) throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toUpperCase());
                }
            }
        }
        return tables;
    }
}
