package de.vvwt.tm.infrastructure.testsupport;

import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Poka-Yoke test-infrastructure utility for DAO integration tests in {@code vvwt-tm-web}.
 *
 * <p>Enforces the three DEC-26 DAO test governance rules by making the correct path
 * the easiest path:
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> {@link #applyMigration(DataSource, String)}
 *       delegates to Spring's {@code ScriptUtils}, ensuring the production Flyway migration
 *       bytes are what the test schema is built from.</li>
 *   <li><b>Rule 2 — Independent persistence verifier:</b> {@link #assertDbOf(DataSource)}
 *       returns an {@code AssertDbConnection} that inspects database state directly via the
 *       DataSource — the DAO under test must not be both subject and instrument.</li>
 *   <li><b>Rule 3 — Read/write decoupling:</b> {@link #insertDirectly(DataSource, String, Map)}
 *       inserts fixture data via parameterised direct JDBC, so read-path tests don't depend
 *       on the DAO's own write method.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @BeforeEach
 * void setUp() {
 *     DataSource ds = TenantDaoTestSupport.freshDataSource();
 *     TenantDaoTestSupport.applyMigration(ds, "db/migration/auth/V1__admin_credentials.sql");
 *     AssertDbConnection assertDb = TenantDaoTestSupport.assertDbOf(ds);
 *     dao = new MyDao(ds);
 * }
 * }</pre>
 *
 * <h2>Design constraints (AC7)</h2>
 * <p>This class is {@code final} with a private constructor. All methods are {@code static}.
 * No inheritance-based extension is possible — DAO-specific tests must compose, not extend.
 * API growth is scoped via explicit AC in consuming stories (DEC-26).
 *
 * <h2>Test-scope only (AC8)</h2>
 * <p>Lives in {@code src/test/java/}. Must not be imported from {@code src/main/java/}.
 *
 * @see <a href="../../../../../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-26.md">DEC-26</a>
 * @see <a href="../../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E16S01.story.md">Story E16S01</a>
 */
public final class TenantDaoTestSupport {

    /** Allowed identifier characters: letters, digits, underscore. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private TenantDaoTestSupport() {
        // utility class — no instances
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns a fresh in-memory H2 {@link DataSource} with a unique URL per call.
     *
     * <p>Each invocation produces an independent database instance — no shared state
     * between calls. Suitable as a per-test {@link DataSource} in a {@code @BeforeEach}
     * setup without explicit table reset between tests.
     *
     * <p>{@code DB_CLOSE_DELAY=-1} keeps the in-memory DB alive as long as the JVM is
     * running (important for concurrent-thread tests that open multiple connections).
     *
     * @return a non-null, open-able DataSource backed by a uniquely named in-memory H2 DB
     */
    public static DataSource freshDataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:tenant-dao-test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * Executes the SQL at the given classpath location against the provided DataSource.
     *
     * <p>Satisfies DEC-26 Rule 1: the production Flyway migration file is the single
     * schema source of truth. Pass the migration path relative to the classpath root, e.g.:
     * {@code "db/migration/auth/V1__admin_credentials.sql"}.
     *
     * @param ds                non-null DataSource to apply the migration to
     * @param classpathResource classpath-relative path to the SQL script
     * @throws IllegalStateException if the resource is not found or SQL execution fails
     */
    public static void applyMigration(DataSource ds, String classpathResource) {
        ClassPathResource resource = new ClassPathResource(classpathResource);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Migration resource not found on classpath: " + classpathResource
                    + " — verify the path matches the production Flyway migration location");
        }
        try (Connection conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn, resource);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to apply migration " + classpathResource + " to test DataSource: " + e.getMessage(), e);
        }
    }

    /**
     * Returns an assertj-db {@link AssertDbConnection} wired to the given DataSource.
     *
     * <p>Satisfies DEC-26 Rule 2: use this to verify database state after a DAO write
     * instead of the DAO's own read methods. Example:
     *
     * <pre>{@code
     * Table table = TenantDaoTestSupport.assertDbOf(ds).table("admin_credentials").build();
     * assertThat(table).hasNumberOfRows(1);
     * }</pre>
     *
     * @param ds non-null DataSource to inspect
     * @return an AssertDbConnection ready for table-level assertions
     */
    public static AssertDbConnection assertDbOf(DataSource ds) {
        return AssertDbConnectionFactory.of(ds).create();
    }

    /**
     * Inserts a single row into the named table via parameterised direct JDBC.
     *
     * <p>Satisfies DEC-26 Rule 3: use this to set up fixture data for read-path tests
     * without invoking the DAO's own write methods.
     *
     * <p><b>Column name safety:</b> Column names are validated against a whitelist pattern
     * ({@code [A-Za-z_][A-Za-z0-9_]*}) and quoted as ANSI SQL identifiers. Values are
     * passed via {@code PreparedStatement} parameters — no string concatenation of values,
     * no SQL injection possible through values.
     *
     * @param ds    DataSource to insert into
     * @param table target table name (must satisfy identifier whitelist)
     * @param cols  column-name → value map (must be non-empty)
     * @throws IllegalArgumentException if {@code cols} is empty or a column name fails the whitelist
     * @throws IllegalStateException    if the JDBC insert fails (e.g. unknown column, constraint violation)
     */
    public static void insertDirectly(DataSource ds, String table, Map<String, Object> cols) {
        if (cols == null || cols.isEmpty()) {
            throw new IllegalArgumentException(
                    "insertDirectly requires at least one column — cols map must not be empty");
        }

        // Preserve insertion order for consistent SQL building
        Map<String, Object> ordered = new LinkedHashMap<>(cols);

        // Validate all column names against whitelist
        for (String col : ordered.keySet()) {
            if (!SAFE_IDENTIFIER.matcher(col).matches()) {
                throw new IllegalArgumentException(
                        "Column name '" + col + "' contains unsafe characters. "
                        + "Only letters, digits, and underscores are allowed in column names "
                        + "passed to insertDirectly.");
            }
        }

        // Build INSERT SQL with unquoted identifiers (H2 normalises to uppercase automatically)
        // and positional parameters for values (preventing SQL injection via values)
        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(table)
                .append(" (");
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
