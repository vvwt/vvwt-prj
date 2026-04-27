package de.vvwt.info.persistence.testsupport;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Poka-Yoke test-infrastructure utility for DAO integration tests in {@code vvwt-info-server}.
 *
 * <p>Module-local helper utility per DEC-46 clause #2(b). Enforces the three DEC-26 DAO test
 * governance rules (scope-extended by DEC-46 to all {@code vvwt-prj} modules):
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> {@link #applyMigration(DataSource,
 *       String)} delegates to Spring's {@code ScriptUtils}, ensuring the production Flyway
 *       migration bytes are what the test schema is built from.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> {@link #assertDbOf(DataSource)} returns
 *       an {@code AssertDbConnection} that inspects database state directly via the DataSource —
 *       the DAO under test must not be both subject and instrument.
 *   <li><b>Rule 3 — Read/write decoupling:</b> {@link #insertDirectly(DataSource, String, Map)}
 *       inserts fixture data via parameterised direct JDBC, so read-path tests don't depend on the
 *       DAO's own write method.
 * </ol>
 *
 * <p>This class is {@code final} with a private constructor. No inheritance-based extension is
 * possible — DAO-specific tests must compose, not extend. API growth is story-gated per DEC-26.
 *
 * <p>Uses H2 for self-host profile tests and Testcontainers PostgreSQL for primary profile tests.
 * The {@link #freshPostgresDataSource(PostgreSQLContainer)} method wraps a Testcontainers container
 * to provide a DataSource compatible with the DEC-26 three rules.
 *
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-26.md">DEC-26</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-46.md">DEC-46</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E38S03.story.md">E38S03</a>
 */
public final class InfoDaoTestSupport {

    /** Allowed identifier characters: letters, digits, underscore. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private InfoDaoTestSupport() {
        // utility class — no instances
    }

    // -------------------------------------------------------------------------
    // DataSource factories
    // -------------------------------------------------------------------------

    /**
     * Returns a fresh in-memory H2 {@link DataSource} with a unique URL per call (DEC-26 Rule 1
     * default backend).
     *
     * <p>Each invocation produces an independent database instance — no shared state between calls.
     * Suitable as a per-test {@link DataSource} in a {@code @BeforeEach} setup.
     *
     * @return a non-null, open-able DataSource backed by a uniquely named in-memory H2 DB
     */
    public static DataSource freshH2DataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:info-dao-test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * Returns a {@link DataSource} backed by the given Testcontainers PostgreSQL container.
     *
     * <p>The container must already be started by the test (e.g., via {@code @Container} and
     * {@code @Testcontainers}). This factory simply constructs a DataSource pointing at the
     * container's JDBC URL.
     *
     * @param container a started PostgreSQL Testcontainers container
     * @return a DataSource connected to the container
     */
    public static DataSource freshPostgresDataSource(PostgreSQLContainer<?> container) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(container.getJdbcUrl());
        ds.setUser(container.getUsername());
        ds.setPassword(container.getPassword());
        return ds;
    }

    // -------------------------------------------------------------------------
    // DEC-26 Rule 1 — Schema from production migration
    // -------------------------------------------------------------------------

    /**
     * Executes the SQL at the given classpath location against the provided DataSource.
     *
     * <p>Satisfies DEC-26 Rule 1: the production Flyway migration file is the single schema source
     * of truth. Pass the migration path relative to the classpath root, e.g.: {@code
     * "db/migration/h2/V1__initial_schema.sql"}.
     *
     * @param ds non-null DataSource to apply the migration to
     * @param classpathResource classpath-relative path to the SQL script
     * @throws IllegalStateException if the resource is not found or SQL execution fails
     */
    public static void applyMigration(DataSource ds, String classpathResource) {
        ClassPathResource resource = new ClassPathResource(classpathResource);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Migration resource not found on classpath: "
                            + classpathResource
                            + " — verify path matches the production Flyway migration location");
        }
        try (Connection conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn, resource);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to apply migration "
                            + classpathResource
                            + " to test DataSource: "
                            + e.getMessage(),
                    e);
        }
    }

    /**
     * Applies the full H2 schema (V1 h2 dialect + V2 common seed) to the given DataSource.
     *
     * <p>Convenience method for ITs testing the self-host (H2) profile. Applies migrations in
     * version order: {@code db/migration/h2/V1__initial_schema.sql} then {@code
     * db/migration/common/V2__algorithm_registry_seed.sql}.
     *
     * @param ds the DataSource to apply the migrations to (typically from {@link
     *     #freshH2DataSource()})
     */
    public static void applyFullH2Schema(DataSource ds) {
        applyMigration(ds, "db/migration/h2/V1__initial_schema.sql");
        applyMigration(ds, "db/migration/common/V2__algorithm_registry_seed.sql");
    }

    /**
     * Applies the full PostgreSQL schema (V1 postgres dialect + V2 common seed) to the given
     * DataSource.
     *
     * <p>Convenience method for ITs testing the primary (PostgreSQL) profile via Testcontainers.
     *
     * @param ds the DataSource to apply the migrations to (typically from {@link
     *     #freshPostgresDataSource(PostgreSQLContainer)})
     */
    public static void applyFullPostgresSchema(DataSource ds) {
        // Drop and recreate public schema so multiple test classes can share the same
        // Testcontainers PostgreSQL instance without "relation already exists" errors.
        try (Connection conn = ds.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA public CASCADE");
            stmt.execute("CREATE SCHEMA public");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to reset PostgreSQL public schema: " + e.getMessage(), e);
        }
        applyMigration(ds, "db/migration/postgresql/V1__initial_schema.sql");
        applyMigration(ds, "db/migration/common/V2__algorithm_registry_seed.sql");
    }

    // -------------------------------------------------------------------------
    // DEC-26 Rule 2 — Independent persistence verifier
    // -------------------------------------------------------------------------

    /**
     * Returns an assertj-db {@link AssertDbConnection} wired to the given DataSource.
     *
     * <p>Satisfies DEC-26 Rule 2: use this to verify database state after a DAO write instead of
     * the DAO's own read methods.
     *
     * @param ds non-null DataSource to inspect
     * @return an AssertDbConnection ready for table-level assertions
     */
    public static AssertDbConnection assertDbOf(DataSource ds) {
        return AssertDbConnectionFactory.of(ds).create();
    }

    // -------------------------------------------------------------------------
    // DEC-26 Rule 3 — Read/write decoupling (direct JDBC fixture insertion)
    // -------------------------------------------------------------------------

    /**
     * Inserts a single row into the named table via parameterised direct JDBC.
     *
     * <p>Satisfies DEC-26 Rule 3: use this to set up fixture data for read-path tests without
     * invoking the DAO's own write methods.
     *
     * <p>Column names are validated against a whitelist pattern and values are passed via {@code
     * PreparedStatement} parameters — no SQL injection possible through values.
     *
     * @param ds DataSource to insert into
     * @param table target table name (must satisfy identifier whitelist)
     * @param cols column-name → value map (must be non-empty)
     * @throws IllegalArgumentException if {@code cols} is empty or a column name fails the
     *     whitelist
     * @throws IllegalStateException if the JDBC insert fails
     */
    public static void insertDirectly(DataSource ds, String table, Map<String, Object> cols) {
        if (cols == null || cols.isEmpty()) {
            throw new IllegalArgumentException(
                    "insertDirectly requires at least one column — cols map must not be empty");
        }

        Map<String, Object> ordered = new LinkedHashMap<>(cols);

        for (String col : ordered.keySet()) {
            if (!SAFE_IDENTIFIER.matcher(col).matches()) {
                throw new IllegalArgumentException(
                        "Column name '"
                                + col
                                + "' contains unsafe characters. Only letters, digits, and"
                                + " underscores are allowed in column names passed to"
                                + " insertDirectly.");
            }
        }

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
        boolean first = true;
        for (String col : ordered.keySet()) {
            if (!first) sql.append(", ");
            sql.append(col);
            first = false;
        }
        sql.append(") VALUES (");
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append('?');
        }
        sql.append(')');

        try (Connection conn = ds.getConnection();
                var ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Object val : ordered.values()) {
                ps.setObject(idx++, val);
            }
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "insertDirectly into table '" + table + "' failed: " + e.getMessage(), e);
        }
    }
}
