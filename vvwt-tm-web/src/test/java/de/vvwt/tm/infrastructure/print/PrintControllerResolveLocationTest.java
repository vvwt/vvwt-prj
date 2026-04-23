package de.vvwt.tm.infrastructure.print;

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
 * RED-first unit test for {@link PrintController#resolveLocationDisplayName(UUID)} (E23S10, DEC-22
 * Q-1a Iron Law).
 *
 * <h2>DEC-26 three-rule compliance</h2>
 *
 * <ul>
 *   <li><b>Rule 1 — Schema from production migration:</b> {@link
 *       TenantDaoTestSupport#applyTournamentSchema(DataSource)} loads the {@code
 *       db/migration/V1__initial_schema.sql} file that contains {@code CREATE TABLE tenants} and
 *       {@code CREATE TABLE locations}. No inline DDL.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Not applicable — this test is a read-path
 *       test. The assertion is on the return value of {@code resolveLocationDisplayName}, not on
 *       database state after a write. DEC-26 Rule 2 applies to write-path tests only.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Fixture rows are inserted via {@link
 *       TenantDaoTestSupport#insertDirectly(DataSource, String, Map)} — direct JDBC, not via any
 *       DAO write method.
 * </ul>
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This test is in the SAME Java package as {@link PrintController} ({@code
 * de.vvwt.tm.infrastructure.print}). Same-package white-box access is explicitly permitted by
 * DEC-36 (amended DEC-22). No cross-package type reference requirement applies here.
 *
 * <h2>DEC-39 — tenant_id WHERE predicate preserved pre-Big-Bang</h2>
 *
 * <p>The tested method preserves the {@code tenant_id = ?} discriminator verbatim from the legacy
 * {@code CertificateAssembler.resolveLocationDisplayName}. Per DEC-39, tenant-scoped column removal
 * is deferred to Wave-2 Big-Bang-Reset. This test verifies the pre-Big-Bang behavior.
 *
 * @see PrintController#resolveLocationDisplayName(UUID)
 * @see TenantDaoTestSupport
 * @since E23S10
 */
@DisplayName("PrintController — resolveLocationDisplayName (E23S10, DEC-22 Q-1a, DEC-26)")
class PrintControllerResolveLocationTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    private JdbcTemplate jdbcTemplate;
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        // DEC-26 Rule 1: fresh isolated DataSource; schema loaded from production migration
        dataSource = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyTournamentSchema(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);

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

        // Construct minimal PrintController instance for this method only.
        // Full constructor wiring is tested by PrintCertificateIT (@SpringBootTest).
        PrintController controller = buildController();

        String result = controller.resolveLocationDisplayName(TENANT_A);

        assertThat(result).isEqualTo("Sporthalle Musterstadt");
    }

    @Test
    @DisplayName("returns empty string when no location exists for tenant")
    void resolveLocationDisplayName_returnsEmptyString_whenNoLocationForTenant() {
        // No location row inserted for TENANT_B — expect empty string result
        PrintController controller = buildController();

        String result = controller.resolveLocationDisplayName(TENANT_B);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns only the location for the specified tenant (tenant isolation)")
    void resolveLocationDisplayName_returnsOnlyOwnTenantLocation() {
        // Insert location for TENANT_A only
        TenantDaoTestSupport.insertDirectly(
                dataSource,
                "locations",
                Map.of("id", UUID.randomUUID(), "tenant_id", TENANT_A, "display_name", "Halle A"));

        PrintController controller = buildController();

        // TENANT_A has a location
        assertThat(controller.resolveLocationDisplayName(TENANT_A)).isEqualTo("Halle A");
        // TENANT_B has no location
        assertThat(controller.resolveLocationDisplayName(TENANT_B)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Test helper
    // -------------------------------------------------------------------------

    /**
     * Constructs a {@link PrintController} with only the {@link JdbcTemplate} wired — sufficient
     * for testing {@link PrintController#resolveLocationDisplayName(UUID)}.
     *
     * <p>All other constructor parameters are {@code null}; the tested method does not use them.
     * Full integration wiring is exercised by {@link PrintCertificateIT}.
     */
    private PrintController buildController() {
        return new PrintController(
                /* tournamentRepository */ null,
                /* phaseRepository */ null,
                /* matchRepository */ null,
                /* teamRepository */ null,
                /* teamAvatarRepository */ null,
                /* phaseBreakRepository */ null,
                /* activityTypeRepository */ null,
                /* laufzettelAssembler */ null,
                /* activityScheduleAssembler */ null,
                /* certificateAssembler */ null,
                /* certificateTemplateService */ null,
                /* tenantContext */ null,
                /* messageSource */ null,
                /* buildProperties */ null,
                /* jdbcTemplate */ jdbcTemplate);
    }
}
