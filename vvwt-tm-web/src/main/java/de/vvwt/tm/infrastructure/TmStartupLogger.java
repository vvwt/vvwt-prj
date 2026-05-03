package de.vvwt.tm.infrastructure;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Emits a structured startup log block after the application is fully initialised.
 *
 * <p>All entries are logged at INFO level with the prefix {@code [tm-bootstrap]} so they can be
 * grepped in Delivery and production logs (AC9).
 *
 * <p>Logged fields (AC9):
 *
 * <ul>
 *   <li>Resolved H2 database file path (from datasource URL)
 *   <li>H2 version (from JDBC connection metadata)
 *   <li>Flyway version and number of applied migrations
 *   <li>Spring Boot version actually loaded
 *   <li>Resolved {@code server.port}
 * </ul>
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E02S02.story.md">Story
 *     E02S02</a>
 */
@Component
public class TmStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(TmStartupLogger.class);

    private final DataSource dataSource;

    /**
     * Optional post-Reset (E45S05 / DEC-25): when {@code spring.flyway.enabled=false} Spring Boot
     * does not register a {@code Flyway} bean. {@link Optional} injection resolves to {@link
     * Optional#empty()} in that case — no wiring failure.
     */
    private final Optional<Flyway> flyway;

    private final String datasourceUrl;
    private final int serverPort;

    public TmStartupLogger(
            @Qualifier("dataSource") DataSource dataSource,
            Optional<Flyway> flyway,
            @Value("${spring.datasource.url}") String datasourceUrl,
            @Value("${server.port}") int serverPort) {
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.datasourceUrl = datasourceUrl;
        this.serverPort = serverPort;
    }

    /**
     * Fires after the application context is fully refreshed and all beans are ready. Logs the
     * startup diagnostics block (AC9).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logStartupDiagnostics() {
        log.info("[tm-bootstrap] ============================================================");
        log.info("[tm-bootstrap] Tournament Manager V1 — startup diagnostics");
        log.info("[tm-bootstrap] ============================================================");

        logH2Diagnostics();
        logFlywayDiagnostics();
        logFrameworkVersions();

        log.info("[tm-bootstrap] Listening on port: {}", serverPort);
        log.info("[tm-bootstrap] ============================================================");
    }

    /** Logs the resolved H2 database file path and H2 version from JDBC metadata. */
    private void logH2Diagnostics() {
        // Log the resolved datasource URL (includes actual TM_DB_PATH value after resolution)
        log.info("[tm-bootstrap] Datasource URL: {}", datasourceUrl);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            log.info("[tm-bootstrap] H2 version: {}", metadata.getDatabaseProductVersion());
            log.info(
                    "[tm-bootstrap] JDBC driver: {} {}",
                    metadata.getDriverName(),
                    metadata.getDriverVersion());
        } catch (SQLException sqlException) {
            // Non-fatal: application is already up, so datasource is reachable.
            // This should not happen in practice. Log a warning and continue.
            log.warn(
                    "[tm-bootstrap] Could not read H2 metadata from JDBC connection: {}",
                    sqlException.getMessage());
        }
    }

    /** Logs Flyway version and the number of applied migrations. */
    private void logFlywayDiagnostics() {
        String flywayVersion = Flyway.class.getPackage().getImplementationVersion();
        if (flywayVersion == null) {
            // May be null in test classpath setups where the manifest is not present
            flywayVersion = "(version not available in manifest)";
        }
        log.info("[tm-bootstrap] Flyway version: {}", flywayVersion);

        if (flyway.isEmpty()) {
            // Post-Reset (E45S05 / DEC-25): spring.flyway.enabled=false — flat DataSource has
            // no schema. PerTenantFlywayRunner is the sole schema-application path (DEC-20).
            log.info(
                    "[tm-bootstrap] Flyway migrations applied: 0 (flat DataSource disabled"
                        + " post-Reset; per-tenant Flyway runner applies per-module migrations)");
            return;
        }
        MigrationInfo[] appliedMigrations = flyway.get().info().applied();
        log.info("[tm-bootstrap] Flyway migrations applied: {}", appliedMigrations.length);
    }

    /** Logs the Spring Boot version actually loaded at runtime. */
    private void logFrameworkVersions() {
        String springBootVersion = SpringBootVersion.getVersion();
        if (springBootVersion == null) {
            springBootVersion = "(version not available in manifest)";
        }
        log.info("[tm-bootstrap] Spring Boot version: {}", springBootVersion);
    }
}
