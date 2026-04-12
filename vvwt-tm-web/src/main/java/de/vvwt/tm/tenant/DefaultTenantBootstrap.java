package de.vvwt.tm.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bootstrap component that resolves or generates the default-tenant UUID at application startup.
 *
 * <p>Runs as an {@link ApplicationRunner} with {@code @Order(1)} so it executes after Flyway
 * has applied all migrations (Flyway runs during context refresh, before ApplicationRunners)
 * and before the HTTP server begins accepting requests (Spring Boot marks ready only after all
 * ApplicationRunners complete).
 *
 * <h2>Startup flow (AC2)</h2>
 * <ol>
 *   <li>Query {@code SELECT id FROM tenants WHERE is_default = TRUE}.</li>
 *   <li>If exactly one row exists: log the resolved UUID (AC10) and populate the provider.</li>
 *   <li>If no row exists: generate a UUID via {@code UUID.randomUUID()} (AC11), insert the
 *       default-tenant row and a default-location row in a single transaction (AC2, DEC-5),
 *       then log the generated UUIDs (AC9).</li>
 *   <li>If more than one row exists: abort with a clear error (AC8 — schema invariant violated).</li>
 * </ol>
 *
 * <h2>Concurrent-start race (AC3)</h2>
 * <p>If two processes start simultaneously and both see zero rows, one insertion will fail with a
 * unique-constraint violation (the {@code idx_tenants_single_default} index from E02S03). The
 * losing process catches {@link DataIntegrityViolationException}, logs that another process beat
 * it, re-queries to resolve the winner's UUID, and continues — no second insertion is attempted.
 *
 * <h2>Security — UUID generation (AC11)</h2>
 * <p>{@code UUID.randomUUID()} is specified by the Java SE spec to use a cryptographically strong
 * PRNG ({@code SecureRandom}). No direct {@code new UUID(long, long)} construction with
 * non-random inputs is used anywhere in this class.
 *
 * @see DefaultTenantProvider
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E02S04.story.md">Story E02S04</a>
 */
@Component
@Order(1)
public class DefaultTenantBootstrap implements ApplicationRunner, DefaultTenantProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantBootstrap.class);

    /**
     * SQL to check for an existing default-tenant row.
     * Returns at most one UUID due to the partial unique index on {@code is_default = TRUE}.
     */
    private static final String SELECT_DEFAULT_TENANT =
            "SELECT id FROM tenants WHERE is_default = TRUE";

    /** Insert the default-tenant row. Tenant location count = 1 per DEC-5 (exactly one location). */
    private static final String INSERT_TENANT =
            "INSERT INTO tenants (id, display_name, tenant_location_count, is_default, created_at) "
            + "VALUES (?, 'Default (LAN)', 1, TRUE, CURRENT_TIMESTAMP)";

    /** Insert the default-location row, scoped to the default tenant. */
    private static final String INSERT_LOCATION =
            "INSERT INTO locations (id, tenant_id, display_name, created_at) "
            + "VALUES (?, ?, 'Default Location', CURRENT_TIMESTAMP)";

    /**
     * Holds the resolved default-tenant UUID after bootstrap completes.
     * {@code null} before bootstrap; callers that reach {@link #getDefaultTenantId()} before
     * bootstrap completes will receive {@link IllegalStateException} (AC4).
     */
    private final AtomicReference<UUID> resolvedTenantId = new AtomicReference<>();

    private final JdbcTemplate jdbcTemplate;

    public DefaultTenantBootstrap(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // -------------------------------------------------------------------------
    // ApplicationRunner
    // -------------------------------------------------------------------------

    /**
     * Entry point called by Spring Boot after the context is fully refreshed and Flyway has
     * run all migrations. Resolves or generates the default-tenant identity.
     *
     * @param args application arguments (not used)
     * @throws IllegalStateException if schema invariants are violated or if a DB error other
     *         than a concurrent-race constraint violation prevents insertion (AC7, AC8)
     */
    @Override
    public void run(ApplicationArguments args) {
        List<UUID> existing = queryDefaultTenantIds();

        if (existing.size() > 1) {
            // AC8: schema corruption — the partial unique index should make this impossible,
            // but we guard defensively.
            throw new IllegalStateException(
                    "Multiple default-tenant rows detected — schema invariant violated; "
                    + "investigate database integrity. Found " + existing.size() + " rows "
                    + "with is_default = TRUE in the tenants table.");
        }

        if (existing.size() == 1) {
            UUID existingId = existing.get(0);
            resolvedTenantId.set(existingId);
            // AC10: observability — subsequent start
            log.info("[tm-bootstrap] Resolved existing default tenant UUID: {}", existingId);
            log.info("[tm-bootstrap] Default tenant bootstrap complete — instance identity resolved");
            return;
        }

        // No row found — generate and insert.
        insertDefaultTenantWithLocation();
    }

    // -------------------------------------------------------------------------
    // DefaultTenantProvider
    // -------------------------------------------------------------------------

    /**
     * Returns the resolved default-tenant UUID.
     *
     * @throws IllegalStateException if called before bootstrap has completed (AC4)
     */
    @Override
    public UUID getDefaultTenantId() {
        UUID id = resolvedTenantId.get();
        if (id == null) {
            throw new IllegalStateException(
                    "DefaultTenantProvider.getDefaultTenantId() was called before bootstrap "
                    + "completed. The default-tenant UUID is not yet resolved. "
                    + "Ensure that all callers are wired as Spring beans that are initialized "
                    + "after DefaultTenantBootstrap has run (ApplicationRunner, @Order(1)).");
        }
        return id;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Queries all UUIDs of rows with {@code is_default = TRUE}.
     * Returns a list — the size is the discriminant for the bootstrap decision.
     */
    private List<UUID> queryDefaultTenantIds() {
        return jdbcTemplate.query(
                SELECT_DEFAULT_TENANT,
                (resultSet, rowNum) -> resultSet.getObject(1, UUID.class));
    }

    /**
     * Attempts to insert a new default-tenant row and its corresponding default-location row
     * within a single transaction (AC2, DEC-5: tenant-creation creates location atomically).
     *
     * <p>On a concurrent-race constraint violation (AC3): catches the exception, re-queries to
     * resolve the winning UUID, and logs the outcome. Does NOT attempt a second insertion.
     *
     * <p>On any other write failure (AC7): rethrows as {@link IllegalStateException} with a clear
     * actionable error message. The application will abort with non-zero exit status.
     */
    @Transactional
    public void insertDefaultTenantWithLocation() {
        // AC11: UUID.randomUUID() uses SecureRandom per the Java SE spec.
        // No direct UUID(long, long) construction with non-random inputs.
        UUID tenantId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        try {
            jdbcTemplate.update(INSERT_TENANT, tenantId);
            jdbcTemplate.update(INSERT_LOCATION, locationId, tenantId);

            resolvedTenantId.set(tenantId);

            // AC9: observability — first start
            log.info("[tm-bootstrap] Generated new default tenant UUID: {}", tenantId);
            log.info("[tm-bootstrap] Generated default location UUID: {}", locationId);
            log.info("[tm-bootstrap] Default tenant bootstrap complete — instance identity established");

        } catch (DataIntegrityViolationException raceException) {
            // AC3: concurrent-start race — another process won the insertion.
            // Do NOT attempt a second insertion. Re-query and resolve their UUID.
            log.info("[tm-bootstrap] Concurrent start detected: another process inserted the default "
                    + "tenant row before us. Re-querying to resolve their UUID. "
                    + "Constraint violation: {}", raceException.getMessage());

            List<UUID> winners = queryDefaultTenantIds();
            if (winners.isEmpty()) {
                // Should not happen: we just caught a constraint violation, so the winning row exists.
                throw new IllegalStateException(
                        "FATAL: Caught concurrent-insertion constraint violation but subsequent "
                        + "re-query found no default-tenant row. Database is in an inconsistent state. "
                        + "Root cause: " + raceException.getMessage(),
                        raceException);
            }
            UUID winnerId = winners.get(0);
            resolvedTenantId.set(winnerId);
            log.info("[tm-bootstrap] Resolved existing default tenant UUID (after concurrent race): {}", winnerId);
            log.info("[tm-bootstrap] Default tenant bootstrap complete — instance identity resolved");

        } catch (Exception unexpectedException) {
            // AC7: non-race DB failure — abort with a clear actionable error message.
            throw new IllegalStateException(
                    "FATAL: Default-tenant bootstrap failed: could not insert the default-tenant row. "
                    + "Check database connectivity, disk space, and schema integrity. "
                    + "Failing operation: INSERT INTO tenants / locations. "
                    + "Underlying cause: " + unexpectedException.getClass().getSimpleName()
                    + " — " + unexpectedException.getMessage(),
                    unexpectedException);
        }
    }
}
