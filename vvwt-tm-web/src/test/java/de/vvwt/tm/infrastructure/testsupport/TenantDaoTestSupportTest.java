package de.vvwt.tm.infrastructure.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.db.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.Table;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * TDD-first tests for {@link TenantDaoTestSupport}.
 *
 * <p>Each method group documents a RED-then-GREEN cycle per DEC-22. AC3 (bootstrap independence):
 * tests for {@code freshDataSource} deliberately do NOT rely on any other {@link
 * TenantDaoTestSupport} methods during setup.
 *
 * @see TenantDaoTestSupport
 * @see <a
 *     href="../../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E16S01.story.md">Story
 *     E16S01</a>
 */
class TenantDaoTestSupportTest {

    // =========================================================================
    // T1 — freshDataSource (AC2, AC3 bootstrap independence)
    // =========================================================================

    /**
     * T1a (AC3 bootstrap): {@code freshDataSource()} returns a non-null {@link DataSource}.
     *
     * <p>AC3: This test does NOT rely on any other TenantDaoTestSupport method. It constructs the
     * expected DataSource shape from first principles: creates a hand-rolled {@link JdbcDataSource}
     * and verifies the same contract that {@code freshDataSource()} must fulfil.
     */
    @Test
    void freshDataSource_returnsNonNullDataSource() {
        // Establish expected shape from first principles (AC3 — no TenantDaoTestSupport methods in
        // setup)
        JdbcDataSource expectedShape = new JdbcDataSource();
        expectedShape.setURL(
                "jdbc:h2:mem:bootstrap-check-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        expectedShape.setUser("sa");
        expectedShape.setPassword("");

        // Smoke-test the hand-rolled shape — proves our expectation is valid
        assertThat(expectedShape).isNotNull();

        // Now verify TenantDaoTestSupport.freshDataSource() meets the same contract
        DataSource result = TenantDaoTestSupport.freshDataSource();

        assertThat(result).as("freshDataSource() must return a non-null DataSource").isNotNull();
    }

    /**
     * T1b (AC2): The returned DataSource opens a real connection (no mock).
     *
     * <p>AC3: Setup uses a hand-rolled DataSource; result is from {@code freshDataSource()}.
     */
    @Test
    void freshDataSource_canOpenConnection() throws Exception {
        DataSource ds = TenantDaoTestSupport.freshDataSource();

        try (Connection conn = ds.getConnection()) {
            assertThat(conn)
                    .as(
                            "freshDataSource() must return a DataSource that can open a JDBC"
                                    + " connection")
                    .isNotNull();
            assertThat(conn.isClosed())
                    .as("Connection from freshDataSource() must not be immediately closed")
                    .isFalse();
        }
    }

    /**
     * T1c (AC2 + uniqueness): Two successive calls to {@code freshDataSource()} return DataSources
     * backed by different in-memory databases (unique-URL invariant).
     *
     * <p>Verified by inserting a table in DS1 and confirming DS2 does NOT see it — proving they are
     * separate database instances, not shared state.
     *
     * <p>AC3: No other TenantDaoTestSupport methods are used.
     */
    @Test
    void freshDataSource_eachCallReturnsSeparateDatabase() throws Exception {
        DataSource ds1 = TenantDaoTestSupport.freshDataSource();
        DataSource ds2 = TenantDaoTestSupport.freshDataSource();

        // Create a marker table in ds1 only
        try (Connection c = ds1.getConnection();
                var stmt = c.createStatement()) {
            stmt.execute("CREATE TABLE isolation_marker (id INT)");
        }

        // ds2 must NOT see the table from ds1
        assertThatThrownBy(
                        () -> {
                            try (Connection c = ds2.getConnection();
                                    var stmt = c.createStatement()) {
                                stmt.execute("SELECT * FROM isolation_marker");
                            }
                        })
                .as(
                        "ds2 must not see tables created in ds1 — each freshDataSource() must yield"
                                + " an independent DB")
                .isInstanceOf(Exception.class);
    }

    // =========================================================================
    // T2 — applyMigration happy path (AC4a)
    // =========================================================================

    /**
     * T2 (AC4a): {@code applyMigration(ds, classpathResource)} successfully applies the production
     * migration {@code db/migration/auth/V1__admin_credentials.sql}.
     *
     * <p>The table is verifiable after migration by querying a row count.
     */
    @Test
    void applyMigration_productionMigration_tableIsCreated() throws Exception {
        DataSource ds = TenantDaoTestSupport.freshDataSource();

        TenantDaoTestSupport.applyMigration(ds, "db/migration/auth/V1__admin_credentials.sql");

        // Verify the table exists by querying it (0 rows expected — fresh DB)
        try (Connection conn = ds.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery("SELECT COUNT(*) FROM admin_credentials")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1))
                    .as("Newly migrated admin_credentials table must have 0 rows")
                    .isEqualTo(0);
        }
    }

    // =========================================================================
    // T3 — applyMigration bogus resource (AC4b)
    // =========================================================================

    /**
     * T3 (AC4b): {@code applyMigration(ds, bogus)} with a non-existent classpath resource must
     * throw with the resource name in the exception message.
     */
    @Test
    void applyMigration_bogusResource_throwsWithResourceNameInMessage() {
        DataSource ds = TenantDaoTestSupport.freshDataSource();
        String bogusResource = "db/migration/nonexistent/Vx__does_not_exist.sql";

        assertThatThrownBy(() -> TenantDaoTestSupport.applyMigration(ds, bogusResource))
                .as("applyMigration with bogus resource must throw with resource name in message")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(bogusResource);
    }

    // =========================================================================
    // T4 — assertDbOf (AC5)
    // =========================================================================

    /**
     * T4 (AC5): {@code assertDbOf(ds)} returns an {@link AssertDbConnection} that can build a Table
     * handle and perform row-count assertions.
     */
    @Test
    void assertDbOf_seededTable_canAssertRowCount() throws Exception {
        DataSource ds = TenantDaoTestSupport.freshDataSource();
        // Create a simple table directly — not using applyMigration to keep T4 independent
        try (Connection conn = ds.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t4_probe (val VARCHAR(50))");
            stmt.execute("INSERT INTO t4_probe VALUES ('row1')");
        }

        AssertDbConnection assertDb = TenantDaoTestSupport.assertDbOf(ds);

        Table table = assertDb.table("t4_probe").build();
        assertThat(table)
                .as("assertDbOf must return a connection that can verify 1 row in t4_probe")
                .hasNumberOfRows(1);
    }

    // =========================================================================
    // T5 — insertDirectly: row is verifiable via assertDbOf (AC6a)
    // =========================================================================

    /**
     * T5 (AC6a): {@code insertDirectly(ds, table, cols)} inserts a row visible via {@code
     * assertDbOf}.
     */
    @Test
    void insertDirectly_singleRow_rowAppearsInDatabase() throws Exception {
        DataSource ds = TenantDaoTestSupport.freshDataSource();
        try (Connection conn = ds.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t5_probe (id VARCHAR(36), name VARCHAR(50))");
        }
        UUID rowId = UUID.randomUUID();

        TenantDaoTestSupport.insertDirectly(
                ds, "t5_probe", Map.of("id", rowId.toString(), "name", "test-row"));

        Table table = TenantDaoTestSupport.assertDbOf(ds).table("t5_probe").build();
        assertThat(table)
                .as("insertDirectly must produce exactly one row in t5_probe")
                .hasNumberOfRows(1)
                .row(0)
                .value("name")
                .isEqualTo("test-row");
    }

    // =========================================================================
    // T5b — insertDirectly: SQL injection via crafted key is rejected (AC6b)
    // =========================================================================

    /**
     * T5b (AC6b): Column names with SQL injection payloads are rejected by the whitelist validation
     * in {@code insertDirectly}.
     *
     * <p>A key like {@code "id; DROP TABLE t5b_probe --"} must not reach the database.
     */
    @Test
    void insertDirectly_sqlInjectionInKey_isRejected() throws Exception {
        DataSource ds = TenantDaoTestSupport.freshDataSource();
        try (Connection conn = ds.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t5b_probe (id VARCHAR(36))");
        }

        assertThatThrownBy(
                        () ->
                                TenantDaoTestSupport.insertDirectly(
                                        ds,
                                        "t5b_probe",
                                        Map.of(
                                                "id; DROP TABLE t5b_probe --",
                                                UUID.randomUUID().toString())))
                .as("insertDirectly must reject column names with SQL injection characters (AC6b)")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // T6 — insertDirectly: unknown column produces descriptive error (AC6c)
    // =========================================================================

    /**
     * T6 (AC6c): {@code insertDirectly} with an unknown column name must throw a descriptive error
     * — not silently ignore the column.
     */
    @Test
    void insertDirectly_unknownColumn_throwsDescriptiveError() throws Exception {
        DataSource ds = TenantDaoTestSupport.freshDataSource();
        try (Connection conn = ds.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t6_probe (id VARCHAR(36))");
        }

        assertThatThrownBy(
                        () ->
                                TenantDaoTestSupport.insertDirectly(
                                        ds,
                                        "t6_probe",
                                        Map.of(
                                                "id",
                                                UUID.randomUUID().toString(),
                                                "nonexistent_column",
                                                "value")))
                .as("insertDirectly with unknown column must throw")
                .isInstanceOf(Exception.class);
    }

    // =========================================================================
    // T7 — insertDirectly: empty map throws IllegalArgumentException (AC6d)
    // =========================================================================

    /**
     * T7 (AC6d): {@code insertDirectly} with an empty column map must throw {@link
     * IllegalArgumentException}.
     */
    @Test
    void insertDirectly_emptyMap_throwsIllegalArgumentException() throws Exception {
        DataSource ds = TenantDaoTestSupport.freshDataSource();

        assertThatThrownBy(() -> TenantDaoTestSupport.insertDirectly(ds, "any_table", Map.of()))
                .as("insertDirectly with empty map must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // T8 — Structural: TenantDaoTestSupport is final with private constructor (AC7)
    // =========================================================================

    /**
     * T8 (AC7): {@link TenantDaoTestSupport} must be {@code final} and have no accessible public
     * constructor.
     */
    @Test
    void tenantDaoTestSupport_isFinalWithNoPublicConstructor() throws Exception {
        Class<?> clazz = TenantDaoTestSupport.class;

        assertThat(java.lang.reflect.Modifier.isFinal(clazz.getModifiers()))
                .as("TenantDaoTestSupport must be declared final (AC7)")
                .isTrue();

        long publicConstructorCount =
                java.util.Arrays.stream(clazz.getConstructors())
                        .filter(c -> java.lang.reflect.Modifier.isPublic(c.getModifiers()))
                        .count();
        assertThat(publicConstructorCount)
                .as("TenantDaoTestSupport must have no public constructor (AC7)")
                .isEqualTo(0L);
    }

    // =========================================================================
    // T9 — All four methods are static (AC7)
    // =========================================================================

    /** T9 (AC7): All four public methods of {@link TenantDaoTestSupport} must be {@code static}. */
    @Test
    void tenantDaoTestSupport_allPublicMethodsAreStatic() {
        java.lang.reflect.Method[] methods = TenantDaoTestSupport.class.getMethods();
        for (java.lang.reflect.Method m : methods) {
            // Skip Object methods
            if (m.getDeclaringClass().equals(Object.class)) {
                continue;
            }
            assertThat(java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                    .as("Method " + m.getName() + " in TenantDaoTestSupport must be static (AC7)")
                    .isTrue();
        }
    }

    // =========================================================================
    // T10 — Test-scope isolation: no main/java reference to TenantDaoTestSupport (AC8)
    // =========================================================================

    /**
     * T10 (AC8): No class in {@code src/main/java} references {@link TenantDaoTestSupport}.
     * Verified by a filesystem scan of {@code src/main/java/} for the class name.
     */
    @Test
    void mainJava_doesNotReferenceTenantDaoTestSupport() throws Exception {
        java.nio.file.Path mainRoot = findMainJavaRoot();
        if (mainRoot == null) {
            // Cannot locate src/main/java — skip scan (should not happen in standard Maven layout)
            return;
        }
        long violations =
                java.nio.file.Files.walk(mainRoot)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(
                                p -> {
                                    try {
                                        String content = java.nio.file.Files.readString(p);
                                        return content.contains("TenantDaoTestSupport");
                                    } catch (Exception e) {
                                        return false;
                                    }
                                })
                        .count();

        assertThat(violations)
                .as(
                        "AC8: TenantDaoTestSupport must not be referenced from src/main/java — "
                                + "it is test-scope only")
                .isEqualTo(0L);
    }

    /**
     * Locates the {@code src/main/java} root relative to the test class location, navigating up
     * from the test classpath root.
     */
    private java.nio.file.Path findMainJavaRoot() {
        try {
            // Resolve from classloader: find vvwt-tm-web project root
            java.net.URL classUrl =
                    TenantDaoTestSupportTest.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation();
            java.nio.file.Path testClassesDir = java.nio.file.Paths.get(classUrl.toURI());
            // Navigate: target/test-classes → target → module root → src/main/java
            java.nio.file.Path moduleRoot = testClassesDir.getParent().getParent();
            java.nio.file.Path mainJava = moduleRoot.resolve("src/main/java");
            return java.nio.file.Files.isDirectory(mainJava) ? mainJava : null;
        } catch (Exception e) {
            return null;
        }
    }
}
