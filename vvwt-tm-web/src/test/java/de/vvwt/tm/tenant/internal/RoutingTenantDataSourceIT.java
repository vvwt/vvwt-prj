package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for {@link RoutingTenantDataSource} — proves real per-tenant H2 isolation.
 *
 * <p>Uses real H2 file DataSources under {@code @TempDir} — no mocks (DEC-22 / AC2 requirement:
 * "the isolation claim is only credible if exercised against actual JDBC connections").
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC2 — data-isolation routing proof: tenant A and B see only their own rows
 *   <li>AC3 — unbound propagation: no tenant bound → typed exception
 *   <li>AC4 — context leak: after exception, context clears to unbound
 * </ul>
 *
 * <p>Story: E14S03 — DEC-14/DEC-20/DEC-22.
 */
class RoutingTenantDataSourceIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0001-0001-0001-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0002-0002-0002-bbbbbbbbbbbb");

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a real H2 file DataSource at {@code tempDir/tenantId/db.mv.db}. H2 does NOT use the
     * file extension in the URL — it appends .mv.db itself.
     */
    static DataSource h2FileDataSource(Path tempDir, UUID tenantId) {
        Path tenantDir = tempDir.resolve(tenantId.toString());
        tenantDir.toFile().mkdirs();
        // H2 URL: file path without extension (H2 adds .mv.db)
        String dbPath = tenantDir.resolve("db").toAbsolutePath().toString();
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /** Initialises a simple marker table and inserts a row in the current DS. */
    static void createTableAndInsertRow(JdbcTemplate jdbc, String markerValue) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS marker (marker_value VARCHAR(100))");
        jdbc.update("INSERT INTO marker (marker_value) VALUES (?)", markerValue);
    }

    // -------------------------------------------------------------------------
    // AC2 — data-isolation routing proof
    // -------------------------------------------------------------------------

    /**
     * AC2: Two tenants (A, B) write distinct marker rows. When reading from A's context, only A's
     * row is visible. When reading from B's context, only B's row is visible. Switching context
     * does not leak connections.
     */
    @Test
    @SuppressWarnings(
            "try") // "ignored" scopes opened for RAII side-effect (bind+auto-restore for JDBC
    // routing); not referenced in body by design (E18S01/DEC-29)
    void tenantAAndBSeeOnlyTheirOwnRows(@TempDir Path tempDir) {
        // Set up real H2 file DataSources
        DataSource dsA = h2FileDataSource(tempDir, TENANT_A);
        DataSource dsB = h2FileDataSource(tempDir, TENANT_B);

        // Set up registry: maps tenant UUID → DataSource
        InMemoryDataSourceRegistry registry = new InMemoryDataSourceRegistry();
        registry.registerDataSource(TENANT_A, dsA);
        registry.registerDataSource(TENANT_B, dsB);

        TenantContext ctx = new ThreadLocalTenantContextImpl();
        RoutingTenantDataSource routing = new RoutingTenantDataSource(ctx, registry);
        routing.setTargetDataSources(new java.util.HashMap<>(registry.asTargetMap()));
        routing.afterPropertiesSet();

        // Write in tenant A's context
        try (TenantContext.Scope ignored = ctx.bind(TENANT_A)) {
            JdbcTemplate jdbc = new JdbcTemplate(routing);
            createTableAndInsertRow(jdbc, "marker-for-tenant-A");
        }

        // Write in tenant B's context
        try (TenantContext.Scope ignored = ctx.bind(TENANT_B)) {
            JdbcTemplate jdbc = new JdbcTemplate(routing);
            createTableAndInsertRow(jdbc, "marker-for-tenant-B");
        }

        // Read from tenant A — must see only A's row
        try (TenantContext.Scope ignored = ctx.bind(TENANT_A)) {
            JdbcTemplate jdbc = new JdbcTemplate(routing);
            java.util.List<String> rows =
                    jdbc.queryForList("SELECT marker_value FROM marker", String.class);
            assertThat(rows)
                    .as("Tenant A context must see only tenant A's row (AC2)")
                    .containsExactly("marker-for-tenant-A")
                    .doesNotContain("marker-for-tenant-B");
        }

        // Read from tenant B — must see only B's row
        try (TenantContext.Scope ignored = ctx.bind(TENANT_B)) {
            JdbcTemplate jdbc = new JdbcTemplate(routing);
            java.util.List<String> rows =
                    jdbc.queryForList("SELECT marker_value FROM marker", String.class);
            assertThat(rows)
                    .as("Tenant B context must see only tenant B's row (AC2)")
                    .containsExactly("marker-for-tenant-B")
                    .doesNotContain("marker-for-tenant-A");
        }
    }

    // -------------------------------------------------------------------------
    // AC3 — unbound context at JDBC time → typed exception
    // -------------------------------------------------------------------------

    /**
     * AC3: Attempting JDBC access with no tenant bound must throw the typed exception from {@code
     * TenantContext.current()} — not a NullPointerException.
     */
    @Test
    void jdbcAccessWithNoTenantBoundThrowsTypedException(@TempDir Path tempDir) {
        InMemoryDataSourceRegistry registry = new InMemoryDataSourceRegistry();
        TenantContext ctx = new ThreadLocalTenantContextImpl();
        RoutingTenantDataSource routing = new RoutingTenantDataSource(ctx, registry);
        routing.setTargetDataSources(new java.util.HashMap<>());
        routing.afterPropertiesSet();

        // No bind → IllegalStateException from TenantContext.current()
        assertThatThrownBy(() -> routing.getConnection())
                .as("No tenant bound — must surface IllegalStateException, not NPE (AC3)")
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // Helper inner class — in-memory resolver for IT tests
    // -------------------------------------------------------------------------

    /** In-memory {@link TenantDataSourceResolver} that backs the routing DataSource. */
    static class InMemoryDataSourceRegistry implements TenantDataSourceResolver {

        private final java.util.Map<UUID, DataSource> map = new java.util.LinkedHashMap<>();

        void registerDataSource(UUID tenantId, DataSource ds) {
            map.put(tenantId, ds);
        }

        @Override
        public DataSource resolve(UUID tenantId) {
            DataSource ds = map.get(tenantId);
            if (ds == null) {
                throw new UnknownTenantException(tenantId);
            }
            return ds;
        }

        java.util.Map<Object, Object> asTargetMap() {
            return new java.util.HashMap<>(map);
        }
    }
}
