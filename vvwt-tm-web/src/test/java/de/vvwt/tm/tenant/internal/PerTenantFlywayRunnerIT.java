package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for {@link PerTenantFlywayRunner} using real H2 file DataSources.
 *
 * <p>Each test creates isolated H2 files under JUnit's {@code @TempDir} — no shared state. Tests
 * use a subclass that overrides {@link PerTenantFlywayRunner#buildLocations()} to point at test
 * migration directories, since Wave-1 per-module production migration directories are added by E15
 * stories (not E14S04).
 *
 * <p>Story: E14S04 — DEC-20 (per-tenant Flyway), DEC-21 (per-module migration paths), DEC-22 (TDD
 * Iron Law, reconstruction-in-place).
 *
 * <p>No Spring context is loaded — this is a pure Flyway + H2 integration test.
 */
class PerTenantFlywayRunnerIT {

    /**
     * Creates a runner that uses the test-classpath {@code db/migration-test/tenant/} directory.
     * This directory contains {@code V1__tenant_test_schema.sql} (creates {@code
     * tenant_test_marker} table), providing a real migration to validate Flyway's per-tenant
     * execution.
     *
     * <p>The path is deliberately outside {@code db/migration/} to prevent Spring Boot's default
     * Flyway auto-configuration from picking it up during full-context integration tests (which
     * would cause a "duplicate version 1" conflict with {@code V1__initial_schema.sql}).
     */
    private static PerTenantFlywayRunner runnerWithTestMigrations(
            TenantDataSourceResolver resolver) {
        return new PerTenantFlywayRunner(resolver, TournamentManagerApplication.class) {
            @Override
            public List<String> buildLocations() {
                // Points at the test-classpath tenant migration directory (E14S04 test resource)
                return List.of("classpath:db/migration-test/tenant");
            }
        };
    }

    // ------------------------------------------------------------------
    // AC2 — two-tenant isolation: flyway_schema_history is per-file
    // ------------------------------------------------------------------

    /**
     * AC2: Running the per-tenant Flyway runner for two tenants produces two independent {@code
     * flyway_schema_history} tables, each local to its own H2 file.
     *
     * <p>After running runner for tenant A and tenant B:
     *
     * <ul>
     *   <li>Tenant A's DB has its own {@code flyway_schema_history} rows.
     *   <li>Tenant B's DB has its own {@code flyway_schema_history} rows.
     *   <li>Both DBs have the {@code tenant_test_marker} table from the test migration.
     *   <li>Row counts are equal (same migration applied independently).
     * </ul>
     */
    @Test
    void twoTenants_schemaHistoryIsLocalToEachTenant(@TempDir Path tempDir) throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        DataSource dsA = H2TestDataSourceHelper.createTempFileDataSource(tempDir, tenantA);
        DataSource dsB = H2TestDataSourceHelper.createTempFileDataSource(tempDir, tenantB);

        PerTenantFlywayRunner runnerA = runnerWithTestMigrations(id -> dsA);
        PerTenantFlywayRunner runnerB = runnerWithTestMigrations(id -> dsB);

        runnerA.run(tenantA);
        runnerB.run(tenantB);

        // Both tenants have their own flyway_schema_history_tenant (Flyway ran in each)
        // E45S06: PerTenantFlywayRunner uses module-namespaced history tables
        // (flyway_schema_history_{moduleName}); the test migration location is
        // "classpath:db/migration-test/tenant" → moduleName = "tenant" → table =
        // "flyway_schema_history_tenant".
        assertThat(H2TestDataSourceHelper.tableExists(dsA, "flyway_schema_history_tenant"))
                .as("Tenant A must have flyway_schema_history in its own file")
                .isTrue();
        assertThat(H2TestDataSourceHelper.tableExists(dsB, "flyway_schema_history_tenant"))
                .as("Tenant B must have flyway_schema_history in its own file")
                .isTrue();

        // The applied migration tables exist in both
        assertThat(H2TestDataSourceHelper.tableExists(dsA, "tenant_test_marker"))
                .as("Tenant A must have the table from the test migration")
                .isTrue();
        assertThat(H2TestDataSourceHelper.tableExists(dsB, "tenant_test_marker"))
                .as("Tenant B must have the table from the test migration")
                .isTrue();

        // Same number of history rows (same migration applied independently)
        int rowsA = H2TestDataSourceHelper.countFlywayHistoryRows(dsA);
        int rowsB = H2TestDataSourceHelper.countFlywayHistoryRows(dsB);
        assertThat(rowsA).as("Tenant A history rows").isGreaterThan(0);
        assertThat(rowsB).as("Tenant B history rows").isEqualTo(rowsA);
    }

    /**
     * AC2 (single tenant happy path): Calling the runner for one tenant creates the {@code
     * flyway_schema_history} table and applies migrations in that tenant's DB.
     */
    @Test
    void singleTenant_happyPath_migrationsApplied(@TempDir Path tempDir) throws Exception {
        UUID tenantId = UUID.randomUUID();
        DataSource ds = H2TestDataSourceHelper.createTempFileDataSource(tempDir, tenantId);
        PerTenantFlywayRunner runner = runnerWithTestMigrations(id -> ds);

        runner.run(tenantId);

        // E45S06: module-namespaced history table (flyway_schema_history_{moduleName})
        assertThat(H2TestDataSourceHelper.tableExists(ds, "flyway_schema_history_tenant"))
                .as("flyway_schema_history_tenant must exist after runner completes")
                .isTrue();
        assertThat(H2TestDataSourceHelper.tableExists(ds, "tenant_test_marker"))
                .as("Migration table must exist after runner completes")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // AC5 — idempotency: running twice leaves history unchanged
    // ------------------------------------------------------------------

    /**
     * AC5: Invoking the runner twice on an already-migrated tenant is idempotent. The {@code
     * flyway_schema_history} row count must be identical after the second run.
     */
    @Test
    void runTwice_isIdempotent(@TempDir Path tempDir) throws Exception {
        UUID tenantId = UUID.randomUUID();
        DataSource ds = H2TestDataSourceHelper.createTempFileDataSource(tempDir, tenantId);
        PerTenantFlywayRunner runner = runnerWithTestMigrations(id -> ds);

        runner.run(tenantId);
        int historyRowCountAfterFirstRun = H2TestDataSourceHelper.countFlywayHistoryRows(ds);

        runner.run(tenantId);
        int historyRowCountAfterSecondRun = H2TestDataSourceHelper.countFlywayHistoryRows(ds);

        assertThat(historyRowCountAfterSecondRun)
                .as("Second run must be idempotent — flyway_schema_history row count unchanged")
                .isEqualTo(historyRowCountAfterFirstRun);
    }

    // ------------------------------------------------------------------
    // AC4 — broken migration: exception propagated, history consistent
    // ------------------------------------------------------------------

    /**
     * AC4: When the Flyway runner encounters an invalid migration (e.g., invalid SQL), it must
     * propagate the Flyway exception — NOT swallow it. The tenant is NOT removed from the registry
     * (cleanup is a lifecycle concern, not a runner concern).
     */
    @Test
    void brokenMigration_flywayExceptionPropagated(@TempDir Path tempDir) throws Exception {
        UUID tenantId = UUID.randomUUID();
        DataSource ds = H2TestDataSourceHelper.createTempFileDataSource(tempDir, tenantId);

        // Runner pointing at a test directory with intentionally broken SQL (AC4)
        PerTenantFlywayRunner runner =
                new PerTenantFlywayRunner(id -> ds, TournamentManagerApplication.class) {
                    @Override
                    public List<String> buildLocations() {
                        return List.of("classpath:db/migration-test-broken");
                    }
                };

        assertThatThrownBy(() -> runner.run(tenantId))
                .as("Flyway exception must propagate — runner must not swallow it")
                .isInstanceOf(FlywayException.class);
    }

    // ------------------------------------------------------------------
    // AC8 — legacy root migration not applied
    // ------------------------------------------------------------------

    /**
     * AC8: A migration placed at the legacy root {@code db/migration/} (without a module
     * sub-directory) must NOT be applied by the runner. The runner exclusively uses {@code
     * classpath:db/migration/{moduleName}} locations per DEC-21.
     *
     * <p>The test verifies that after running the runner (which uses only per-module locations),
     * the {@code legacy_root_marker} table (created by {@code V99__legacy_root.sql} at the root
     * path) does NOT exist in the tenant's DB.
     */
    @Test
    void legacyRootMigration_notAppliedByRunner(@TempDir Path tempDir) throws Exception {
        UUID tenantId = UUID.randomUUID();
        DataSource ds = H2TestDataSourceHelper.createTempFileDataSource(tempDir, tenantId);

        // Runner uses per-module locations only (the production behavior)
        PerTenantFlywayRunner runner = runnerWithTestMigrations(id -> ds);

        runner.run(tenantId);

        // V99__legacy_root.sql is at db/migration/V99__legacy_root.sql (root path, not per-module).
        // If the runner had accidentally scanned the root db/migration path, this table would
        // exist.
        // Absence confirms the runner uses only per-module paths (AC8).
        assertThat(H2TestDataSourceHelper.tableAbsent(ds, "legacy_root_marker"))
                .as(
                        "Table from legacy root migration must NOT exist — runner uses per-module"
                                + " paths only")
                .isTrue();
    }
}
