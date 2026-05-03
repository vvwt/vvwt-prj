package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RED-first DAO integration test for {@link DefaultLocationDisplayResolver} after E45S04 predicate
 * removal (DEC-22 Q-1a Iron Law; DEC-41 §3 hierarchy item (1)).
 *
 * <h2>DEC-41 Snapshot-Driven deletion rationale</h2>
 *
 * <p>The three prior tests (E24S04 batch) were classified Snapshot-Driven per DEC-41 §1 observable
 * form — they captured the {@code tenant_id = ?} WHERE predicate behavior as a regression safety
 * net (zero jqwik @Property, zero round-trip/bijection, zero external-spec citation, zero named
 * algebraic invariant). All three are replaced by RED-first tests that specify the post-removal
 * behavior: {@code resolveLocationDisplayName()} with no tenantId parameter, relying on per-tenant
 * routing (DEC-20 AbstractRoutingDataSource) for isolation.
 *
 * <h2>DEC-26 three-rule compliance</h2>
 *
 * <ul>
 *   <li><b>Rule 1 — Schema from production migration:</b> {@link
 *       TenantDaoTestSupport#applyTournamentSchema(DataSource)} loads {@code
 *       db/migration/V1__initial_schema.sql} which contains {@code CREATE TABLE locations}.
 *   <li><b>Rule 2 — N/A:</b> read-path test; assertion on return value, not DB-state-post-write.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Fixture rows inserted via {@link
 *       TenantDaoTestSupport#insertDirectly}.
 * </ul>
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>Same Java package as {@link DefaultLocationDisplayResolver} — white-box access permitted.
 *
 * @see DefaultLocationDisplayResolver
 * @since E45S04 — predicate removed; no-param signature per DEC-50 / DEC-39
 */
@DisplayName("DefaultLocationDisplayResolver — E45S04 post-predicate-removal (DEC-22 Q-1a)")
class DefaultLocationDisplayResolverTest {

    private static final UUID TENANT_ID = UUID.fromString("a18e4f23-ac24-467b-baa1-87e30b54d2ac");

    private DataSource dataSource;
    private DefaultLocationDisplayResolver resolver;

    @BeforeEach
    void setUp() {
        // DEC-26 Rule 1: fresh isolated DataSource; schema from production migration
        dataSource = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyTournamentSchema(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        resolver = new DefaultLocationDisplayResolver(jdbcTemplate);

        // Insert tenant row (required by FK on locations.tenant_id in current pre-Reset schema)
        TenantDaoTestSupport.insertDirectly(
                dataSource,
                "tenants",
                Map.of(
                        "id",
                        TENANT_ID,
                        "display_name",
                        "Default (LAN)",
                        "tenant_location_count",
                        1,
                        "is_default",
                        true));
    }

    /**
     * E45S04-AC-a: location row present → returns display_name.
     *
     * <p>No tenantId filter — per-tenant routing (DEC-20) ensures all rows in this DataSource
     * belong to one tenant.
     */
    @Test
    @DisplayName("returns display_name when a location row exists (no tenantId param)")
    void resolveLocationDisplayName_returnsDisplayName_whenLocationExists() {
        // DEC-26 Rule 3: fixture via direct JDBC
        // tenant_id still required by FK (pre-Reset schema); uses the tenant inserted in setUp()
        TenantDaoTestSupport.insertDirectly(
                dataSource,
                "locations",
                Map.of(
                        "id",
                        UUID.randomUUID(),
                        "tenant_id",
                        TENANT_ID,
                        "display_name",
                        "Sporthalle Musterstadt"));

        String result = resolver.resolveLocationDisplayName();

        assertThat(result).isEqualTo("Sporthalle Musterstadt");
    }

    /** E45S04-AC-b: no location rows → returns empty string "". */
    @Test
    @DisplayName("returns empty string when no location row exists")
    void resolveLocationDisplayName_returnsEmptyString_whenNoLocations() {
        // No locations inserted — expect empty string
        String result = resolver.resolveLocationDisplayName();

        assertThat(result).isEmpty();
    }
}
