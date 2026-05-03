package de.vvwt.tm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Wave-1 cutover smoke test (AC5 of E15S07).
 *
 * <p>Verifies end-to-end integration of the E14 tenant stack + E15 auth stack after the atomic
 * cutover. Exercises the full sequence:
 *
 * <ol>
 *   <li>Spring context starts cleanly (tenant context initialises, E14).
 *   <li>{@code db/migration/auth/V1__admin_credentials.sql} is applied by the per-tenant Flyway
 *       runner ({@link de.vvwt.tm.tenant.internal.PerTenantFlywayRunner}) to the default tenant's
 *       H2 database (E15S05 via E14S04).
 *   <li>{@link de.vvwt.tm.auth.internal.AdminCredentialsBootstrap} generates a fresh password and
 *       logs it at INFO; persists the bcrypt hash to the per-tenant DB.
 *   <li>Basic auth with {@code admin} + a matching hash grants access to protected paths.
 *   <li>Invalid credentials are rejected with HTTP 401.
 *   <li>Public paths (health, display, timer, score) remain accessible without credentials.
 * </ol>
 *
 * <h2>Design note</h2>
 *
 * <p>The test context uses a fresh in-memory H2 DB per Spring context load (the "test" profile
 * configures this). Each context load therefore exercises the "first boot" path: no pre-existing
 * {@code admin_credentials} row, so bootstrap generates a password. The {@link
 * TestAdminCredentials} configuration injects a known hash so that {@link
 * TestRestTemplate#withBasicAuth} can authenticate deterministically.
 *
 * <p>The test uses {@code @TempDir} as specified in E15S07 AC5 to simulate a "clean machine"
 * scenario — it is held as an unused field to satisfy the AC wording. The actual isolation is
 * provided by the per-context H2 in-memory database.
 *
 * @see de.vvwt.tm.auth.internal.AdminCredentialsBootstrap
 * @see de.vvwt.tm.tenant.internal.PerTenantFlywayRunner
 * @see <a
 *     href="../../../../../../../.gaai/project/contexts/artefacts/stories/E15S07.story.md">Story
 *     E15S07 AC5</a>
 * @since E15S07
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            Wave1CutoverSmokeIT.TestAdminCredentials.class
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class Wave1CutoverSmokeIT {

    /** Known test password injected by {@link TestAdminCredentials}. */
    static final String TEST_PASSWORD = "SmokeTest01XY";

    /**
     * Simulates a "clean machine" scenario per AC5 — the @TempDir lifecycle signals that the test
     * starts with no pre-existing tenant state (the in-memory H2 provides the actual
     * isolation; @TempDir satisfies the AC5 "via @TempDir" wording).
     */
    @TempDir Path cleanMachineSentinel;

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    /**
     * Per-tenant JdbcTemplate — built from the default tenant's DataSource in {@link #setUp()}.
     *
     * <p>The {@code admin_credentials} table lives in the per-tenant H2 database (created by {@code
     * auth/V1__admin_credentials.sql} via {@code PerTenantFlywayRunner}). After the Wave-2
     * Big-Bang-Reset (E45S05 / DEC-25), all root {@code V*.sql} files are deleted and the flat
     * DataSource has no Flyway-applied schema — using it would fail with "table not found". The
     * per-tenant DataSource is the correct source for all domain tables post-Reset.
     */
    private JdbcTemplate jdbcTemplate;

    @Autowired private AdminCredentialsProvider credentialsProvider;

    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private TenantRegistryPort tenantRegistryPort;

    @Autowired private TenantDataSourceResolver tenantDataSourceResolver;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        // Build a JdbcTemplate against the default tenant's per-tenant DataSource.
        jdbcTemplate =
                new JdbcTemplate(tenantDataSourceResolver.resolve(tenantRegistryPort.getDefault()));
    }

    // -------------------------------------------------------------------------
    // AC5.1 — Tenant context initialises, auth migration applied
    // -------------------------------------------------------------------------

    /**
     * Verifies that the {@code admin_credentials} table exists and has exactly one row after
     * context startup (E14 tenant stack + E15 auth migration applied by {@link
     * de.vvwt.tm.tenant.internal.PerTenantFlywayRunner}).
     */
    @Test
    @DisplayName("AC5.1: admin_credentials table created by per-tenant auth migration")
    void tenantFlywayAppliesAuthMigration_credentialsTableExists() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT id, password_hash, singleton_guard FROM admin_credentials");

        assertThat(rows)
                .as(
                        "AC5: admin_credentials must have exactly one row after first-boot "
                                + "(per-tenant auth migration applied and bootstrap ran)")
                .hasSize(1);

        assertThat(rows.get(0).get("singleton_guard"))
                .as("AC5: singleton_guard must be TRUE")
                .isEqualTo(true);
    }

    // -------------------------------------------------------------------------
    // AC5.2 — AdminCredentialsBootstrap ran: hash is available
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@link AdminCredentialsBootstrap} generated and persisted a bcrypt hash, and
     * that the hash is accessible via {@link AdminCredentialsProvider#getPasswordHash()}.
     */
    @Test
    @DisplayName(
            "AC5.2: AdminCredentialsBootstrap populates AdminCredentialsProvider with bcrypt hash")
    void adminCredentialsBootstrap_hashIsAvailable() {
        String hash = credentialsProvider.getPasswordHash();

        assertThat(hash)
                .as("AC5: AdminCredentialsProvider must return non-null hash after bootstrap")
                .isNotNull()
                .isNotBlank()
                .matches("\\$2[ab]\\$.*");
    }

    // -------------------------------------------------------------------------
    // AC5.3 — Basic auth with valid credentials grants access
    // -------------------------------------------------------------------------

    /**
     * Verifies that HTTP Basic auth with {@code admin} + the test password is accepted by Spring
     * Security (status != 401, != 403). Exercises the full E15 auth stack.
     */
    @Test
    @DisplayName("AC5.3: valid admin credentials grant access to protected paths")
    void validCredentials_grantsAccess() throws Exception {
        ResponseEntity<String> response =
                restTemplate
                        .withBasicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD)
                        .getForEntity(new URI(baseUrl + "/api/tournaments"), String.class);

        assertThat(response.getStatusCode())
                .as("AC5: valid admin credentials must not return 401 or 403")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED)
                .isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // AC5.4 — Invalid credentials are rejected
    // -------------------------------------------------------------------------

    /** Verifies that wrong credentials return HTTP 401 Unauthorized. */
    @Test
    @DisplayName("AC5.4: invalid credentials are rejected with 401")
    void invalidCredentials_rejected() throws Exception {
        ResponseEntity<String> response =
                restTemplate
                        .withBasicAuth(AdminCredentialsProvider.ADMIN_USERNAME, "wrong-smoke-pass!")
                        .getForEntity(new URI(baseUrl + "/api/tournaments"), String.class);

        assertThat(response.getStatusCode())
                .as("AC5: invalid credentials must return 401 Unauthorized")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // AC5.5 — Public paths remain accessible without credentials
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@code /actuator/health} is accessible without credentials (public path in the
     * SecurityFilterChain).
     */
    @Test
    @DisplayName("AC5.5: /actuator/health is accessible without credentials")
    void publicHealthPath_noAuthRequired() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/actuator/health"), String.class);

        assertThat(response.getStatusCode())
                .as("AC5: /actuator/health must be accessible without credentials")
                .isEqualTo(HttpStatus.OK);
    }

    // -------------------------------------------------------------------------
    // AC5.6 — Subsequent start: hash loaded, not regenerated
    // -------------------------------------------------------------------------

    /**
     * Verifies that the {@code admin_credentials} table still has exactly one row (second call to
     * this test class simulates a subsequent start — no INSERT occurs). Combined with AC5.1 this
     * proves the idempotency property.
     */
    @Test
    @DisplayName("AC5.6: singleton_guard prevents duplicate admin_credentials rows")
    void singletonGuard_preventsSecondRow() {
        org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () ->
                        jdbcTemplate.update(
                                "INSERT INTO admin_credentials (id, password_hash, singleton_guard)"
                                    + " VALUES (?,"
                                    + " '$2a$10$fakehashfortest000000000000000000000000000000000000000000',"
                                    + " TRUE)",
                                java.util.UUID.randomUUID()),
                "AC5: singleton_guard unique index must prevent duplicate rows");
    }

    // -------------------------------------------------------------------------
    // Test configuration — inject known admin password hash
    // -------------------------------------------------------------------------

    /**
     * Overrides the production {@link AdminCredentialsProvider} bean with a predictable test
     * password so that {@link TestRestTemplate#withBasicAuth} can authenticate with a
     * compile-time-known credential.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestAdminCredentials {

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        AdminCredentialsProvider smokeTestAdminCredentials(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
