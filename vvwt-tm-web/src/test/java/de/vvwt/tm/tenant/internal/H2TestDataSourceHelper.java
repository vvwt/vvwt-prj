package de.vvwt.tm.tenant.internal;

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Test-only helper for creating real H2 file-based DataSources and querying
 * Flyway schema history for IT assertions.
 *
 * <p>Lives in {@code src/test/java} only — MUST NOT be imported from production code.
 *
 * <p>Story: E14S04 (DEC-20, DEC-22).
 */
final class H2TestDataSourceHelper {

    private H2TestDataSourceHelper() {}

    /**
     * Creates a real H2 file-based DataSource using a temp directory provided by JUnit
     * {@code @TempDir}. Each call with a unique {@code tenantId} produces an isolated DB file.
     *
     * @param tempDir  JUnit-managed temp directory
     * @param tenantId unique identifier for this tenant (used as filename discriminator)
     * @return a new H2 {@link DataSource} pointing to {@code tempDir/{tenantId}/db}
     */
    static DataSource createTempFileDataSource(Path tempDir, UUID tenantId) {
        Path dbPath = tempDir.resolve(tenantId.toString()).resolve("db");
        dbPath.getParent().toFile().mkdirs();
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + dbPath.toAbsolutePath() + ";AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=TRUE");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * Returns the number of rows in {@code flyway_schema_history} for the given DataSource.
     * Returns 0 if the table does not exist (Flyway has not yet run).
     */
    static int countFlywayHistoryRows(DataSource ds) throws SQLException {
        try (Connection conn = ds.getConnection()) {
            if (!tableExistsViaSql(conn, "flyway_schema_history")) {
                return 0;
            }
            // H2 2.x: unquoted identifiers are folded to uppercase.
            // The table was created by Flyway with lowercase name — must quote to preserve case.
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT COUNT(*) FROM \"flyway_schema_history\"")) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Returns true if a table with the given name exists in the given DataSource.
     * Uses an H2 information_schema query for reliable case-insensitive detection.
     */
    static boolean tableExists(DataSource ds, String tableName) throws SQLException {
        try (Connection conn = ds.getConnection()) {
            return tableExistsViaSql(conn, tableName.toLowerCase());
        }
    }

    /**
     * Returns true if the given table does NOT exist in the DataSource.
     */
    static boolean tableAbsent(DataSource ds, String tableName) throws SQLException {
        return !tableExists(ds, tableName);
    }

    /**
     * Checks table existence via H2's INFORMATION_SCHEMA.TABLES query.
     * This is more reliable than {@link java.sql.DatabaseMetaData#getTables} with H2 file mode.
     */
    private static boolean tableExistsViaSql(Connection conn, String lowerTableName) throws SQLException {
        // H2 stores table names lowercase by default when created without quoting.
        // Using LOWER() comparison for case-insensitive but reliable detection.
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = '"
                     + lowerTableName.toLowerCase() + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
