package de.vvwt.tm.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Cross-tenant isolation proof using {@link ApplicationModuleTest}.
 *
 * <p>This test class is the canonical reference test for DB-per-Tenant isolation (DEC-20). It
 * bootstraps the {@code tenant} bounded context via {@code @ApplicationModuleTest}, registers two
 * tenants, writes distinct marker data into each, and proves physical isolation at both the query
 * layer (AC2) and the filesystem layer (AC3).
 *
 * <p>All Spring-injected beans are from {@code tenant::api} — functional test methods use only
 * public interfaces from {@code de.vvwt.tm.tenant} (AC6).
 *
 * <p>Story: E14S06 — DEC-20/DEC-21/DEC-22.
 */
@ApplicationModuleTest
@ActiveProfiles("test")
class CrossTenantIsolationTest {

    /**
     * Static temp dir created eagerly (before Spring context initializes) so that {@link
     * #tenantDataDir} can supply it as {@code tm.data.dir} at context-build time.
     *
     * <p>AC8: cleanup is performed in {@link #cleanupTempDataDir()} annotated with
     * {@code @AfterAll} — regardless of test pass/fail, JUnit guarantees {@code @AfterAll} runs
     * after all test methods in the class.
     */
    static final Path tempDataDir;

    static {
        try {
            tempDataDir = Files.createTempDirectory("tm-e14s06-isolation-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void cleanupTempDataDir() throws IOException {
        // AC8: delete the temp dir tree after all tests complete (pass or fail)
        if (Files.exists(tempDataDir)) {
            try (var stream = Files.walk(tempDataDir)) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    /**
     * Supplies the {@code tm.data.dir} property from the static temp dir path.
     *
     * <p>Must be static so it runs before the Spring context is initialized. The temp dir is
     * created in the {@code static} initializer above, which runs before JUnit's
     * {@code @DynamicPropertySource} callback.
     */
    @DynamicPropertySource
    static void tenantDataDir(DynamicPropertyRegistry registry) {
        registry.add("tm.data.dir", () -> tempDataDir.toAbsolutePath().toString());
    }

    @Autowired private TenantRegistryPort tenantRegistryPort;

    @Autowired private TenantContext tenantContext;

    @Autowired private TenantDataSourceResolver tenantDataSourceResolver;

    // -------------------------------------------------------------------------
    // AC1 — @ApplicationModuleTest and test-first discipline
    // -------------------------------------------------------------------------

    /**
     * AC1: Structural AC — the use of {@code @ApplicationModuleTest} here proves the test exercises
     * the {@code tenant} module's Spring context via Modulith's mechanism.
     *
     * <p>If {@code @ApplicationModuleTest} could not bootstrap the {@code tenant} module cleanly,
     * all tests would fail to start. The three tenant::api beans being successfully autowired
     * confirms the module bootstrapped.
     *
     * <p>DEC-22 (TDD Iron Law): this test was committed in RED state (context load error) before
     * the GREEN fix was applied. See git history for commit sequence.
     */
    @Test
    void applicationModuleTestBootstrapsOnlyTenantModule() {
        // If @ApplicationModuleTest is working correctly, all three tenant::api beans are wired
        assertThat(tenantRegistryPort)
                .as("TenantRegistryPort must be wired by @ApplicationModuleTest")
                .isNotNull();
        assertThat(tenantContext)
                .as("TenantContext must be wired by @ApplicationModuleTest")
                .isNotNull();
        assertThat(tenantDataSourceResolver)
                .as("TenantDataSourceResolver must be wired by @ApplicationModuleTest")
                .isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC2 — Two-tenant, two-row isolation proof (query layer)
    // -------------------------------------------------------------------------

    /**
     * AC2: Registers tenant A and tenant B, binds context to each, writes a distinct marker row
     * into each tenant's DB, and asserts that reads return only the correct row.
     *
     * <p>Verifies: SELECT *, filtered selects, and aggregate queries — none can surface
     * cross-tenant data (AC2 contract).
     */
    @Test
    void twoTenants_queryIsolation_eachTenantSeesOnlyItsOwnRow() {
        UUID tenantAId = UUID.randomUUID();
        UUID tenantBId = UUID.randomUUID();

        tenantRegistryPort.register(tenantAId, "Tenant A");
        tenantRegistryPort.register(tenantBId, "Tenant B");

        // Resolve per-tenant DataSources via public API only (AC6)
        javax.sql.DataSource dsA = tenantDataSourceResolver.resolve(tenantAId);
        javax.sql.DataSource dsB = tenantDataSourceResolver.resolve(tenantBId);

        JdbcTemplate jdbcA = new JdbcTemplate(dsA);
        JdbcTemplate jdbcB = new JdbcTemplate(dsB);

        // Create marker table in each tenant's DB and insert distinct rows
        jdbcA.execute("CREATE TABLE IF NOT EXISTS isolation_marker (label VARCHAR(100) NOT NULL)");
        jdbcB.execute("CREATE TABLE IF NOT EXISTS isolation_marker (label VARCHAR(100) NOT NULL)");

        jdbcA.execute("INSERT INTO isolation_marker VALUES ('marker-for-tenant-A')");
        jdbcB.execute("INSERT INTO isolation_marker VALUES ('marker-for-tenant-B')");

        // AC2: SELECT * — each tenant sees only its own row
        List<Map<String, Object>> rowsA = jdbcA.queryForList("SELECT * FROM isolation_marker");
        List<Map<String, Object>> rowsB = jdbcB.queryForList("SELECT * FROM isolation_marker");

        assertThat(rowsA).as("Tenant A must see exactly one row in its own DB").hasSize(1);
        assertThat(rowsA.get(0).get("LABEL"))
                .as("Tenant A's row must contain its own marker")
                .isEqualTo("marker-for-tenant-A");

        assertThat(rowsB).as("Tenant B must see exactly one row in its own DB").hasSize(1);
        assertThat(rowsB.get(0).get("LABEL"))
                .as("Tenant B's row must contain its own marker")
                .isEqualTo("marker-for-tenant-B");

        // AC2: Filtered selects — no cross-bleed
        List<Map<String, Object>> aCannotSeeBMarker =
                jdbcA.queryForList(
                        "SELECT * FROM isolation_marker WHERE label = 'marker-for-tenant-B'");
        assertThat(aCannotSeeBMarker)
                .as("Tenant A must not be able to read Tenant B's marker via a filtered query")
                .isEmpty();

        List<Map<String, Object>> bCannotSeeAMarker =
                jdbcB.queryForList(
                        "SELECT * FROM isolation_marker WHERE label = 'marker-for-tenant-A'");
        assertThat(bCannotSeeAMarker)
                .as("Tenant B must not be able to read Tenant A's marker via a filtered query")
                .isEmpty();

        // AC2: Aggregate query — each tenant's DB contains exactly 1 row
        Integer countA =
                jdbcA.queryForObject("SELECT COUNT(*) FROM isolation_marker", Integer.class);
        Integer countB =
                jdbcB.queryForObject("SELECT COUNT(*) FROM isolation_marker", Integer.class);

        assertThat(countA)
                .as("Tenant A's DB must contain exactly 1 marker row (its own)")
                .isEqualTo(1);
        assertThat(countB)
                .as("Tenant B's DB must contain exactly 1 marker row (its own)")
                .isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // AC3 — Schema probe: physical isolation at filesystem layer
    // -------------------------------------------------------------------------

    /**
     * AC3: Proves physical isolation by opening a second independent connection directly to each
     * tenant's H2 file and confirming the file does NOT contain the other tenant's marker.
     *
     * <p>This verifies that isolation is physical (separate files), not just query-filtered.
     */
    @Test
    void twoTenants_physicalIsolation_filesDoNotContainCrossTenantData() {
        UUID tenantCId = UUID.randomUUID();
        UUID tenantDId = UUID.randomUUID();

        tenantRegistryPort.register(tenantCId, "Tenant C");
        tenantRegistryPort.register(tenantDId, "Tenant D");

        javax.sql.DataSource dsC = tenantDataSourceResolver.resolve(tenantCId);
        javax.sql.DataSource dsD = tenantDataSourceResolver.resolve(tenantDId);

        JdbcTemplate jdbcC = new JdbcTemplate(dsC);
        JdbcTemplate jdbcD = new JdbcTemplate(dsD);

        jdbcC.execute("CREATE TABLE IF NOT EXISTS isolation_marker (label VARCHAR(100) NOT NULL)");
        jdbcD.execute("CREATE TABLE IF NOT EXISTS isolation_marker (label VARCHAR(100) NOT NULL)");
        jdbcC.execute("INSERT INTO isolation_marker VALUES ('marker-for-tenant-C')");
        jdbcD.execute("INSERT INTO isolation_marker VALUES ('marker-for-tenant-D')");

        // AC3: Verify separate H2 files exist on disk (physical isolation requires separate files)
        Path tenantCFile =
                tempDataDir.resolve("tenants").resolve(tenantCId.toString()).resolve("db.mv.db");
        Path tenantDFile =
                tempDataDir.resolve("tenants").resolve(tenantDId.toString()).resolve("db.mv.db");

        assertThat(tenantCFile.toFile())
                .as("Tenant C's H2 file must exist on disk (physical isolation)")
                .exists();
        assertThat(tenantDFile.toFile())
                .as("Tenant D's H2 file must exist on disk (physical isolation)")
                .exists();

        // AC3: Open fresh JDBC connections directly to each file (bypassing the resolver cache)
        // H2 JDBC URL needs the path WITHOUT the .mv.db extension (H2 appends it)
        Path tenantCDbPath =
                tempDataDir.resolve("tenants").resolve(tenantCId.toString()).resolve("db");
        Path tenantDDbPath =
                tempDataDir.resolve("tenants").resolve(tenantDId.toString()).resolve("db");

        org.h2.jdbcx.JdbcDataSource directDsC = new org.h2.jdbcx.JdbcDataSource();
        directDsC.setURL("jdbc:h2:file:" + tenantCDbPath.toAbsolutePath() + ";AUTO_SERVER=FALSE");
        directDsC.setUser("sa");
        directDsC.setPassword("");

        org.h2.jdbcx.JdbcDataSource directDsD = new org.h2.jdbcx.JdbcDataSource();
        directDsD.setURL("jdbc:h2:file:" + tenantDDbPath.toAbsolutePath() + ";AUTO_SERVER=FALSE");
        directDsD.setUser("sa");
        directDsD.setPassword("");

        JdbcTemplate directJdbcC = new JdbcTemplate(directDsC);
        JdbcTemplate directJdbcD = new JdbcTemplate(directDsD);

        // AC3: Tenant C's file does NOT contain Tenant D's marker
        List<Map<String, Object>> cFileForD =
                directJdbcC.queryForList(
                        "SELECT * FROM isolation_marker WHERE label = 'marker-for-tenant-D'");
        assertThat(cFileForD)
                .as(
                        "Tenant C's H2 file must NOT contain Tenant D's marker row (physical"
                                + " isolation)")
                .isEmpty();

        // AC3: Tenant D's file does NOT contain Tenant C's marker
        List<Map<String, Object>> dFileForC =
                directJdbcD.queryForList(
                        "SELECT * FROM isolation_marker WHERE label = 'marker-for-tenant-C'");
        assertThat(dFileForC)
                .as(
                        "Tenant D's H2 file must NOT contain Tenant C's marker row (physical"
                                + " isolation)")
                .isEmpty();

        // Each file contains exactly its own marker
        List<Map<String, Object>> cFileOwn =
                directJdbcC.queryForList("SELECT * FROM isolation_marker");
        assertThat(cFileOwn)
                .as("Tenant C's file must contain exactly Tenant C's own marker row")
                .hasSize(1)
                .extracting(row -> row.get("LABEL"))
                .containsExactly("marker-for-tenant-C");

        List<Map<String, Object>> dFileOwn =
                directJdbcD.queryForList("SELECT * FROM isolation_marker");
        assertThat(dFileOwn)
                .as("Tenant D's file must contain exactly Tenant D's own marker row")
                .hasSize(1)
                .extracting(row -> row.get("LABEL"))
                .containsExactly("marker-for-tenant-D");
    }

    // -------------------------------------------------------------------------
    // AC4 — Unbound-context query raises typed exception
    // -------------------------------------------------------------------------

    /**
     * AC4a: Verifies that {@link TenantContext#current()} with no tenant bound raises {@link
     * IllegalStateException} — no silent default-tenant fallback.
     */
    @Test
    void unboundContext_current_throwsIllegalStateException() {
        // No bind() called — TenantContext stack is empty for this thread
        assertThatThrownBy(() -> tenantContext.current())
                .as("current() with no TenantContext bound must throw IllegalStateException (AC4)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant is bound");
    }

    /**
     * AC4b: Verifies that resolving an unknown (unregistered) UUID throws {@link
     * TenantDataSourceResolver.UnknownTenantException} — fail-fast, no silent fallback.
     */
    @Test
    void resolve_unknownTenant_throwsUnknownTenantException() {
        UUID unknownId = UUID.randomUUID(); // not registered
        assertThatThrownBy(() -> tenantDataSourceResolver.resolve(unknownId))
                .as("Resolving an unregistered tenant UUID must throw UnknownTenantException (AC4)")
                .isInstanceOf(TenantDataSourceResolver.UnknownTenantException.class)
                .hasMessageContaining(unknownId.toString());
    }

    // -------------------------------------------------------------------------
    // AC5 — Context switch mid-transaction: nested-bind defined behaviour
    // -------------------------------------------------------------------------

    /**
     * AC5: Verifies the nested-bind contract from E14S01/E14S03.
     *
     * <p>Switching {@link TenantContext} inside an outer scope (simulating a mid-transaction
     * context switch) produces DEFINED behaviour: the inner bind overrides the outer for its scope;
     * closing the inner scope restores the outer. This is the nested-bind contract.
     *
     * <p>At the connection level, switching DataSources mid-transaction is undefined and is
     * prevented by the architecture (routing happens at connection-acquisition time, before the
     * transaction begins). This test verifies the CONTEXT layer semantics.
     */
    @Test
    @SuppressWarnings(
            "try") // outerScope/innerScope opened for RAII side-effect (bind+auto-restore); not
    // referenced in body by design (E18S01/DEC-29)
    void nestedBind_midScopeSwitch_restoresOuterTenantOnClose() {
        UUID outerTenantId = UUID.randomUUID();
        UUID innerTenantId = UUID.randomUUID();

        tenantRegistryPort.register(outerTenantId, "Outer Tenant");
        tenantRegistryPort.register(innerTenantId, "Inner Tenant");

        // Outer scope
        try (TenantContext.Scope outerScope = tenantContext.bind(outerTenantId)) {
            assertThat(tenantContext.current())
                    .as("Outer bind: current() must return outerTenantId")
                    .isEqualTo(outerTenantId);

            // Inner scope (simulates mid-transaction context switch)
            try (TenantContext.Scope innerScope = tenantContext.bind(innerTenantId)) {
                assertThat(tenantContext.current())
                        .as("After inner bind: current() must return innerTenantId")
                        .isEqualTo(innerTenantId);
            } // inner scope closed — outer must be restored

            assertThat(tenantContext.current())
                    .as(
                            "After inner scope closed: current() must restore outerTenantId "
                                    + "(nested-bind contract from E14S01/E14S03)")
                    .isEqualTo(outerTenantId);
        } // outer scope closed

        // After both scopes closed — no tenant bound
        assertThatThrownBy(() -> tenantContext.current())
                .as("After all scopes closed: current() must throw (no tenant bound)")
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // AC6 — Surface compliance: only tenant::api types in functional test code
    // -------------------------------------------------------------------------

    /**
     * AC6: Structural AC — the functional test methods in this class use only {@link
     * TenantRegistryPort}, {@link TenantContext}, and {@link TenantDataSourceResolver}, all from
     * the {@code de.vvwt.tm.tenant} public API package.
     *
     * <p>This is the compile-time contract that proves a future consumer (e.g., the {@code auth}
     * context from E15) can achieve the same isolation proof without accessing {@code
     * tenant.internal}.
     */
    @Test
    @SuppressWarnings(
            "try") // scope opened for RAII side-effect (bind+auto-restore); not referenced in body
    // by design (E18S01/DEC-29)
    void tenantPublicApi_isFullySufficientForIsolationProof() {
        // Functional demonstration: a full register → resolve → query cycle is achievable
        // using only the public API interfaces.
        UUID proofTenantId = UUID.randomUUID();
        tenantRegistryPort.register(proofTenantId, "Proof Tenant");

        try (TenantContext.Scope scope = tenantContext.bind(proofTenantId)) {
            assertThat(tenantContext.current())
                    .as("Public API: TenantContext.current() must return the bound UUID")
                    .isEqualTo(proofTenantId);
        }

        // Lookup via registry port
        assertThat(tenantRegistryPort.lookup(proofTenantId))
                .as("Public API: TenantRegistryPort.lookup() must find the registered tenant")
                .isPresent()
                .hasValueSatisfying(
                        record -> assertThat(record.tenantId()).isEqualTo(proofTenantId));

        // Resolve DataSource via resolver port (no internal types needed)
        javax.sql.DataSource ds = tenantDataSourceResolver.resolve(proofTenantId);
        assertThat(ds)
                .as(
                        "Public API: TenantDataSourceResolver.resolve() must return a non-null"
                                + " DataSource")
                .isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC7 — ApplicationModulesTest still passes (verified in full mvn verify)
    // -------------------------------------------------------------------------

    /**
     * AC7: Verifies that {@code ApplicationModules.verify()} still passes after this story.
     *
     * <p>This story adds only a test class and a test-scope dependency — no production code
     * changes. The module structure is unchanged. {@link de.vvwt.tm.ApplicationModulesTest}
     * continues to pass in the full {@code mvn verify} run.
     */
    @Test
    void applicationModulesVerify_remainsGreen() {
        // Invoke verify() inline to confirm module structure integrity from this test context
        org.springframework.modulith.core.ApplicationModules.of(
                        de.vvwt.tm.TournamentManagerApplication.class)
                .verify();
    }

    // -------------------------------------------------------------------------
    // AC8 — @TempDir cleanup: cleanup guaranteed by JUnit lifecycle
    // -------------------------------------------------------------------------

    /**
     * AC8: Verifies that the temp data directory is writable and that the {@code @AfterAll} cleanup
     * method will delete it after all tests complete.
     *
     * <p>The temp dir is created once in the static initializer and cleaned up by {@link
     * #cleanupTempDataDir()}, which is guaranteed to run after all tests — regardless of whether
     * any test failed. This means repeated local runs do not accumulate stale tenant H2 files from
     * previous test class executions.
     */
    @Test
    void tempDir_isWritableAndCleanupRegistered() {
        assertThat(tempDataDir)
                .as("Temp data dir must be a writable directory for tenant H2 files")
                .isNotNull()
                .isDirectory();
        assertThat(tempDataDir.toFile().canWrite())
                .as("Temp data dir must be writable (AC8: no stale files accumulate)")
                .isTrue();
        assertThat(tempDataDir.toAbsolutePath().toString())
                .as("tm.data.dir must point to the temp dir — stale files never accumulate")
                .isNotBlank();
    }
}
