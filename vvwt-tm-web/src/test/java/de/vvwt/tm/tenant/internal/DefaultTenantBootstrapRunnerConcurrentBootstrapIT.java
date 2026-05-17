// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.PerTenantFlywayRunner;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tenant.TenantRegistryPort.TenantRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test for {@link DefaultTenantBootstrapRunner} — concurrent first-boot race
 * protection.
 *
 * <p>Covers AC-CONCURRENT-BOOTSTRAP-RACE-PRESERVED (E46S05): two concurrent bootstrap invocations
 * against the same {@code ${tm.data.dir}/tenant-registry.json} must result in:
 *
 * <ol>
 *   <li>exactly one register-success in the JSON registry,
 *   <li>exactly one INSERT into the per-tenant {@code tenants} table, and
 *   <li>cross-carrier consistency — the JSON registry's {@code language} matches the per-tenant
 *       {@code tenants.language} column for the registered UUID; no partial-write between the two
 *       carriers (i.e., no state where the JSON registry has the tenant but the per-tenant table is
 *       empty, or vice versa).
 * </ol>
 *
 * <h2>Concurrency mechanism</h2>
 *
 * <p>Two threads run two distinct {@link DefaultTenantBootstrapRunner} instances concurrently via
 * {@link CompletableFuture#runAsync(Runnable)} + {@link CompletableFuture#allOf}. Both runners
 * share the same {@link TenantFileRegistry} and the same {@code tm.data.dir}, mirroring the
 * production race scenario where two JVM instances start against the same on-disk registry. The
 * production runner's existing race protection is the {@link
 * TenantFileRegistry#registerIfDisplayNameAbsent} synchronized block invoked via {@link
 * DefaultTenantBootstrapRunner#atomicRegisterIfAbsent} (Step 7 of the bootstrap flow). The loser
 * self-cleans its UUID-named tenant directory (Step 7 else-branch).
 *
 * <h2>Isolation from the auto-configured runner</h2>
 *
 * <p>The Spring-managed {@code DefaultTenantBootstrapRunner} bean has already executed once at
 * context load, populating its own {@code tm.data.dir} (the {@link
 * org.junit.jupiter.api.io.TempDir}-resolved path from {@code application-test.yml}). This test
 * uses an isolated {@link TempDir} per test method and constructs two fresh runner instances
 * directly — independent of the auto-configured one — so the concurrent race is exercised against a
 * known-empty registry rather than the already-populated one.
 *
 * <h2>DEC classifications</h2>
 *
 * <ul>
 *   <li>DEC-22 RED-first: this test exercises the cross-carrier both-or-neither invariant on the
 *       post-E46S05 registry+per-tenant-table topology — the assertion targets ({@link
 *       TenantRecord#language()} method, {@code tenants.language} column) did not exist pre-E46S05.
 *       The "RED-first" criterion is met at the test-body level.
 *   <li>DEC-41 observable-form: assertions are at the registry-state level ({@link
 *       TenantRegistryPort#findAll}) and the per-tenant DB-column level ({@link JdbcTemplate}
 *       queries). No internal helper invocation counts are asserted.
 *   <li>DEC-44 + DEC-44-amendment: web-module IT uses
 *       {@code @SpringBootTest(WebEnvironment.RANDOM_PORT)} (consistent with the existing {@link
 *       DefaultTenantBootstrapRunnerIT}).
 *   <li>DEC-36: this test lives in {@code de.vvwt.tm.tenant.internal} so cross-package typing rules
 *       allow direct reference to the {@code Default*} impl class.
 * </ul>
 *
 * @see DefaultTenantBootstrapRunner
 * @see TenantFileRegistry#registerIfDisplayNameAbsent
 * @see DefaultTenantBootstrapRunnerIT
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class DefaultTenantBootstrapRunnerConcurrentBootstrapIT {

    @Autowired private PerTenantFlywayRunner perTenantFlywayRunner;
    @Autowired private TmBootstrapProperties bootstrapProperties;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private DataSourceProperties dataSourceProperties;

    @Qualifier("dataSource")
    @Autowired
    private DataSource flatDataSource;

    /**
     * AC-CONCURRENT-BOOTSTRAP-RACE-PRESERVED: two concurrent bootstrap invocations produce
     * exactly-one register-success, exactly-one per-tenant {@code tenants} INSERT, and consistent
     * language across both carriers.
     *
     * <p>Setup: an isolated {@link TempDir}-rooted {@code tm.data.dir} with an empty registry. Two
     * fresh {@link DefaultTenantBootstrapRunner} instances are constructed sharing the same {@link
     * TenantFileRegistry} and the same data dir, then both are invoked concurrently via {@link
     * CompletableFuture#runAsync}.
     *
     * <p>Assertions:
     *
     * <ul>
     *   <li>{@code registry.findAll().size() == 1} (exactly-one register-success).
     *   <li>{@code COUNT(*) FROM tenants WHERE id = ?} on the winner's per-tenant H2 file equals 1
     *       (exactly-one per-tenant INSERT).
     *   <li>{@code SELECT language FROM tenants WHERE id = ?} matches {@code
     *       registry.findAll().get(0).language()} (cross-carrier consistency — both-or-neither).
     * </ul>
     */
    @Test
    void concurrentFirstBoot_atomicBothOrNeitherAcrossRegistryAndPerTenantTable(
            @TempDir Path isolatedDataDir) throws Exception {
        // Shared registry — both runners point at the same on-disk tenant-registry.json.
        TenantFileRegistry sharedRegistry = new TenantFileRegistry(isolatedDataDir);

        // Shared in-memory per-tenant DataSource resolver. The runner's Step 6 + 6c use a
        // file-based DS derived from the data dir; Step 7b (winner only) uses this resolver.
        // Sharing the resolver across both runners mirrors the production wiring where the
        // resolver is a singleton bean.
        TenantContextTestSupport.InMemoryTenantDataSourceResolver sharedResolver =
                new TenantContextTestSupport.InMemoryTenantDataSourceResolver(
                        dataSourceProperties, 0L);

        // The two runners share registry, resolver, data dir, and bootstrap properties — the
        // production race scenario. They use independent flat-DB JdbcTemplates (the AC11
        // idempotency guard uses queryForList, which is read-only against an empty TENANTS table
        // — both threads observe an empty list and each generates its own UUID).
        JdbcTemplate sharedFlatJdbcTemplate = new JdbcTemplate(flatDataSource);

        DefaultTenantBootstrapRunner runnerA =
                new DefaultTenantBootstrapRunner(
                        sharedRegistry,
                        perTenantFlywayRunner,
                        sharedResolver,
                        isolatedDataDir,
                        sharedFlatJdbcTemplate,
                        transactionTemplate,
                        bootstrapProperties);
        DefaultTenantBootstrapRunner runnerB =
                new DefaultTenantBootstrapRunner(
                        sharedRegistry,
                        perTenantFlywayRunner,
                        sharedResolver,
                        isolatedDataDir,
                        sharedFlatJdbcTemplate,
                        transactionTemplate,
                        bootstrapProperties);

        // Capture any unexpected exception from either thread.
        AtomicReference<Throwable> failureA = new AtomicReference<>();
        AtomicReference<Throwable> failureB = new AtomicReference<>();

        CompletableFuture<Void> futureA =
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                runnerA.run(null);
                            } catch (Throwable t) {
                                failureA.set(t);
                            }
                        });
        CompletableFuture<Void> futureB =
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                runnerB.run(null);
                            } catch (Throwable t) {
                                failureB.set(t);
                            }
                        });

        CompletableFuture.allOf(futureA, futureB).join();

        assertThat(failureA.get())
                .as(
                        "Runner A must complete without exception"
                                + " (AC-CONCURRENT-BOOTSTRAP-RACE-PRESERVED)")
                .isNull();
        assertThat(failureB.get())
                .as(
                        "Runner B must complete without exception"
                                + " (AC-CONCURRENT-BOOTSTRAP-RACE-PRESERVED)")
                .isNull();

        // Carrier 1: JSON registry. Exactly-one register-success.
        List<TenantRecord> registered = sharedRegistry.findAll();
        assertThat(registered)
                .as(
                        "AC-CONCURRENT-BOOTSTRAP-RACE-PRESERVED: exactly one register-success in"
                                + " JSON registry after concurrent first-boot")
                .hasSize(1);
        TenantRecord winner = registered.get(0);
        UUID winnerUuid = winner.tenantId();
        String winnerLanguage = winner.language();
        assertThat(winner.displayName())
                .as("Winner displayName must equal the property-bound default")
                .isEqualTo(bootstrapProperties.getDisplayName());
        assertThat(winnerLanguage)
                .as("Winner language must equal the property-bound default")
                .isEqualTo(bootstrapProperties.getLanguage());

        // Carrier 2: per-tenant tenants table for the winner UUID. Query the file-based H2
        // directly (this is the DataSource the runner's Step 6c writes into; the loser's file is
        // self-cleaned in Step 7's else-branch).
        DataSource winnerFileDataSource = openWinnerFileDataSource(isolatedDataDir, winnerUuid);
        JdbcTemplate winnerJdbc = new JdbcTemplate(winnerFileDataSource);

        Long tenantsRowCount =
                winnerJdbc.queryForObject(
                        "SELECT COUNT(*) FROM tenants WHERE id = ?", Long.class, winnerUuid);
        assertThat(tenantsRowCount)
                .as(
                        "AC-CONCURRENT-BOOTSTRAP-RACE-PRESERVED: exactly one INSERT into the"
                                + " per-tenant tenants table for the registered UUID (no partial-"
                                + "write — JSON registry says yes, per-tenant table must also)")
                .isEqualTo(1L);

        // Cross-carrier consistency: tenants.language for the winner must match registry.language.
        String perTenantLanguage =
                winnerJdbc.queryForObject(
                        "SELECT language FROM tenants WHERE id = ?", String.class, winnerUuid);
        assertThat(perTenantLanguage)
                .as(
                        "AC-CONCURRENT-BOOTSTRAP-RACE-PRESERVED: per-tenant tenants.language must"
                                + " equal JSON-registry language for the same UUID (both-or-neither"
                                + " consistency)")
                .isEqualTo(winnerLanguage);
    }

    /**
     * Opens a read-only H2 DataSource pointing at the winner's per-tenant database file.
     *
     * <p>The runner's Step 6c writes the {@code tenants} row into this file via the file-based
     * DataSource it created in Step 5. We open a fresh DataSource at the same path to assert on the
     * post-bootstrap state without going through the runner.
     */
    private static DataSource openWinnerFileDataSource(Path dataDir, UUID winnerUuid) {
        Path dbPath = TenantDirectoryHelper.tenantDbPath(dataDir, winnerUuid);
        String urlPath = dbPath.toAbsolutePath().toString();
        if (urlPath.endsWith(".mv.db")) {
            urlPath = urlPath.substring(0, urlPath.length() - ".mv.db".length());
        }
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:file:" + urlPath + ";AUTO_SERVER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }
}
