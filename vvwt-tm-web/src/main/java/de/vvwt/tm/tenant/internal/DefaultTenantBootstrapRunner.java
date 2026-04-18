package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tenant.TenantRegistryPort.TenantRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bootstraps the default tenant at application first-start using the Wave-1 tenant infrastructure
 * ({@link TenantRegistryPort}, {@link TenantDirectoryHelper}, {@link PerTenantFlywayRunner}).
 *
 * <h2>Startup flow (AC2)</h2>
 * <ol>
 *   <li>Orphan scan: list {@code ${tm.data.dir}/tenants/} — for each UUID subdirectory found,
 *       check if a registry entry exists. If a directory exists but has NO registry entry
 *       (orphan from a prior crashed start), delete the orphan directory (AC-ORPHAN-RECOVERY).</li>
 *   <li>Existence check: {@link TenantRegistryPort#findAll()} filtered by
 *       {@code displayName == "Default (LAN)"}.
 *       <ul>
 *         <li>If found → log resolved UUID and return (no-op, AC3).</li>
 *         <li>If not found → proceed with creation.</li>
 *       </ul></li>
 *   <li>Generate UUID via {@code UUID.randomUUID()} (AC4, DEC-17).</li>
 *   <li>Create the tenant H2 directory via {@link TenantDirectoryHelper#createTenantDirectory}.</li>
 *   <li>Create an H2 DataSource pointing to the new directory.</li>
 *   <li>Run Flyway migrations via {@link PerTenantFlywayRunner#runWithDataSource} BEFORE registering
 *       (AC5: a failed migration leaves NO registry entry).</li>
 *   <li>Register the tenant via {@link TenantRegistryPort#register} (AC2).</li>
 *   <li>On {@link TenantFileRegistry.DuplicateTenantException}: concurrent race — another process
 *       won registration. Delete our newly-created directory (self-cleanup) and log (AC6).</li>
 * </ol>
 *
 * <h2>Parallel-phase coexistence (AC-PARALLEL-PHASE / DEC-21)</h2>
 * <p>During the parallel development phase (between this story's merge and E14S07 cutover),
 * the legacy {@code de.vvwt.tm.tenant.DefaultTenantBootstrap} (at {@code @Order(1)}) and this
 * runner (at {@code @Order(2)}) BOTH run at startup. They operate on different persistence models:
 * <ul>
 *   <li>Legacy: writes to the shared JPA DataSource ({@code tenants} table via JdbcTemplate).</li>
 *   <li>This runner: writes to {@code tenant-registry.json} + per-tenant H2 file.</li>
 * </ul>
 * The staging deploy is accepted as paused/broken until E14S07's atomic cutover resolves this.
 * Per DEC-21: NO {@code @ConditionalOnProperty}, NO {@code @Profile}, NO {@code @Conditional*}.
 *
 * <h2>No auth coupling (AC8)</h2>
 * <p>This class does NOT import or reference any {@code de.vvwt.tm.auth.*} type.
 * Admin credentials are E15's concern. This runner produces a tenant and registers it, nothing more.
 *
 * <h2>AC11 — Idempotency guard for UUID reconciliation (E14S12)</h2>
 * <p>Before generating a new UUID, this runner executes a
 * {@code SELECT id FROM tenants LIMIT 1} query on the shared JPA {@link JdbcTemplate}.
 * If the legacy {@code DefaultTenantBootstrap} ({@code @Order(1)}) has already written UUID-A
 * to the shared {@code TENANTS} JPA table, this runner picks up UUID-A and uses it as the
 * file-registry UUID — ensuring the HTTP interceptor resolves the same UUID as the JPA layer.
 * Without this guard, the runner would generate an independent UUID-B, causing FK violations
 * on every JPA insert (135 test failures observed in the initial E14S12 delivery attempt).
 *
 * <p>When the legacy {@code DefaultTenantBootstrap} is deleted at E14S07 cutover, the JPA
 * {@code TENANTS} table is empty on true fresh-start. The {@code orElseGet} fallback
 * generates a new UUID via {@code UUID.randomUUID()}, which is then the UUID-of-record
 * (per DEC-17 amendment). The guard is forward-compatible.
 *
 * <p>If the shared JPA query throws {@link DataAccessException} (connectivity failure),
 * the runner surfaces an {@link IllegalStateException} with message containing
 * "idempotency guard" and does NOT write the file registry with a guessed UUID.
 *
 * <h2>@Order(2) rationale</h2>
 * <p>The legacy {@code DefaultTenantBootstrap} runs at {@code @Order(1)}. This runner is placed
 * at {@code @Order(2)} to ensure the legacy infrastructure has run first during the parallel phase.
 * At E14S07 cutover, when the legacy class is deleted, this runner's order becomes the first
 * and can be adjusted if needed. The {@code @Order} value is documented here for E15's
 * {@code AdminCredentialsBootstrap} to reason about its relative ordering: E15 must run AFTER
 * this runner to ensure the default tenant's database is created and migrated.
 *
 * @see TenantRegistryPort
 * @see TenantDirectoryHelper
 * @see PerTenantFlywayRunner
 * @see <a href="../../../../../../../../docs/governance/stories/E14S05.story.md">Story E14S05</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E14S12.story.md">Story E14S12 (AC11 idempotency guard)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-17.md">DEC-17 (generated UUID)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20 (DB-per-Tenant)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21 (no feature flags)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22 (TDD)</a>
 */
@Order(2)
public class DefaultTenantBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantBootstrapRunner.class);

    /** Display name used to identify the default tenant in the registry. */
    static final String DEFAULT_TENANT_DISPLAY_NAME = "Default (LAN)";

    private final TenantRegistryPort registry;
    private final PerTenantFlywayRunner flywayRunner;
    private final Path dataDir;

    /**
     * Shared JPA {@link JdbcTemplate} — used by the AC11 idempotency guard to query
     * the {@code TENANTS} table before generating a new UUID.
     *
     * <p>Never {@code null}. Injected from the shared (legacy) Spring DataSource bean.
     */
    private final JdbcTemplate sharedJdbcTemplate;

    /**
     * Constructs a {@code DefaultTenantBootstrapRunner}.
     *
     * @param registry             the tenant registry for existence checks and registration; must not be {@code null}
     * @param flywayRunner         the per-tenant Flyway runner for applying schema migrations; must not be {@code null}
     * @param dataDir              the root data directory ({@code ${tm.data.dir}}); must not be {@code null}
     * @param sharedJdbcTemplate   the shared JPA JdbcTemplate for the AC11 idempotency guard; must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public DefaultTenantBootstrapRunner(TenantRegistryPort registry,
                                        PerTenantFlywayRunner flywayRunner,
                                        Path dataDir,
                                        JdbcTemplate sharedJdbcTemplate) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (flywayRunner == null) {
            throw new IllegalArgumentException("flywayRunner must not be null");
        }
        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir must not be null");
        }
        if (sharedJdbcTemplate == null) {
            throw new IllegalArgumentException("sharedJdbcTemplate must not be null");
        }
        this.registry = registry;
        this.flywayRunner = flywayRunner;
        this.dataDir = dataDir;
        this.sharedJdbcTemplate = sharedJdbcTemplate;
    }

    // -------------------------------------------------------------------------
    // ApplicationRunner
    // -------------------------------------------------------------------------

    /**
     * Entry point called by Spring Boot after the context is fully refreshed.
     *
     * <p>Implements the full bootstrap sequence described in the class Javadoc.
     *
     * @param args application arguments (not used)
     * @throws org.flywaydb.core.api.FlywayException if Flyway migration fails (AC5: no registry
     *         entry is created in this case — fail-fast behavior)
     * @throws IllegalStateException if orphan cleanup fails and cannot proceed
     */
    @Override
    public void run(ApplicationArguments args) {
        // Step 1: Orphan scan and cleanup (AC-ORPHAN-RECOVERY)
        cleanupOrphanTenantDirectories();

        // Step 2: Existence check — is the default tenant already registered?
        Optional<TenantRecord> existing = findDefaultTenantRecord();
        if (existing.isPresent()) {
            log.info("[tm-e14s05] Default tenant already registered: UUID={} (subsequent-start no-op, AC3)",
                    existing.get().tenantId());
            return;
        }

        // Step 3–7: Create new default tenant
        createDefaultTenant();
    }

    // -------------------------------------------------------------------------
    // Internal — creation flow
    // -------------------------------------------------------------------------

    /**
     * Full first-start creation flow: reconcile UUID via AC11 guard, create directory,
     * run Flyway, register.
     *
     * <p>Ordering invariant (AC5): Flyway runs BEFORE registry registration. A failed migration
     * leaves NO registry entry — the application startup fails fast with the Flyway exception.
     *
     * <p>AC11 — idempotency guard (E14S12): before generating a new UUID, queries the shared
     * JPA DataSource for an existing {@code TENANTS} row. If one exists (written by the legacy
     * {@code DefaultTenantBootstrap} at {@code @Order(1)}), that UUID is used — reconciling
     * the parallel-phase divergence between UUID-A (JPA) and UUID-B (file registry).
     *
     * <p>Concurrent-race handling (AC6): if {@link TenantFileRegistry.DuplicateTenantException}
     * is thrown on register(), this runner was the loser. It deletes its own newly-created
     * directory (self-cleanup) and logs the outcome. The winner's entry is already in the registry.
     */
    private void createDefaultTenant() {
        // AC11 / DEC-17: read-before-generate idempotency guard.
        // Query the shared JPA TENANTS table for an existing default tenant UUID.
        // If the legacy DefaultTenantBootstrap (@Order(1)) already wrote UUID-A, use it.
        // If the table is empty (true fresh-start or post-E14S07-cutover), generate UUID.
        UUID tenantId = resolveOrGenerateTenantUuid();

        // Step 4: Create the tenant H2 directory
        TenantDirectoryHelper.createTenantDirectory(dataDir, tenantId);

        // Step 5: Create DataSource pointing to the new directory
        DataSource dataSource = createDataSourceForTenant(tenantId);

        // Step 6: Run Flyway migrations BEFORE registering (AC5: failed migration → no registry entry)
        // FlywayException propagates unchanged — fail fast, no registry entry created.
        flywayRunner.runWithDataSource(tenantId, dataSource);

        // Step 7: Atomically register if no "Default (LAN)" entry exists yet (AC6).
        // Uses TenantFileRegistry.registerIfDisplayNameAbsent() for atomic check+write when
        // available (same package — package-private method). Falls back to non-atomic for
        // test doubles and alternative implementations.
        boolean registered = atomicRegisterIfAbsent(tenantId, DEFAULT_TENANT_DISPLAY_NAME);
        if (registered) {
            log.info("[tm-e14s05] Default tenant bootstrap complete — UUID={} registered (first-start, AC2)",
                    tenantId);
        } else {
            // Concurrent winner already registered — self-clean our directory (AC6)
            log.info("[tm-e14s05] Concurrent start detected: another process already registered "
                    + "'Default (LAN)'. Self-cleaning our directory UUID={} and deferring to winner. (AC6)",
                    tenantId);
            deleteTenantDirectory(tenantId);
        }
    }

    // -------------------------------------------------------------------------
    // Internal — AC11 idempotency guard
    // -------------------------------------------------------------------------

    /**
     * AC11 idempotency guard — reads the shared JPA {@code TENANTS} table for an existing UUID.
     *
     * <p>Query: {@code SELECT id FROM TENANTS LIMIT 1}.
     * <ul>
     *   <li>If a row exists → parse the UUID string and return it (UUID-A from legacy bootstrap).</li>
     *   <li>If the table is empty (null result) → generate {@code UUID.randomUUID()} (true fresh-start).</li>
     *   <li>If the query throws {@link DataAccessException} → surface typed
     *       {@link IllegalStateException} with message containing "idempotency guard";
     *       do NOT fall back to a random UUID (AC11: no guessed write).</li>
     * </ul>
     *
     * @return the UUID to use for this bootstrap run; never {@code null}
     * @throws IllegalStateException if the shared JPA DataSource cannot be queried (AC11)
     */
    private UUID resolveOrGenerateTenantUuid() {
        try {
            String existingId = sharedJdbcTemplate.queryForObject(
                    "SELECT id FROM TENANTS LIMIT 1", String.class);
            if (existingId != null) {
                UUID resolved = UUID.fromString(existingId);
                log.info("[tm-e14s12] AC11 idempotency guard: found existing JPA tenant UUID={} — "
                        + "using this UUID for file registry (UUID reconciliation, parallel-phase). ", resolved);
                return resolved;
            }
            // JPA table empty — true fresh-start or post-E14S07-cutover
            UUID generated = UUID.randomUUID();
            log.info("[tm-e14s12] AC11 idempotency guard: JPA TENANTS table empty — "
                    + "generating new UUID={} (DEC-17 amendment).", generated);
            return generated;
        } catch (DataAccessException e) {
            throw new IllegalStateException(
                    "[tm-e14s12] AC11 idempotency guard: cannot query shared JPA TENANTS table to reconcile UUID. "
                    + "Bootstrap will NOT proceed to avoid writing the file registry with a guessed UUID. "
                    + "Ensure the shared DataSource is healthy before starting. "
                    + "Underlying error: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal — orphan recovery
    // -------------------------------------------------------------------------

    /**
     * Scans the tenant directories and removes any that have no corresponding registry entry.
     *
     * <p>An orphan occurs when a prior start created the tenant directory but the application
     * crashed before the registry was written. On the next start, the orphan is cleaned up
     * so that the creation flow starts fresh (AC-ORPHAN-RECOVERY).
     *
     * <p>Mode: clean up + retry (idempotent — preferred per AC-ORPHAN-RECOVERY spec).
     * Recovery mode documented here: <strong>cleanup-and-retry</strong>.
     *
     * @throws IllegalStateException if the tenant-directories path cannot be listed
     */
    private void cleanupOrphanTenantDirectories() {
        Path tenantsRoot = dataDir.resolve("tenants");
        if (!Files.exists(tenantsRoot)) {
            // No tenant directories at all — nothing to scan
            return;
        }

        List<TenantRecord> registeredTenants = registry.findAll();

        try (var stream = Files.list(tenantsRoot)) {
            stream
                .filter(Files::isDirectory)
                .forEach(tenantDir -> {
                    String dirName = tenantDir.getFileName().toString();
                    UUID dirUuid;
                    try {
                        dirUuid = UUID.fromString(dirName);
                    } catch (IllegalArgumentException ignored) {
                        // Not a UUID-named directory — skip (not a tenant directory)
                        return;
                    }

                    boolean isRegistered = registeredTenants.stream()
                            .anyMatch(r -> r.tenantId().equals(dirUuid));

                    if (!isRegistered) {
                        log.info("[tm-e14s05] Orphan tenant directory detected: UUID={} (no registry entry). "
                                + "Cleaning up before retry (AC-ORPHAN-RECOVERY, mode: cleanup-and-retry). "
                                + "Path: {}", dirUuid, tenantDir);
                        deleteDirectoryTree(tenantDir);
                    }
                });
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot scan tenant directories for orphan detection at '"
                    + tenantsRoot.toAbsolutePath() + "'. "
                    + "Ensure the data directory is readable. "
                    + "Underlying error: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal — helpers
    // -------------------------------------------------------------------------

    /**
     * Atomically registers a tenant if no entry with the same {@code displayName} exists.
     *
     * <p>When the underlying registry is a {@link TenantFileRegistry} (same package), uses
     * {@link TenantFileRegistry#registerIfDisplayNameAbsent} for a synchronized check+write
     * that prevents duplicate displayName entries under concurrent first-start scenarios (AC6).
     *
     * <p>For other {@link TenantRegistryPort} implementations (test doubles, etc.), falls back
     * to a non-atomic double-check + register (may occasionally produce two entries in tests
     * that use concurrent access against non-synchronized mocks — acceptable for test doubles).
     *
     * @param tenantId    the tenant UUID to register
     * @param displayName the display name to check for uniqueness
     * @return {@code true} if registration succeeded; {@code false} if the displayName already existed
     */
    private boolean atomicRegisterIfAbsent(UUID tenantId, String displayName) {
        if (registry instanceof TenantFileRegistry fileRegistry) {
            // Fast path: atomic check+register in the same synchronized block (AC6)
            return fileRegistry.registerIfDisplayNameAbsent(tenantId, displayName);
        }
        // Fallback (non-atomic): used with test doubles or future alternative implementations
        Optional<TenantRecord> existing = findDefaultTenantRecord();
        if (existing.isPresent()) {
            return false;
        }
        registry.register(tenantId, displayName);
        return true;
    }

    /**
     * Searches the registry for a tenant record with the default-tenant display name.
     *
     * @return the first matching record, or empty if no default tenant is registered
     */
    private Optional<TenantRecord> findDefaultTenantRecord() {
        return registry.findAll().stream()
                .filter(r -> DEFAULT_TENANT_DISPLAY_NAME.equals(r.displayName()))
                .findFirst();
    }

    /**
     * Creates an H2 file-based DataSource for the given tenant UUID, using the standard
     * {@link TenantDirectoryHelper#tenantDbPath} layout.
     *
     * <p>The H2 JDBC URL omits the {@code .mv.db} suffix (H2 appends it automatically).
     *
     * @param tenantId the tenant UUID; must not be {@code null}
     * @return a new H2 JdbcDataSource for this tenant's file
     */
    private DataSource createDataSourceForTenant(UUID tenantId) {
        Path dbPath = TenantDirectoryHelper.tenantDbPath(dataDir, tenantId);
        String urlPath = dbPath.toAbsolutePath().toString();
        if (urlPath.endsWith(".mv.db")) {
            urlPath = urlPath.substring(0, urlPath.length() - ".mv.db".length());
        }
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + urlPath + ";AUTO_SERVER=FALSE");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * Attempts to delete the tenant directory for the given UUID (AC6: self-cleanup after
     * losing a concurrent registration race, or cleanup after orphan detection).
     *
     * <p>Errors during cleanup are logged as warnings but do NOT propagate — the primary concern
     * is that the winner's entry is in the registry and startup can continue.
     *
     * @param tenantId the UUID whose directory should be deleted
     */
    private void deleteTenantDirectory(UUID tenantId) {
        Path tenantDir = dataDir.resolve("tenants").resolve(tenantId.toString());
        deleteDirectoryTree(tenantDir);
    }

    /**
     * Recursively deletes a directory tree. Errors are logged as warnings and suppressed
     * (best-effort cleanup — never blocks startup).
     *
     * @param root the root directory to delete recursively
     */
    private void deleteDirectoryTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(f -> {
                      if (!f.delete()) {
                          log.warn("[tm-e14s05] Could not delete path during cleanup: {}", f.getAbsolutePath());
                      }
                  });
        } catch (IOException e) {
            log.warn("[tm-e14s05] Error during directory cleanup at '{}': {}", root, e.getMessage());
        }
    }
}
