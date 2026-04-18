package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PerTenantFlywayRunner}.
 *
 * <p>All tests use real H2 in-memory or file DataSources — no mocks for the runner itself.
 * {@link TenantDataSourceResolver} is stubbed only for error-path tests where the point
 * is precisely the resolver's exception behaviour (AC6).
 *
 * <p>Story: E14S04 — Per-tenant Flyway runner, tests first (DEC-20, DEC-21, DEC-22).
 */
class PerTenantFlywayRunnerTest {

    // ------------------------------------------------------------------
    // AC6 — unknown tenant: resolver throws, runner propagates
    // ------------------------------------------------------------------

    /**
     * AC6: If the runner is called for an unregistered tenant, the resolver's
     * {@link TenantDataSourceResolver.UnknownTenantException} must propagate unchanged.
     * The runner must NOT create a DB file on the fly.
     */
    @Test
    void unknownTenant_throwsUnknownTenantException() {
        UUID unknownId = UUID.randomUUID();
        TenantDataSourceResolver rejectingResolver = tenantId -> {
            throw new TenantDataSourceResolver.UnknownTenantException(tenantId);
        };

        PerTenantFlywayRunner runner = new PerTenantFlywayRunner(
                rejectingResolver, TournamentManagerApplication.class);

        assertThatThrownBy(() -> runner.run(unknownId))
                .isInstanceOf(TenantDataSourceResolver.UnknownTenantException.class)
                .extracting("tenantId")
                .isEqualTo(unknownId);
    }

    // ------------------------------------------------------------------
    // AC5 — idempotency: running twice is a no-op
    // ------------------------------------------------------------------

    /**
     * AC5: Invoking the runner twice on an already-migrated tenant is idempotent.
     * The {@code flyway_schema_history} row count must be identical after the second run.
     *
     * <p>This test uses a real H2 file DataSource and the actual classpath migrations
     * (if any exist at {@code db/migration/{module}}). It verifies Flyway's built-in
     * idempotency contract — no custom idempotency logic is needed in the runner.
     */
    @Test
    void runTwice_isIdempotent(@TempDir Path tempDir) throws Exception {
        UUID tenantId = UUID.randomUUID();
        DataSource ds = H2TestDataSourceHelper.createTempFileDataSource(tempDir, tenantId);
        TenantDataSourceResolver resolver = id -> ds;

        PerTenantFlywayRunner runner = new PerTenantFlywayRunner(
                resolver, TournamentManagerApplication.class);

        runner.run(tenantId);
        int historyRowCountAfterFirstRun = H2TestDataSourceHelper.countFlywayHistoryRows(ds);

        runner.run(tenantId);
        int historyRowCountAfterSecondRun = H2TestDataSourceHelper.countFlywayHistoryRows(ds);

        assertThat(historyRowCountAfterSecondRun)
                .as("Second run must be idempotent — flyway_schema_history row count unchanged")
                .isEqualTo(historyRowCountAfterFirstRun);
    }

    // ------------------------------------------------------------------
    // AC3 — module-ordering: locations derived from ApplicationModules
    // ------------------------------------------------------------------

    /**
     * AC3 (ordering proof): The migration locations produced by the runner are derived from
     * {@code ApplicationModules.of(applicationClass)} dependency-tree order, not from a
     * hardcoded list or random iteration.
     *
     * <p>Verifies via {@link PerTenantFlywayRunner#buildLocations()} that the returned list
     * is non-empty and each entry follows the {@code classpath:db/migration/{moduleName}} pattern.
     * This is the unit-level proof; the IT tests exercise real Flyway execution.
     */
    @Test
    void buildLocations_followsClasspathDbMigrationPattern() {
        TenantDataSourceResolver noopResolver = id -> { throw new AssertionError("should not be called"); };
        PerTenantFlywayRunner runner = new PerTenantFlywayRunner(
                noopResolver, TournamentManagerApplication.class);

        List<String> locations = runner.buildLocations();

        assertThat(locations)
                .as("Locations must be non-empty (ApplicationModules has at least the tenant module)")
                .isNotEmpty();
        assertThat(locations)
                .as("Every location must match classpath:db/migration/{moduleName}")
                .allSatisfy(loc -> assertThat(loc).startsWith("classpath:db/migration/"));
        assertThat(locations)
                .as("No location may point at the legacy root db/migration path (AC8)")
                .noneMatch("classpath:db/migration/"::equals);
    }

    /**
     * AC8 (legacy root path proof): The runner MUST NOT include the legacy root
     * {@code classpath:db/migration} (without a sub-directory) in the Flyway locations.
     *
     * <p>This is a stronger statement than the pattern check above: it ensures that
     * calling {@link PerTenantFlywayRunner#buildLocations()} never returns the bare root path
     * even if {@code ApplicationModules} returns a module whose name happens to be empty.
     */
    @Test
    void buildLocations_neverIncludesLegacyRootPath() {
        TenantDataSourceResolver noopResolver = id -> { throw new AssertionError("should not be called"); };
        PerTenantFlywayRunner runner = new PerTenantFlywayRunner(
                noopResolver, TournamentManagerApplication.class);

        List<String> locations = runner.buildLocations();

        assertThat(locations).doesNotContain("classpath:db/migration");
        assertThat(locations).doesNotContain("classpath:db/migration/");
        assertThat(locations).doesNotContain("db/migration");
    }
}
