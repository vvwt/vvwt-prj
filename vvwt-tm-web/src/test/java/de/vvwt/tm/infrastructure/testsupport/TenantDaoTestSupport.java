package de.vvwt.tm.infrastructure.testsupport;

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
 * Poka-Yoke test-infrastructure utility for DAO integration tests in {@code vvwt-tm-web}.
 *
 * <p>Enforces the three DEC-26 DAO test governance rules by making the correct path the easiest
 * path:
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
 * <h2>Usage</h2>
 *
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
 *
 * <p>This class is {@code final} with a private constructor. All methods are {@code static}. No
 * inheritance-based extension is possible — DAO-specific tests must compose, not extend. API growth
 * is scoped via explicit AC in consuming stories (DEC-26).
 *
 * <h2>Test-scope only (AC8)</h2>
 *
 * <p>Lives in {@code src/test/java/}. Must not be imported from {@code src/main/java/}.
 *
 * @see <a
 *     href="../../../../../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-26.md">DEC-26</a>
 * @see <a
 *     href="../../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E16S01.story.md">Story
 *     E16S01</a>
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
     * <p>Each invocation produces an independent database instance — no shared state between calls.
     * Suitable as a per-test {@link DataSource} in a {@code @BeforeEach} setup without explicit
     * table reset between tests.
     *
     * <p>{@code DB_CLOSE_DELAY=-1} keeps the in-memory DB alive as long as the JVM is running
     * (important for concurrent-thread tests that open multiple connections).
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
     * <p>Satisfies DEC-26 Rule 1: the production Flyway migration file is the single schema source
     * of truth. Pass the migration path relative to the classpath root, e.g.: {@code
     * "db/migration/auth/V1__admin_credentials.sql"}.
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
     * <p>Satisfies DEC-26 Rule 2: use this to verify database state after a DAO write instead of
     * the DAO's own read methods. Example:
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
     * <p>Satisfies DEC-26 Rule 3: use this to set up fixture data for read-path tests without
     * invoking the DAO's own write methods.
     *
     * <p><b>Column name safety:</b> Column names are validated against a whitelist pattern ({@code
     * [A-Za-z_][A-Za-z0-9_]*}) and quoted as ANSI SQL identifiers. Values are passed via {@code
     * PreparedStatement} parameters — no string concatenation of values, no SQL injection possible
     * through values.
     *
     * @param ds DataSource to insert into
     * @param table target table name (must satisfy identifier whitelist)
     * @param cols column-name → value map (must be non-empty)
     * @throws IllegalArgumentException if {@code cols} is empty or a column name fails the
     *     whitelist
     * @throws IllegalStateException if the JDBC insert fails (e.g. unknown column, constraint
     *     violation)
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
                        "Column name '"
                                + col
                                + "' contains unsafe characters. Only letters, digits, and"
                                + " underscores are allowed in column names passed to"
                                + " insertDirectly.");
            }
        }

        // Build INSERT SQL with unquoted identifiers (H2 normalises to uppercase automatically)
        // and positional parameters for values (preventing SQL injection via values)
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

    /**
     * Applies the full tournament-scope schema subset required by all E21 DAO stories (C-15
     * contract, AC-DAO-SCHEMA-HELPER — E21S02).
     *
     * <p>Loads the following migrations in version order into the provided DataSource:
     *
     * <ol>
     *   <li>{@code auth/V1__admin_credentials.sql} — auth base (FK dependency)
     *   <li>{@code V1__initial_schema.sql} — tenants + locations base schema
     *   <li>{@code V2__e03_core_schema.sql} — tournament + phase + team + round_snapshot
     *   <li>{@code V3__e03_match.sql} — match_entry
     *   <li>{@code V4__e03_set_result.sql} — set_result
     *   <li>{@code V5__e03_aggregates_and_audit.sql} — audit_log_entry
     *   <li>{@code V7__e05s04_tournament_fields.sql} — appointment, field_count, team_count
     *   <li>{@code V8__e06s03_devices.sql} — devices (tournament FK)
     *   <li>{@code V9__e06s06_audit_source.sql} — audit source
     *   <li>{@code V10__e07s01_device_model_extension.sql} — device model extension
     *   <li>{@code V11__e08s01_planned_start_time.sql} — planned_start_time column
     *   <li>{@code V12__e08s01_phase_breaks.sql} — phase_break table
     *   <li>{@code V14__e08s05_draft_config.sql} — draft_json column
     *   <li>{@code V16__e14s08_device_location_nullable.sql} — device location nullable
     * </ol>
     *
     * <p>Excluded: V6 (auth-only, retired at E15S07 cutover per DEC-25), V13 (activity_types —
     * E20S02 scope, not tournament-core), V15 (certificate template — E12S04 scope).
     *
     * <p>This helper is the entry point for all subsequent E21 DAO stories (S03, S04, S05) that
     * need to set up the tournament-root schema before testing their own aggregate tables.
     *
     * @param ds the DataSource to apply the migrations to (typically from {@link #freshDataSource()})
     * @throws IllegalStateException if any migration resource is not found or SQL fails
     * @see DEC-26 — DAO test governance (schema-from-migration rule)
     * @see DEC-22 — TDD Iron Law
     */
    public static void applyTournamentSchema(DataSource ds) {
        // C-15 ordered subset: auth/V1 + V1–V5 + V7–V12 + V14 + V16 (13 root + 1 auth = 14 total)
        // Applied in version order per DEC-26 Rule 1.
        String[] migrations = {
            "db/migration/auth/V1__admin_credentials.sql",
            "db/migration/V1__initial_schema.sql",
            "db/migration/V2__e03_core_schema.sql",
            "db/migration/V3__e03_match.sql",
            "db/migration/V4__e03_set_result.sql",
            "db/migration/V5__e03_aggregates_and_audit.sql",
            "db/migration/V7__e05s04_tournament_fields.sql",
            "db/migration/V8__e06s03_devices.sql",
            "db/migration/V9__e06s06_audit_source.sql",
            "db/migration/V10__e07s01_device_model_extension.sql",
            "db/migration/V11__e08s01_planned_start_time.sql",
            "db/migration/V12__e08s01_phase_breaks.sql",
            "db/migration/V14__e08s05_draft_config.sql",
            "db/migration/V16__e14s08_device_location_nullable.sql",
        };
        for (String migration : migrations) {
            applyMigration(ds, migration);
        }
    }
}
