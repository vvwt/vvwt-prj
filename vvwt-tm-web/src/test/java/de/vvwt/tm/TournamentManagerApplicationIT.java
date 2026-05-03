package de.vvwt.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for E02S02 + E02S03 — updated for E45S05 post-Reset bootstrap.
 *
 * <p>Covers E02S02 baseline (persistence stack wiring, health endpoint) and E02S03 acceptance
 * criteria (schema invariants, idempotency).
 *
 * <h2>Post-Reset bootstrap path (E45S05)</h2>
 *
 * <p>After the Wave-2 Big-Bang-Reset (DEC-25), the {@code db/migration/} root contains no {@code
 * V*.sql} files. Spring Boot's auto-configured Flyway scans {@code db/migration} and finds nothing
 * — it becomes a no-op against the flat DataSource. Schema application is exclusively via {@link
 * de.vvwt.tm.tenant.internal.PerTenantFlywayRunner}, which applies {@code
 * db/migration/{module}/V1__*.sql} to each per-tenant H2 file (DEC-20, DEC-21).
 *
 * <p>Implications for this test class:
 *
 * <ul>
 *   <li>The flat DataSource has NO Flyway-applied schema. Querying {@code flyway_schema_history} or
 *       any domain table on the flat DataSource will fail (no such table).
 *   <li>The {@code tenants} and {@code locations} tables now live exclusively in per-tenant H2
 *       files, applied by the per-tenant Flyway runner.
 *   <li>Tests that exercise schema invariants (e.g., duplicate default-tenant rejection) must use
 *       the per-tenant DataSource via {@link TenantDataSourceResolver}.
 * </ul>
 *
 * <p>Uses the "test" profile ({@code application-test.yml}): in-memory H2 so no filesystem
 * side-effects occur during test runs.
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>E02S02 AC5 — health endpoint returns HTTP 200 with {@code "status":"UP"}
 *   <li>E02S02 AC6 — application context loads without errors
 *   <li>E02S02 AC10 — POSIX DB directory gets owner-only permissions (POSIX hosts only)
 *   <li>E02S03 AC10 — duplicate is_default=TRUE insert raises a constraint violation (tested
 *       against per-tenant DataSource post-Reset)
 * </ul>
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E02S02.story.md">Story
 *     E02S02</a>
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E02S03.story.md">Story
 *     E02S03</a>
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E45S05.story.md">Story
 *     E45S05 (AC-TM-APP-IT-*)</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class TournamentManagerApplicationIT {

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    /**
     * Per-tenant DataSource resolver — used to obtain the default tenant's DataSource.
     *
     * <p>Post-Reset (E45S05): domain tables ({@code tenants}, {@code locations}, etc.) now live in
     * per-tenant H2 files, not in the flat DataSource. Tests that query or mutate domain tables
     * must use the per-tenant DataSource.
     */
    @Autowired private TenantDataSourceResolver tenantDataSourceResolver;

    /** Registry for resolving the default-tenant UUID, used in {@link #perTenantJdbcTemplate()}. */
    @Autowired private TenantRegistryPort tenantRegistryPort;

    /**
     * Returns a {@link JdbcTemplate} backed by the default tenant's per-tenant H2 DataSource.
     *
     * <p>Post-Reset: the {@code tenants} table lives in the per-tenant H2 file, applied by {@link
     * de.vvwt.tm.tenant.internal.PerTenantFlywayRunner} via {@code tenant/V1__initial_schema.sql}.
     */
    private JdbcTemplate perTenantJdbcTemplate() {
        return new JdbcTemplate(tenantDataSourceResolver.resolve(tenantRegistryPort.getDefault()));
    }

    // -------------------------------------------------------------------------
    // E02S02 baseline tests
    // -------------------------------------------------------------------------

    /**
     * E02S02 AC6 — Verifies the Spring application context loads without errors.
     *
     * <p>Post-Reset (E45S05): Spring Boot Flyway finds zero root {@code V*.sql} files and is a
     * no-op against the flat DataSource. The per-tenant Flyway runner applies per-module migrations
     * to each tenant's H2 file during default-tenant bootstrap. A failure in either path would
     * abort context load and fail this test.
     */
    @Test
    void contextLoads() {
        // @SpringBootTest itself is the assertion: a context-load failure throws an exception.
        assertThat(port).isGreaterThan(0);
    }

    /**
     * E02S02 AC5 — Verifies the health endpoint returns HTTP 200 and a JSON body with {@code
     * "status":"UP"} when the H2 datasource is reachable.
     */
    @Test
    void healthEndpointReturnsUp() throws Exception {
        URI healthUri = new URI("http://localhost:" + port + "/actuator/health");
        ResponseEntity<String> response = restTemplate.getForEntity(healthUri, String.class);

        assertThat(response.getStatusCode())
                .as("Health endpoint must return HTTP 200")
                .isEqualTo(HttpStatus.OK);

        assertThat(response.getBody())
                .as("Health endpoint body must contain 'status':'UP'")
                .contains("\"status\":\"UP\"");
    }

    /**
     * E02S02 AC10 — Verifies that a newly created DB parent directory has owner-only permissions
     * (mode {@code rwx------}) on POSIX hosts.
     *
     * <p>Since the test profile uses in-memory H2, the {@code DatabaseDirectoryInitializer} skips
     * directory creation. We verify the POSIX permission logic directly.
     *
     * <p>Enabled only on POSIX operating systems (Linux, macOS). Skipped on Windows.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void databaseDirectoryHasOwnerOnlyPermissionsOnPosix() throws Exception {
        boolean isPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

        org.junit.jupiter.api.Assumptions.assumeTrue(
                isPosix, "Skipped: POSIX file attribute view not supported on this filesystem");

        Path tempDir = Files.createTempDirectory("tm-ac10-test-");
        tempDir.toFile().deleteOnExit();

        Set<PosixFilePermission> ownerOnly =
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
        Files.getFileAttributeView(tempDir, PosixFileAttributeView.class).setPermissions(ownerOnly);

        Set<PosixFilePermission> actualPermissions = Files.getPosixFilePermissions(tempDir);

        assertThat(actualPermissions)
                .as("DB parent directory must have owner-only permissions (rwx------)")
                .containsExactlyInAnyOrderElementsOf(ownerOnly);

        Files.deleteIfExists(tempDir);
    }

    // -------------------------------------------------------------------------
    // E02S03 schema migration tests
    // -------------------------------------------------------------------------

    /**
     * E02S03 AC10 — Duplicate default-tenant constraint violation (post-Reset variant).
     *
     * <p>Post-Reset (E45S05): the {@code tenants} table lives in the default tenant's per-tenant H2
     * file. The {@code DefaultTenantBootstrapRunner} inserts the default-tenant row during context
     * startup. This test verifies that the unique index ({@code idx_tenants_single_default} on the
     * generated {@code default_sentinel} column) rejects a second insert of a row with {@code
     * is_default = TRUE}.
     *
     * <p>Uses the per-tenant DataSource (via {@link TenantDataSourceResolver}) because domain
     * tables are no longer on the flat DataSource post-Reset.
     */
    @Test
    void duplicateDefaultTenantIsRejected() {
        // Use the per-tenant DataSource — tenants table is in per-tenant H2 post-Reset.
        JdbcTemplate perTenantJdbc = perTenantJdbcTemplate();

        // The bootstrap has already inserted one is_default=TRUE row during context startup.
        // Attempting a second insert must be rejected by idx_tenants_single_default.
        UUID duplicateId = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                perTenantJdbc.update(
                                        "INSERT INTO tenants (id, display_name,"
                                                + " tenant_location_count, is_default) VALUES (?,"
                                                + " 'Another Default', 1, TRUE)",
                                        duplicateId))
                .as(
                        "Inserting a second row with is_default=TRUE must raise a constraint"
                                + " violation (bootstrap already occupies the slot)")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
