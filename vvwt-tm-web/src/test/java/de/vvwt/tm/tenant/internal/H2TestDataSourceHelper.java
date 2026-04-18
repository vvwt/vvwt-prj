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
            // Check if flyway_schema_history exists first
            ResultSet tables = conn.getMetaData().getTables(null, null, "FLYWAY_SCHEMA_HISTORY", null);
            if (!tables.next()) {
                return 0;
            }
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT COUNT(*) FROM flyway_schema_history")) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Returns true if a table with the given name exists in the given DataSource.
     * Case-insensitive (H2 stores table names uppercase by default).
     */
    static boolean tableExists(DataSource ds, String tableName) throws SQLException {
        try (Connection conn = ds.getConnection()) {
            ResultSet rs = conn.getMetaData().getTables(
                    null, null, tableName.toUpperCase(), new String[]{"TABLE"});
            return rs.next();
        }
    }

    /**
     * Returns true if the given table does NOT exist in the DataSource.
     */
    static boolean tableAbsent(DataSource ds, String tableName) throws SQLException {
        return !tableExists(ds, tableName);
    }
}
