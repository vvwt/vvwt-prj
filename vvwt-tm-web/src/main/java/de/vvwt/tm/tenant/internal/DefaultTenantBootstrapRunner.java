package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tenant.TenantRegistryPort.TenantRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bootstraps the default tenant at application first-start using the Wave-1 tenant infrastructure
 * ({@link TenantRegistryPort}, {@link TenantDirectoryHelper}, {@link PerTenantFlywayRunner}).
 *
 * <h2>Startup flow (AC2)</h2>
 *
 * <ol>
 *   <li>Orphan scan: list {@code ${tm.data.dir}/tenants/} — for each UUID subdirectory found, check
 *       if a registry entry exists. If a directory exists but has NO registry entry (orphan from a
 *       prior crashed start), delete the orphan directory (AC-ORPHAN-RECOVERY).
 *   <li>Existence check: {@link TenantRegistryPort#findAll()} filtered by {@code displayName ==
 *       "Default (LAN)"}.
 *       <ul>
 *         <li>If found → ensure main-DB row exists (idempotent upsert) and return (AC3).
 *         <li>If not found → proceed with creation.
 *       </ul>
 *   <li>Generate UUID via {@code UUID.randomUUID()} (AC4, DEC-17).
 *   <li>Create the tenant H2 directory via {@link TenantDirectoryHelper#createTenantDirectory}.
 *   <li>Create an H2 DataSource pointing to the new directory.
 *   <li>Run Flyway migrations via {@link PerTenantFlywayRunner#runWithDataSource} BEFORE
 *       registering (AC5: a failed migration leaves NO registry entry).
 *   <li>Insert the tenant row into the main application {@code tenants} table via {@link
 *       JdbcTemplate} (idempotent — skipped if row already present).
 *   <li>Register the tenant via {@link TenantRegistryPort#register} (AC2).
 *   <li>On {@link TenantFileRegistry.DuplicateTenantException}: concurrent race — another process
 *       won registration. Delete our newly-created directory (self-cleanup) and log (AC6).
 * </ol>
 *
 * <h2>Main-DB tenant + location rows (E14S07 cutover)</h2>
 *
 * <p>The main application database (flat DataSource, pre-E14S11) contains a {@code tenants} table
 * and a {@code locations} table (created by V1 Flyway migration). Other entities ({@code devices},
 * {@code tournaments}) reference these via foreign keys. This runner inserts both the
 * default-tenant row and a default-location row (DEC-5: every tenant must have at least one
 * location) so that repository operations can reference valid tenant and location UUIDs. Both
 * inserts are idempotent. This responsibility previously belonged to the legacy {@code
 * DefaultTenantBootstrap}; it is migrated here as part of the E14S07 atomic cutover.
 *
 * <h2>No auth coupling (AC8)</h2>
 *
 * <p>This class does NOT import or reference any {@code de.vvwt.tm.auth.*} type. Admin credentials
 * are E15's concern. This runner produces a tenant and registers it, nothing more.
 *
 * <h2>AC11 — Idempotency guard for UUID reconciliation (E14S12, forward-compatible)</h2>
 *
 * <p>Before generating a new UUID, this runner executes a {@code SELECT id FROM TENANTS LIMIT 1}
 * query on the shared JPA {@link JdbcTemplate}. Post-E14S07 cutover, the legacy {@code
 * DefaultTenantBootstrap} is deleted and the JPA {@code TENANTS} table is empty on true
 * fresh-start. The guard generates a new UUID via {@code UUID.randomUUID()} in that case (per
 * DEC-17 amendment). The guard is forward-compatible with future multi-tenant deployments where an
 * existing row may be present.
 *
 * <p>If the shared JPA query throws {@link DataAccessException} (connectivity failure), the runner
 * surfaces an {@link IllegalStateException} with message containing "idempotency guard" and does
 * NOT write the file registry with a guessed UUID.
 *
 * <h2>@Order(1) (E14S07 atomic cutover)</h2>
 *
 * <p>After the E14S07 atomic cutover, this is the sole {@code ApplicationRunner} responsible for
 * tenant bootstrap. It runs at {@code @Order(1)} — first among ApplicationRunners. E15's {@code
 * AdminCredentialsBootstrap} must run AFTER this runner to ensure the default tenant's database is
 * created and migrated before auth setup.
 *
 * @see TenantRegistryPort
 * @see TenantDirectoryHelper
 * @see PerTenantFlywayRunner
 * @see <a href="../../../../../../../../docs/governance/stories/E14S05.story.md">Story E14S05</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E14S07.story.md">Story E14S07
 *     (atomic cutover)</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E14S12.story.md">Story E14S12 (AC11
 *     idempotency guard)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-17.md">DEC-17 (generated
 *     UUID)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20
 *     (DB-per-Tenant)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21 (no feature
 *     flags)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22 (TDD)</a>
 */
@Order(1)
public class DefaultTenantBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantBootstrapRunner.class);

    /** Display name used to identify the default tenant in the registry. */
    static final String DEFAULT_TENANT_DISPLAY_NAME = "Default (LAN)";

    /** SQL to insert the default-tenant row into the main application {@code tenants} table. */
    private static final String INSERT_TENANT_SQL =
            "INSERT INTO tenants (id, display_name, tenant_location_count, is_default, created_at) "
                    + "VALUES (?, 'Default (LAN)', 1, TRUE, CURRENT_TIMESTAMP)";

    /** SQL to check whether a tenant row already exists by primary key. */
    private static final String SELECT_TENANT_BY_ID_SQL =
            "SELECT COUNT(*) FROM tenants WHERE id = ?";

    /** SQL to insert the default-location row for the default tenant. */
    private static final String INSERT_LOCATION_SQL =
            "INSERT INTO locations (id, tenant_id, display_name, created_at) "
                    + "VALUES (?, ?, 'Default Location', CURRENT_TIMESTAMP)";

    /** SQL to check whether a location row already exists for the given tenant. */
    private static final String SELECT_LOCATION_COUNT_SQL =
            "SELECT COUNT(*) FROM locations WHERE tenant_id = ?";

    private final TenantRegistryPort registry;
    private final PerTenantFlywayRunner flywayRunner;
    private final TenantDataSourceResolver tenantDataSourceResolver;
    private final Path dataDir;

    /**
     * Shared JPA {@link JdbcTemplate} — used by the AC11 idempotency guard to query the {@code
     * TENANTS} table before generating a new UUID, and for main-DB tenant row upsert after E14S07
     * atomic cutover.
     *
     * <p>Never {@code null}. Injected from the shared (legacy) Spring DataSource bean.
     */
    private final JdbcTemplate sharedJdbcTemplate;

    /**
     * {@link TransactionTemplate} for wrapping the main-DB tenant row INSERT in an explicit
     * transaction. Using {@link TransactionTemplate} rather than {@code @Transactional} avoids
     * Spring AOP self-invocation proxy bypass that would make the annotation ineffective when
     * called from within the same bean.
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * Constructs a {@code DefaultTenantBootstrapRunner}.
     *
     * @param registry the tenant registry for existence checks and registration; must not be {@code
     *     null}
     * @param flywayRunner the per-tenant Flyway runner for applying schema migrations; must not be
     *     {@code null}
     * @param dataDir the root data directory ({@code ${tm.data.dir}}); must not be {@code null}
     * @param sharedJdbcTemplate the shared JPA JdbcTemplate for the AC11 idempotency guard and
     *     main-DB upsert; must not be {@code null}
     * @param transactionTemplate the TransactionTemplate for transactional main-DB inserts; must
     *     not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public DefaultTenantBootstrapRunner(
            TenantRegistryPort registry,
            PerTenantFlywayRunner flywayRunner,
            TenantDataSourceResolver tenantDataSourceResolver,
            Path dataDir,
            JdbcTemplate sharedJdbcTemplate,
            TransactionTemplate transactionTemplate) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (flywayRunner == null) {
            throw new IllegalArgumentException("flywayRunner must not be null");
        }
        if (tenantDataSourceResolver == null) {
            throw new IllegalArgumentException("tenantDataSourceResolver must not be null");
        }
        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir must not be null");
        }
        if (sharedJdbcTemplate == null) {
            throw new IllegalArgumentException("sharedJdbcTemplate must not be null");
        }
        if (transactionTemplate == null) {
            throw new IllegalArgumentException("transactionTemplate must not be null");
        }
        this.registry = registry;
        this.flywayRunner = flywayRunner;
        this.tenantDataSourceResolver = tenantDataSourceResolver;
        this.dataDir = dataDir;
        this.sharedJdbcTemplate = sharedJdbcTemplate;
        this.transactionTemplate = transactionTemplate;
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
     *     entry is created in this case — fail-fast behavior)
     * @throws IllegalStateException if orphan cleanup fails and cannot proceed
     */
    @Override
    public void run(ApplicationArguments args) {
        // Step 1: Orphan scan and cleanup (AC-ORPHAN-RECOVERY)
        cleanupOrphanTenantDirectories();

        // Step 2: Existence check — is the default tenant already registered?
        Optional<TenantRecord> existing = findDefaultTenantRecord();
        if (existing.isPresent()) {
            UUID existingId = existing.get().tenantId();
            log.info(
                    "[tm-e14s05] Default tenant already registered: UUID={} (subsequent-start,"
                            + " AC3)",
                    existingId);
            // Idempotent main-DB upsert: ensures the tenants row exists even if the main DB
            // is a fresh in-memory H2 (e.g. integration tests) while the file registry persists.
            upsertMainDbTenantRow(existingId);
            // E14S11 — Idempotent per-tenant Flyway run on subsequent starts.
            // Applies any migrations that were not present when the DB was first created (e.g.
            // wave-1 migration bootstrap: per-tenant DB was created with empty locations, now
            // re-runs with classpath:db/migration fallback). Flyway skips already-applied
            // migrations.
            flywayRunner.run(existingId);
            // E14S11 — Also upsert tenant/location rows into the per-tenant DB.
            // In wave-1 the per-tenant DB has the same schema as the flat DB (including tenants
            // and locations tables). FK constraints on tournament, device etc. require the tenant
            // row to exist in the per-tenant DB too (not only in the flat DB).
            // Use the resolver (not createDataSourceForTenant) so that test overrides that
            // provide an in-memory TenantDataSourceResolver are honoured here too.
            DataSource perTenantDs = tenantDataSourceResolver.resolve(existingId);
            upsertDbTenantRow(existingId, new JdbcTemplate(perTenantDs));
            return;
        }

        // Step 3–7: Create new default tenant
        createDefaultTenant();
    }

    // -------------------------------------------------------------------------
    // Internal — creation flow
    // -------------------------------------------------------------------------

    /**
     * Full first-start creation flow: reconcile UUID via AC11 guard, create directory, run Flyway,
     * insert main-DB row, register.
     *
     * <p>Ordering invariant (AC5): Flyway runs BEFORE registry registration. A failed migration
     * leaves NO registry entry — the application startup fails fast with the Flyway exception.
     *
     * <p>AC11 — idempotency guard (E14S12, forward-compatible): before generating a new UUID,
     * queries the shared JPA DataSource. Post-E14S07-cutover, the JPA table is empty on fresh-start
     * so a new UUID is generated. The guard is forward-compatible.
     *
     * <p>Concurrent-race handling (AC6): if {@link TenantFileRegistry.DuplicateTenantException} is
     * thrown on register(), this runner was the loser. It deletes its own newly-created directory
     * (self-cleanup) and logs the outcome. The winner's entry is already in the registry.
     */
    private void createDefaultTenant() {
        // AC11 / DEC-17: read-before-generate idempotency guard.
        // Post-E14S07-cutover: JPA table is empty on fresh-start → generates new UUID.
        UUID tenantId = resolveOrGenerateTenantUuid();

        // Step 4: Create the tenant H2 directory
        TenantDirectoryHelper.createTenantDirectory(dataDir, tenantId);

        // Step 5: Create DataSource pointing to the new directory (production file-based path).
        DataSource fileDataSource = createDataSourceForTenant(tenantId);

        // Step 6: Run Flyway migrations BEFORE registering (AC5: failed migration → no registry
        // entry). FlywayException propagates unchanged — fail fast, no registry entry created.
        flywayRunner.runWithDataSource(tenantId, fileDataSource);

        // Step 6b: Insert the tenant row into the main application DB (idempotent).
        // This populates the shared `tenants` table that other entities (devices, tournaments,
        // etc.)
        // reference via FK. Runs after per-tenant Flyway but before file-registry registration.
        upsertMainDbTenantRow(tenantId);

        // Step 6c (E14S11 wave-1): Also upsert tenant/location rows into the per-tenant DB via
        // the FILE-based DataSource. The resolver is NOT used here because the tenant has not been
        // registered yet — TenantFileRegistryDataSourceResolver would throw UnknownTenantException.
        // The resolver-based idempotent upsert is deferred to step 7b (after registration).
        upsertDbTenantRow(tenantId, new JdbcTemplate(fileDataSource));

        // Step 7: Atomically register if no "Default (LAN)" entry exists yet (AC6).
        // Uses TenantFileRegistry.registerIfDisplayNameAbsent() for atomic check+write when
        // available (same package — package-private method). Falls back to non-atomic for
        // test doubles and alternative implementations.
        boolean registered = atomicRegisterIfAbsent(tenantId, DEFAULT_TENANT_DISPLAY_NAME);
        if (registered) {
            log.info(
                    "[tm-e14s05] Default tenant bootstrap complete — UUID={} registered"
                            + " (first-start, AC2)",
                    tenantId);
            // Step 7b (E14S11 wave-1): Now that the tenant is registered, apply Flyway and upsert
            // to the resolver's DataSource too. In production the resolver returns the SAME
            // file-based DataSource (idempotent no-ops). In tests
            // (InMemoryTenantDataSourceResolver)
            // the resolver returns a fresh in-memory H2 that needs schema + tenant rows for
            // subsequent integration-test queries.
            DataSource resolverDs = tenantDataSourceResolver.resolve(tenantId);
            flywayRunner.runWithDataSource(tenantId, resolverDs);
            upsertDbTenantRow(tenantId, new JdbcTemplate(resolverDs));
        } else {
            // Concurrent winner already registered — self-clean our directory (AC6)
            log.info(
                    "[tm-e14s05] Concurrent start detected: another process already registered"
                        + " 'Default (LAN)'. Self-cleaning our directory UUID={} and deferring to"
                        + " winner. (AC6)",
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
     * <p>Query: {@code SELECT id FROM TENANTS LIMIT 1} via {@link JdbcTemplate#queryForList}.
     *
     * <ul>
     *   <li>If a row exists → parse the UUID string and return it.
     *   <li>If the table is empty (empty list) → generate {@code UUID.randomUUID()} (true
     *       fresh-start, post-E14S07-cutover). Uses {@code queryForList} rather than {@code
     *       queryForObject} because {@code queryForObject} throws {@link
     *       org.springframework.dao.EmptyResultDataAccessException} when 0 rows are returned, which
     *       is not an error condition here.
     *   <li>If the query throws {@link DataAccessException} → surface typed {@link
     *       IllegalStateException} with message containing "idempotency guard"; do NOT fall back to
     *       a random UUID (AC11: no guessed write).
     * </ul>
     *
     * @return the UUID to use for this bootstrap run; never {@code null}
     * @throws IllegalStateException if the shared JPA DataSource cannot be queried (AC11)
     */
    private UUID resolveOrGenerateTenantUuid() {
        try {
            List<String> results =
                    sharedJdbcTemplate.queryForList("SELECT id FROM TENANTS LIMIT 1", String.class);
            if (!results.isEmpty()) {
                UUID resolved = UUID.fromString(results.get(0));
                log.info(
                        "[tm-e14s12] AC11 idempotency guard: found existing JPA tenant UUID={} — "
                                + "using this UUID for file registry (UUID reconciliation). ",
                        resolved);
                return resolved;
            }
            // JPA table empty — true fresh-start or post-E14S07-cutover
            UUID generated = UUID.randomUUID();
            log.info(
                    "[tm-e14s12] AC11 idempotency guard: JPA TENANTS table empty — "
                            + "generating new UUID={} (DEC-17 amendment).",
                    generated);
            return generated;
        } catch (BadSqlGrammarException noTable) {
            // Post-Reset (E45S05 / DEC-25): the flat DataSource has no TENANTS table.
            // Per-tenant schema is now in per-tenant H2 files only (DEC-20 end-state).
            // Treat "table not found" identically to an empty table — generate new UUID.
            UUID generated = UUID.randomUUID();
            log.info(
                    "[tm-e14s12] AC11 idempotency guard: TENANTS table absent from flat DataSource"
                            + " (post-Reset DEC-25 end-state) — generating new UUID={} (DEC-17"
                            + " amendment).",
                    generated);
            return generated;
        } catch (DataAccessException e) {
            throw new IllegalStateException(
                    "[tm-e14s12] AC11 idempotency guard: cannot query shared JPA TENANTS table to"
                        + " reconcile UUID. Bootstrap will NOT proceed to avoid writing the file"
                        + " registry with a guessed UUID. Ensure the shared DataSource is healthy"
                        + " before starting. Underlying error: "
                            + e.getMessage(),
                    e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal — orphan recovery
    // -------------------------------------------------------------------------

    /**
     * Scans the tenant directories and removes any that have no corresponding registry entry.
     *
     * <p>An orphan occurs when a prior start created the tenant directory but the application
     * crashed before the registry was written. On the next start, the orphan is cleaned up so that
     * the creation flow starts fresh (AC-ORPHAN-RECOVERY).
     *
     * <p>Mode: clean up + retry (idempotent — preferred per AC-ORPHAN-RECOVERY spec). Recovery mode
     * documented here: <strong>cleanup-and-retry</strong>.
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
            stream.filter(Files::isDirectory)
                    .forEach(
                            tenantDir -> {
                                String dirName = tenantDir.getFileName().toString();
                                UUID dirUuid;
                                try {
                                    dirUuid = UUID.fromString(dirName);
                                } catch (IllegalArgumentException ignored) {
                                    // Not a UUID-named directory — skip (not a tenant directory)
                                    return;
                                }

                                boolean isRegistered =
                                        registeredTenants.stream()
                                                .anyMatch(r -> r.tenantId().equals(dirUuid));

                                if (!isRegistered) {
                                    log.info(
                                            "[tm-e14s05] Orphan tenant directory detected: UUID={}"
                                                + " (no registry entry). Cleaning up before retry"
                                                + " (AC-ORPHAN-RECOVERY, mode: cleanup-and-retry)."
                                                + " Path: {}",
                                            dirUuid,
                                            tenantDir);
                                    deleteDirectoryTree(tenantDir);
                                }
                            });
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot scan tenant directories for orphan detection at '"
                            + tenantsRoot.toAbsolutePath()
                            + "'. "
                            + "Ensure the data directory is readable. "
                            + "Underlying error: "
                            + e.getMessage(),
                    e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal — helpers
    // -------------------------------------------------------------------------

    /**
     * Inserts the default-tenant row and a default-location row into the main application tables if
     * they do not already exist (idempotent upsert).
     *
     * <p>This is required because the main application database (flat DataSource, pre-E14S11)
     * contains a {@code tenants} table with FK constraints used by {@code devices}, {@code
     * tournaments}, and other entities. Without this row, repository inserts fail with
     * referential-integrity violations. A corresponding {@code locations} row is also created to
     * satisfy DEC-5's invariant (every tenant must have at least one location).
     *
     * <p>The tenant and location inserts run within a single {@link TransactionTemplate} rather
     * than {@code @Transactional} to avoid Spring AOP self-invocation proxy bypass.
     *
     * <p>On {@link DataIntegrityViolationException} (duplicate UUID race): swallowed silently — the
     * rows were inserted by a concurrent process and the constraint violation confirms they exist.
     *
     * @param tenantId the tenant UUID to insert; must not be {@code null}
     */
    private void upsertMainDbTenantRow(UUID tenantId) {
        try {
            Integer count =
                    sharedJdbcTemplate.queryForObject(
                            SELECT_TENANT_BY_ID_SQL, Integer.class, tenantId);
            if (count != null && count > 0) {
                // Tenant row already present — idempotent; check location too
                log.debug("[tm-e14s07] Main-DB tenant row already present for UUID={}", tenantId);
                upsertDefaultLocationRow(tenantId);
                return;
            }
            try {
                final UUID locationId = UUID.randomUUID();
                transactionTemplate.executeWithoutResult(
                        status -> {
                            sharedJdbcTemplate.update(INSERT_TENANT_SQL, tenantId);
                            sharedJdbcTemplate.update(INSERT_LOCATION_SQL, locationId, tenantId);
                        });
                log.info(
                        "[tm-e14s07] Main-DB tenant row inserted for UUID={}, location UUID={}",
                        tenantId,
                        locationId);
            } catch (DataIntegrityViolationException race) {
                // Concurrent insert won — rows are present, nothing to do
                log.info(
                        "[tm-e14s07] Main-DB tenant row insert race (concurrent process won) for"
                                + " UUID={}: {}",
                        tenantId,
                        race.getMessage());
            }
        } catch (BadSqlGrammarException noTable) {
            // Post-Reset (E45S05 / DEC-25): flat DataSource has no schema (no tenants table).
            // Per-tenant schema lives in per-tenant H2 files only (DEC-20 end-state).
            // upsertDbTenantRow() handles the per-tenant DB row insertion separately.
            log.debug(
                    "[tm-e14s07] Main-DB tenants table absent (post-Reset DEC-25) — skipping"
                            + " upsert for UUID={}",
                    tenantId);
        }
    }

    /**
     * Inserts a default-location row if none exists for the given tenant (idempotent).
     *
     * <p>Called when a tenant row already exists but may be missing its location (e.g. on re-start
     * after a partial-write crash, or when migrating from a state where only the tenant row was
     * present).
     *
     * @param tenantId the tenant UUID; must not be {@code null}
     */
    private void upsertDefaultLocationRow(UUID tenantId) {
        try {
            Integer locationCount =
                    sharedJdbcTemplate.queryForObject(
                            SELECT_LOCATION_COUNT_SQL, Integer.class, tenantId);
            if (locationCount != null && locationCount > 0) {
                log.debug(
                        "[tm-e14s07] Default location already present for tenant UUID={}",
                        tenantId);
                return;
            }
            try {
                UUID locationId = UUID.randomUUID();
                transactionTemplate.executeWithoutResult(
                        status ->
                                sharedJdbcTemplate.update(
                                        INSERT_LOCATION_SQL, locationId, tenantId));
                log.info(
                        "[tm-e14s07] Default location row inserted for tenant UUID={}, location"
                                + " UUID={}",
                        tenantId,
                        locationId);
            } catch (DataIntegrityViolationException race) {
                log.info(
                        "[tm-e14s07] Default location row insert race for tenant UUID={}: {}",
                        tenantId,
                        race.getMessage());
            }
        } catch (BadSqlGrammarException noTable) {
            // Post-Reset (E45S05 / DEC-25): flat DataSource has no schema (no locations table).
            // Per-tenant schema lives in per-tenant H2 files only (DEC-20 end-state).
            log.debug(
                    "[tm-e14s07] Main-DB locations table absent (post-Reset DEC-25) — skipping"
                            + " location upsert for UUID={}",
                    tenantId);
        }
    }

    /**
     * Upserts the default-tenant and default-location rows into the given database.
     *
     * <h2>E14S11 Wave-1 purpose</h2>
     *
     * <p>In wave-1 the per-tenant DB schema is bootstrapped from {@code classpath:db/migration}
     * (the legacy root) which includes V1 ({@code tenants} table) through V16. FK constraints on
     * {@code tournament}, {@code devices}, etc. require the tenant row to exist in the same DB.
     * Since {@link #upsertMainDbTenantRow} only populates the flat DataSource's {@code tenants}
     * table, the per-tenant DB's table is empty — causing FK violations on repository inserts. This
     * method populates both the tenant and location rows in the given DataSource.
     *
     * <p>Idempotent: checks row existence before inserting. {@link DataIntegrityViolationException}
     * from a concurrent insert is swallowed (rows are present — the race loser catches this case).
     *
     * @param tenantId the tenant UUID to upsert; must not be {@code null}
     * @param jdbcTemplate the JdbcTemplate backed by the target DataSource (flat or per-tenant)
     */
    void upsertDbTenantRow(UUID tenantId, JdbcTemplate jdbcTemplate) {
        try {
            Integer count =
                    jdbcTemplate.queryForObject(SELECT_TENANT_BY_ID_SQL, Integer.class, tenantId);
            if (count != null && count > 0) {
                log.debug(
                        "[tm-e14s11] Tenant row already present in target DB for UUID={}",
                        tenantId);
                // Check location too
                Integer locationCount =
                        jdbcTemplate.queryForObject(
                                SELECT_LOCATION_COUNT_SQL, Integer.class, tenantId);
                if (locationCount == null || locationCount == 0) {
                    try {
                        jdbcTemplate.update(INSERT_LOCATION_SQL, UUID.randomUUID(), tenantId);
                        log.info(
                                "[tm-e14s11] Location row inserted in target DB for UUID={}",
                                tenantId);
                    } catch (DataIntegrityViolationException ignored) {
                        // Concurrent insert won
                    }
                }
                return;
            }
            jdbcTemplate.update(INSERT_TENANT_SQL, tenantId);
            jdbcTemplate.update(INSERT_LOCATION_SQL, UUID.randomUUID(), tenantId);
            log.info(
                    "[tm-e14s11] Tenant+location rows inserted in target DB for UUID={}", tenantId);
        } catch (DataIntegrityViolationException race) {
            log.info(
                    "[tm-e14s11] Tenant row insert race in target DB for UUID={}: {}",
                    tenantId,
                    race.getMessage());
        } catch (org.springframework.jdbc.BadSqlGrammarException noTable) {
            // The tenants table does not exist in this DB (Flyway may have run with empty
            // locations).
            // This is a no-op — wave-1 fallback will ensure the table exists on next start.
            log.debug(
                    "[tm-e14s11] Tenant table absent in target DB for UUID={} — skipping upsert"
                            + " (wave-1 no-op): {}",
                    tenantId,
                    noTable.getMessage());
        }
    }

    /**
     * Atomically registers a tenant if no entry with the same {@code displayName} exists.
     *
     * <p>When the underlying registry is a {@link TenantFileRegistry} (same package), uses {@link
     * TenantFileRegistry#registerIfDisplayNameAbsent} for a synchronized check+write that prevents
     * duplicate displayName entries under concurrent first-start scenarios (AC6).
     *
     * <p>For other {@link TenantRegistryPort} implementations (test doubles, etc.), falls back to a
     * non-atomic double-check + register (may occasionally produce two entries in tests that use
     * concurrent access against non-synchronized mocks — acceptable for test doubles).
     *
     * @param tenantId the tenant UUID to register
     * @param displayName the display name to check for uniqueness
     * @return {@code true} if registration succeeded; {@code false} if the displayName already
     *     existed
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
     * Creates an H2 file-based DataSource for the given tenant UUID, using the standard {@link
     * TenantDirectoryHelper#tenantDbPath} layout.
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
        // CASE_INSENSITIVE_IDENTIFIERS=TRUE: required so that Spring Data JDBC quoted identifiers
        // (e.g. "tournament") match H2 uppercase-stored names (TOURNAMENT). Must match the URL
        // used by TenantFileRegistryDataSourceResolver for the operational connection.
        ds.setURL(
                "jdbc:h2:file:" + urlPath + ";AUTO_SERVER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * Attempts to delete the tenant directory for the given UUID (AC6: self-cleanup after losing a
     * concurrent registration race, or cleanup after orphan detection).
     *
     * <p>Errors during cleanup are logged as warnings but do NOT propagate — the primary concern is
     * that the winner's entry is in the registry and startup can continue.
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
                    .forEach(
                            f -> {
                                if (!f.delete()) {
                                    log.warn(
                                            "[tm-e14s05] Could not delete path during cleanup: {}",
                                            f.getAbsolutePath());
                                }
                            });
        } catch (IOException e) {
            log.warn(
                    "[tm-e14s05] Error during directory cleanup at '{}': {}", root, e.getMessage());
        }
    }
}
