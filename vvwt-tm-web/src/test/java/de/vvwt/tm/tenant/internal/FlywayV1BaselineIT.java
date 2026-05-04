package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RED-first integration test for E46S06 schema consolidation (AC12 — DEC-22 Iron Law).
 *
 * <p>Asserts the V1-only baseline state of the per-tenant Flyway schema after the E46S06
 * consolidation: each of the {@code tenant} and {@code tournament} modules should have exactly
 * <strong>one migration row</strong> (version {@code 1}) in their respective {@code
 * flyway_schema_history_*} tables when bootstrapped against a fresh tenant DB.
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED against the pre-consolidation codebase (E46S01 V2 files still
 * present): the {@code flyway_schema_history_tenant} table contains a V2 row in addition to V1, and
 * similarly for {@code flyway_schema_history_tournament}. The assertion {@code hasSize(1)} fails
 * because each migration-version list has size 2 ({@code [1, 2]}).
 *
 * <h2>GREEN state</h2>
 *
 * <p>After the E46S06 consolidation (V2 files deleted, columns inlined into V1), a fresh tenant
 * bootstrap produces exactly one migration row per module — version {@code 1} — making the test
 * GREEN.
 *
 * <h2>DEC-22 Iron Law compliance</h2>
 *
 * <p>New production behaviour (V1-only schema) is guarded by this RED-first test. No production
 * code was changed before this test was committed RED per DEC-22.
 *
 * <h2>Flyway history table structure</h2>
 *
 * <p>The {@link PerTenantFlywayRunner} uses module-namespaced history tables: {@code
 * flyway_schema_history_tenant} and {@code flyway_schema_history_tournament}. With {@code
 * baselineOnMigrate=true} and {@code baselineVersion="0"}, Flyway creates a baseline row for
 * version {@code "0"} only when it encounters a non-empty schema without a history table. For a
 * fresh empty DB, no baseline row is created — only the V1 migration row appears. This test queries
 * only {@code installed_rank} rows where {@code version} = {@code '1'} (i.e., real migration rows,
 * not baseline rows) and asserts exactly one such row per module.
 *
 * @see PerTenantFlywayRunner
 * @see <a href="../../../../../../../../docs/governance/stories/E46S06.story.md">Story E46S06</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22 — TDD Iron
 *     Law</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-52.md">DEC-52 — V2 i18n
 *     reclassification</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-25.md">DEC-25 — no-prod-data
 *     condition</a>
 */
class FlywayV1BaselineIT {

    /**
     * AC12: Fresh tenant bootstrap produces exactly one migration row (version {@code 1}) in {@code
     * flyway_schema_history_tenant} and exactly one in {@code flyway_schema_history_tournament}.
     *
     * <p>FAILs before E46S06 consolidation (V2 rows present → version list is [1, 2]). PASSes after
     * E46S06 consolidation (V2 files deleted → version list is [1]).
     */
    @Test
    void freshTenantBootstrap_tenantAndTournamentModules_haveExactlyOneSchemaHistoryRowEach(
            @TempDir Path tempDir) throws Exception {
        UUID tenantId = UUID.randomUUID();
        DataSource ds = H2TestDataSourceHelper.createTempFileDataSource(tempDir, tenantId);

        // Use the real production PerTenantFlywayRunner with production migration locations.
        // This causes Flyway to apply all modules' production V*.sql files to the fresh H2 DB.
        PerTenantFlywayRunner runner =
                new PerTenantFlywayRunner(id -> ds, TournamentManagerApplication.class);
        runner.run(tenantId);

        // Query flyway_schema_history_tenant for all migration versions (exclude baseline rows)
        List<String> tenantVersions = queryMigrationVersions(ds, "flyway_schema_history_tenant");
        // Query flyway_schema_history_tournament for all migration versions
        List<String> tournamentVersions =
                queryMigrationVersions(ds, "flyway_schema_history_tournament");

        // AC12: exactly one migration row per module, and it must be version "1".
        // Pre-consolidation (E46S01 V2 files present): tenantVersions = ["1", "2"] → FAIL
        // Post-consolidation (E46S06 done): tenantVersions = ["1"] → PASS
        assertThat(tenantVersions)
                .as(
                        "flyway_schema_history_tenant must contain exactly one migration row with"
                                + " version 1 after E46S06 consolidation (DEC-52, DEC-25)")
                .containsExactly("1");

        assertThat(tournamentVersions)
                .as(
                        "flyway_schema_history_tournament must contain exactly one migration row"
                                + " with version 1 after E46S06 consolidation (DEC-52, DEC-25)")
                .containsExactly("1");
    }

    /**
     * Returns the ordered list of migration {@code version} values from the given {@code
     * flyway_schema_history_*} table, excluding baseline rows (type = 'BASELINE').
     *
     * <p>Queries only rows where {@code type} is {@code 'MIGRATE'} — the standard migration type
     * that Flyway uses for real V*.sql migrations. Baseline rows (type='BASELINE', version='0') are
     * excluded because they are an implementation detail of Flyway's baselineOnMigrate setting and
     * do not represent actual schema versions.
     *
     * @param ds the DataSource to query
     * @param historyTable the Flyway history table name (e.g., "flyway_schema_history_tenant")
     * @return ordered list of version strings for MIGRATE-type rows; empty if table absent
     */
    private static List<String> queryMigrationVersions(DataSource ds, String historyTable)
            throws Exception {
        List<String> versions = new ArrayList<>();
        try (Connection conn = ds.getConnection()) {
            // Check table exists first (avoid spurious error if module has no history table)
            boolean exists;
            try (var stmt = conn.createStatement();
                    ResultSet rs =
                            stmt.executeQuery(
                                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE"
                                            + " LOWER(TABLE_NAME) = '"
                                            + historyTable.toLowerCase()
                                            + "'")) {
                rs.next();
                exists = rs.getInt(1) > 0;
            }
            if (!exists) {
                return versions;
            }
            // H2 2.x: table created by Flyway with lowercase name — must quote to preserve case.
            // Query only MIGRATE-type rows (exclude BASELINE rows from baselineOnMigrate).
            try (var stmt = conn.createStatement();
                    ResultSet rs =
                            stmt.executeQuery(
                                    "SELECT \"version\" FROM \""
                                            + historyTable
                                            + "\" WHERE \"success\" = TRUE"
                                            + " AND \"version\" IS NOT NULL"
                                            + " AND \"version\" <> '0'"
                                            + " ORDER BY \"installed_rank\"")) {
                while (rs.next()) {
                    versions.add(rs.getString("version"));
                }
            }
        }
        return versions;
    }
}
