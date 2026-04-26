package de.vvwt.slotopt.dispatcher.identity.testsupport;

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

/**
 * Poka-Yoke test-infrastructure utility for DAO integration tests in {@code
 * vvwt-slotopt-dispatcher}.
 *
 * <p>Analogously applies DEC-26 DAO test governance (three rules) to the dispatcher module. Pattern
 * mirrored from {@code TenantDaoTestSupport} in {@code vvwt-tm-web}.
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> {@link #applyMigration(DataSource,
 *       String)} delegates to Spring's {@code ScriptUtils}, ensuring the production Flyway
 *       migration bytes are what the test schema is built from.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> {@link #assertDbOf(DataSource)} returns
 *       an {@code AssertDbConnection} that inspects database state directly via the DataSource —
 *       the DAO under test must not be both subject and instrument.
 *   <li><b>Rule 3 — Read/write decoupling:</b> {@link #insertDirectly(DataSource, String, Map)}
 *       inserts fixture data via parameterised direct JDBC.
 * </ol>
 *
 * <p>This class is {@code final} with a private constructor. All methods are {@code static}. Scope:
 * dispatcher module test sources only.
 *
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="E37S05">Story E37S05</a>
 */
public final class DispatcherDaoTestSupport {

    /** Allowed identifier characters: letters, digits, underscore. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private DispatcherDaoTestSupport() {
        // utility class — no instances
    }

    /**
     * Returns a fresh in-memory H2 {@link DataSource} with a unique URL per call.
     *
     * <p>Each invocation produces an independent database instance. Suitable as a per-test {@link
     * DataSource} in a {@code @BeforeEach} setup.
     *
     * @return a non-null, open-able DataSource backed by a uniquely named in-memory H2 DB
     */
    public static DataSource freshDataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:dispatcher-dao-test-"
                        + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * Executes the SQL at the given classpath location against the provided DataSource.
     *
     * <p>Satisfies DEC-26 Rule 1: the production Flyway migration file is the single schema source
     * of truth.
     *
     * @param ds non-null DataSource
     * @param classpathResource classpath-relative path to the SQL script
     */
    public static void applyMigration(DataSource ds, String classpathResource) {
        ClassPathResource resource = new ClassPathResource(classpathResource);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Migration resource not found on classpath: "
                            + classpathResource
                            + " — verify the path matches the production Flyway migration"
                            + " location");
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
     * Returns an assertj-db {@link AssertDbConnection} wired to the given DataSource.
     *
     * <p>Satisfies DEC-26 Rule 2: use this to verify database state after a DAO write.
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
     * <p>Satisfies DEC-26 Rule 3: fixture data for read-path tests without invoking the DAO's own
     * write methods.
     *
     * @param ds DataSource to insert into
     * @param table target table name (must satisfy identifier whitelist)
     * @param cols column-name → value map (must be non-empty)
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
