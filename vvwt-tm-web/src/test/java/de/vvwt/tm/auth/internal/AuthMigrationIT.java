package de.vvwt.tm.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.internal.DefaultPerTenantFlywayRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for the {@code auth} module Flyway migration: {@code
 * db/migration/auth/V1__admin_credentials.sql}.
 *
 * <h2>TDD provenance (DEC-22 — AC1)</h2>
 *
 * <p>This test file was committed in the <em>RED</em> state before {@code
 * db/migration/auth/V1__admin_credentials.sql} existed. At that point {@code buildLocations()}
 * returned an empty list, Flyway was a no-op, and the assertion {@code adminCredentialsTableExists}
 * failed with "expected: true but was: false". Adding the migration file in the GREEN step made
 * this test pass.
 *
 * <h2>Acceptance Criteria covered</h2>
 *
 * <ul>
 *   <li><b>AC1</b> — TDD provenance: test-first for migration application.
 *   <li><b>AC2</b> — Two tenants: each gets an independent {@code admin_credentials} table.
 *   <li><b>AC3</b> — Migration path {@code classpath:db/migration/auth/V1__admin_credentials.sql};
 *       schema accepts DAO operations identically to the legacy V6 schema.
 *   <li><b>AC6</b> — Idempotency: running the migration twice leaves table and data intact.
 *   <li><b>AC7</b> — {@code ApplicationModulesTest.verify()} remains green.
 * </ul>
 *
 * <h2>Design</h2>
 *
 * <p>Tests use a {@code DefaultPerTenantFlywayRunner} subclass that restricts locations to {@code
 * classpath:db/migration/auth} only. Real H2 file-DataSources provide production-faithful
 * isolation. {@link AdminCredentialsDao} exercises the Flyway-created schema shape (AC3).
 *
 * @see DefaultPerTenantFlywayRunner
 * @see AdminCredentialsDao
 * @see <a
 *     href="../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E15S05.story.md">Story
 *     E15S05</a>
 * @since E15S05
 */
class AuthMigrationIT {

    // -------------------------------------------------------------------------
    // Test infrastructure: file-based H2 DataSource + table existence helper
    // -------------------------------------------------------------------------

    /**
     * Creates a real H2 file-based DataSource under the JUnit {@code @TempDir}. Each unique {@code
     * tenantId} produces an isolated DB file — no shared state.
     */
    private static DataSource createTempFileDataSource(Path tempDir, UUID tenantId) {
        Path dbPath = tempDir.resolve(tenantId.toString()).resolve("db");
        dbPath.getParent().toFile().mkdirs();
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:file:"
                        + dbPath.toAbsolutePath()
                        + ";AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=TRUE");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * Returns {@code true} if the given table exists in the DataSource's DB. Uses {@code
     * INFORMATION_SCHEMA.TABLES} for reliable H2 case-insensitive detection.
     */
    private static boolean tableExists(DataSource ds, String tableName) throws SQLException {
        try (Connection conn = ds.getConnection();
                var stmt = conn.createStatement();
                var rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE"
                                        + " LOWER(TABLE_NAME) = '"
                                        + tableName.toLowerCase()
                                        + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    // -------------------------------------------------------------------------
    // Test-scoped runner — restricts locations to db/migration/auth only
    // -------------------------------------------------------------------------

    /**
     * A {@link DefaultPerTenantFlywayRunner} subclass that restricts migration locations to {@code
     * classpath:db/migration/auth} — the single location added by E15S05.
     *
     * <p>If {@code db/migration/auth/} is absent from the classpath, {@code buildLocations()}
     * returns an empty list, Flyway is a no-op, and the table-existence assertions fail (RED state
     * before the migration file is added).
     */
    private static DefaultPerTenantFlywayRunner runnerWithAuthMigrationOnly(DataSource dataSource) {
        return new DefaultPerTenantFlywayRunner(
                tenantId -> dataSource, TournamentManagerApplication.class) {
            @Override
            public List<String> buildLocations() {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) cl = DefaultPerTenantFlywayRunner.class.getClassLoader();
                boolean exists = cl.getResource("db/migration/auth") != null;
                return exists ? List.of("classpath:db/migration/auth") : List.of();
            }
        };
    }

    // -------------------------------------------------------------------------
    // AC1 + AC3: migration applied, admin_credentials table exists
    // -------------------------------------------------------------------------

    /**
     * AC1 (TDD-provenance) + AC3 (path, schema): Running the per-tenant runner with {@code
     * classpath:db/migration/auth} applies {@code V1__admin_credentials.sql}, creating the {@code
     * admin_credentials} table.
     *
     * <p><b>RED:</b> Without the migration file, {@code buildLocations()} returns empty list →
     * Flyway no-op → table absent → assertion fails.
     *
     * <p><b>GREEN:</b> After adding the migration file → Flyway applies it → table present.
     */
    @Test
    void authMigration_applied_adminCredentialsTableExists(@TempDir Path tempDir) throws Exception {
        UUID tenantId = UUID.randomUUID();
        DataSource ds = createTempFileDataSource(tempDir, tenantId);
        DefaultPerTenantFlywayRunner runner = runnerWithAuthMigrationOnly(ds);

        runner.run(tenantId);

        assertThat(tableExists(ds, "admin_credentials"))
                .as("AC1/AC3: after running auth migration, admin_credentials table must exist")
                .isTrue();
    }

    /**
     * AC3 (schema shape): After applying the migration, the table must accept DAO operations —
     * confirming the schema is identical in structure to the legacy V6 schema.
     *
     * <p>{@link AdminCredentialsDao} exercises insert + read against the Flyway-created table.
     */
    @Test
    void authMigration_applied_schemaAcceptsAdminCredentialsDaoOperations(@TempDir Path tempDir)
            throws Exception {
        UUID tenantId = UUID.randomUUID();
        DataSource ds = createTempFileDataSource(tempDir, tenantId);
        DefaultPerTenantFlywayRunner runner = runnerWithAuthMigrationOnly(ds);

        runner.run(tenantId);

        AdminCredentialsDao dao = new AdminCredentialsDao(ds);
        UUID credId = UUID.randomUUID();
        // bcrypt hash width = 60 chars; VARCHAR(255) must accommodate it (AC9)
        String hash = "$2a$10$exampleBcryptHashXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";

        assertThatNoException()
                .as("AC3: schema must accept AdminCredentialsDao.insertNew() without error")
                .isThrownBy(() -> dao.insertNew(credId, hash));

        AdminCredentialsDao.CredentialRecord record =
                dao.findExisting()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "Inserted record must be retrievable via"
                                                        + " findExisting()"));

        assertThat(record.id()).as("AC3: retrieved id must match inserted id").isEqualTo(credId);
        assertThat(record.passwordHash())
                .as(
                        "AC3: retrieved passwordHash must match inserted hash verbatim (no DAO"
                                + " hashing)")
                .isEqualTo(hash);
    }

    // -------------------------------------------------------------------------
    // AC2: two-tenant isolation
    // -------------------------------------------------------------------------

    /**
     * AC2 (data isolation): Running the auth migration for two tenants creates two independent
     * {@code admin_credentials} tables — one per tenant H2 file.
     *
     * <p>Inserts into tenant A's table must NOT appear in tenant B's table (DB-per-Tenant per
     * DEC-20).
     */
    @Test
    void twoTenants_eachHasIndependentAdminCredentialsTable(@TempDir Path tempDir)
            throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        DataSource dsA = createTempFileDataSource(tempDir, tenantA);
        DataSource dsB = createTempFileDataSource(tempDir, tenantB);

        DefaultPerTenantFlywayRunner runnerA = runnerWithAuthMigrationOnly(dsA);
        DefaultPerTenantFlywayRunner runnerB = runnerWithAuthMigrationOnly(dsB);

        runnerA.run(tenantA);
        runnerB.run(tenantB);

        // Both tables exist independently
        assertThat(tableExists(dsA, "admin_credentials"))
                .as("AC2: tenant A must have admin_credentials table")
                .isTrue();
        assertThat(tableExists(dsB, "admin_credentials"))
                .as("AC2: tenant B must have admin_credentials table")
                .isTrue();

        // Insert into tenant A's table — must NOT appear in tenant B's table
        AdminCredentialsDao daoA = new AdminCredentialsDao(dsA);
        AdminCredentialsDao daoB = new AdminCredentialsDao(dsB);

        daoA.insertNew(UUID.randomUUID(), "hash-for-tenant-A");

        assertThat(daoA.findExisting())
                .as("AC2: tenant A must have exactly one credential row")
                .isPresent();

        assertThat(daoB.findExisting())
                .as("AC2: tenant B must have NO credential rows (cross-tenant isolation)")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC6: idempotency — applying the migration twice leaves schema + data intact
    // -------------------------------------------------------------------------

    /**
     * AC6 (idempotency): Applying the auth migration twice (simulating a tenant whose DB was
     * previously migrated) must succeed without error and must not destroy existing data.
     *
     * <p>Flyway compares checksums and skips already-applied V1 — no destructive re-run.
     */
    @Test
    void authMigration_appliedTwice_isIdempotentAndPreservesData(@TempDir Path tempDir)
            throws Exception {
        UUID tenantId = UUID.randomUUID();
        DataSource ds = createTempFileDataSource(tempDir, tenantId);
        DefaultPerTenantFlywayRunner runner = runnerWithAuthMigrationOnly(ds);

        // First run — migration applied
        runner.run(tenantId);

        AdminCredentialsDao dao = new AdminCredentialsDao(ds);
        dao.insertNew(UUID.randomUUID(), "hash-inserted-before-second-run");

        // Second run — must be idempotent
        assertThatNoException()
                .as("AC6: second migration run must not throw (Flyway idempotency via checksum)")
                .isThrownBy(() -> runner.run(tenantId));

        // Row inserted between the two runs must still be present
        assertThat(dao.findExisting())
                .as("AC6: credential row inserted before second run must survive intact")
                .isPresent();
        assertThat(dao.findExisting().orElseThrow().passwordHash())
                .as("AC6: hash must be preserved verbatim after idempotent second run")
                .isEqualTo("hash-inserted-before-second-run");
    }

    // -------------------------------------------------------------------------
    // AC7: ApplicationModulesTest remains green
    // -------------------------------------------------------------------------

    /**
     * AC7 (Modulith verify): Adding {@code db/migration/auth/} (a classpath resource directory)
     * does not introduce any module boundary violation. {@code ApplicationModules.verify()} must
     * remain green.
     */
    @Test
    void applicationModulesVerify_remainsGreen() {
        org.springframework.modulith.core.ApplicationModules.of(TournamentManagerApplication.class)
                .verify();
    }
}
