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
 * RED-first DAO integration test for {@link DefaultLocationDisplayResolver} (E24S04, DEC-22 Q-1a
 * interpretation-α Iron Law).
 *
 * <h2>DEC-26 three-rule compliance</h2>
 *
 * <ul>
 *   <li><b>Rule 1 — Schema from production migration:</b> {@link
 *       TenantDaoTestSupport#applyTournamentSchema(DataSource)} loads {@code
 *       db/migration/V1__initial_schema.sql} which contains {@code CREATE TABLE tenants} and {@code
 *       CREATE TABLE locations}. No inline DDL.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> N/A — read-path test. Assertion is on the
 *       return value of {@link DefaultLocationDisplayResolver#resolveLocationDisplayName(UUID)}, not
 *       on database state after a write. DEC-26 Rule 2 applies to write-path tests only. Precedent:
 *       {@code PrintControllerResolveLocationTest}.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Fixture rows are inserted via {@link
 *       TenantDaoTestSupport#insertDirectly(DataSource, String, Map)} — direct JDBC, not via any
 *       DAO write method.
 * </ul>
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This test is in the SAME Java package as {@link DefaultLocationDisplayResolver} ({@code
 * de.vvwt.tm.tenant.internal}). Same-package white-box access is explicitly permitted by DEC-36
 * (amended DEC-22). Type reference to the implementation class is allowed.
 *
 * <h2>DEC-39 — tenant_id WHERE predicate preserved pre-Big-Bang</h2>
 *
 * <p>The resolver preserves the {@code tenant_id = ?} discriminator verbatim (DEC-39 interim
 * state). Test case (c) verifies tenant isolation: tenant B's data is not visible to tenant A's
 * query and vice versa.
 *
 * <h2>DEC-22 interpretation-α commit sequence</h2>
 *
 * <ol>
 *   <li>RED commit: this test file exists; {@link DefaultLocationDisplayResolver} and {@code
 *       LocationDisplayResolver} both ABSENT → compile error (this IS the RED state).
 *   <li>GREEN commit: interface + impl authored in same commit; test compiles AND all 3 scenarios
 *       pass.
 * </ol>
 *
 * @see DefaultLocationDisplayResolver
 * @see TenantDaoTestSupport
 * @since E24S04
 */
@DisplayName("DefaultLocationDisplayResolver — DEC-26 DAO test (E24S04, DEC-22 Q-1a α, DEC-26)")
class DefaultLocationDisplayResolverTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    private DataSource dataSource;
    private DefaultLocationDisplayResolver resolver;

    @BeforeEach
    void setUp() {
        // DEC-26 Rule 1: fresh isolated DataSource; schema loaded from production migration
        dataSource = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyTournamentSchema(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        resolver = new DefaultLocationDisplayResolver(jdbcTemplate);

        // Insert tenant rows (required by FK constraint on locations.tenant_id)
        // DEC-26 Rule 3: fixture rows inserted via direct JDBC
        TenantDaoTestSupport.insertDirectly(
                dataSource,
                "tenants",
                Map.of(
                        "id",
                        TENANT_A,
                        "display_name",
                        "Tenant A",
                        "tenant_location_count",
                        1,
                        "is_default",
                        false));
        TenantDaoTestSupport.insertDirectly(
                dataSource,
                "tenants",
                Map.of(
                        "id",
                        TENANT_B,
                        "display_name",
                        "Tenant B",
                        "tenant_location_count",
                        1,
                        "is_default",
                        false));
    }

    /** AC-DAO-TEST-CASES (a): single location present → returns display_name. */
    @Test
    @DisplayName("returns display_name when location exists for tenant")
    void resolveLocationDisplayName_returnsDisplayName_whenLocationExists() {
        // DEC-26 Rule 3: fixture row inserted via direct JDBC
        TenantDaoTestSupport.insertDirectly(
                dataSource,
                "locations",
                Map.of(
                        "id",
                        UUID.randomUUID(),
                        "tenant_id",
                        TENANT_A,
                        "display_name",
                        "Sporthalle Musterstadt"));

        String result = resolver.resolveLocationDisplayName(TENANT_A);

        // Rule 2 N/A read-path: assertion on returned value, not on DB-state-post-write
        assertThat(result).isEqualTo("Sporthalle Musterstadt");
    }

    /** AC-DAO-TEST-CASES (b): no location for tenant → returns empty string "". */
    @Test
    @DisplayName("returns empty string when no location exists for tenant")
    void resolveLocationDisplayName_returnsEmptyString_whenNoLocationForTenant() {
        // No location row for TENANT_B — expect empty string
        String result = resolver.resolveLocationDisplayName(TENANT_B);

        assertThat(result).isEmpty();
    }

    /**
     * AC-DAO-TEST-CASES (c): tenant isolation — tenant A's fixture NOT visible to tenant B lookup.
     *
     * <p>DEC-39 interim state: {@code tenant_id = ?} predicate enforces isolation within single
     * DataSource.
     */
    @Test
    @DisplayName("tenant isolation — tenant A location not visible to tenant B lookup")
    void resolveLocationDisplayName_enforcesTenantIsolation() {
        // Insert location for TENANT_A only
        TenantDaoTestSupport.insertDirectly(
                dataSource,
                "locations",
                Map.of("id", UUID.randomUUID(), "tenant_id", TENANT_A, "display_name", "Halle A"));

        // TENANT_A has a location → returns display_name
        assertThat(resolver.resolveLocationDisplayName(TENANT_A)).isEqualTo("Halle A");
        // TENANT_B has no location → returns ""
        assertThat(resolver.resolveLocationDisplayName(TENANT_B)).isEmpty();
    }
}
