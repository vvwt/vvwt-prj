package de.vvwt.tm.tenant.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tenant.TenantRegistryPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * File-based implementation of {@link TenantRegistryPort}.
 *
 * <h2>Registry persistence (AC3)</h2>
 * <p>The registry is persisted as a JSON file at {@code ${tm.data.dir}/tenant-registry.json}.
 * The JSON format is a human-inspectable array of tenant records — readable with any text editor
 * or {@code cat}. Example:
 * <pre>
 * [
 *   {"tenantId":"aaaaaaaa-...","displayName":"Default (LAN)"}
 * ]
 * </pre>
 *
 * <h2>Persistence choice rationale (AC3)</h2>
 * <p>Two options were considered:
 * <ol>
 *   <li><strong>H2 registry DB</strong> — second H2 file at {@code registry.mv.db}. Provides
 *       transactional semantics at the cost of a second file operators must back up.</li>
 *   <li><strong>JSON file</strong> — single {@code tenant-registry.json}. Human-inspectable
 *       without tooling; editable in an emergency with a text editor. Atomic write safety
 *       via write-to-temp + {@code Files.move(ATOMIC_MOVE)}, which is crash-safe on
 *       POSIX filesystems (rename is atomic). Concurrent-write safety via {@code synchronized}
 *       on this instance — sufficient for single-JVM scenarios (the Wave-1 deployment model).</li>
 * </ol>
 * <p>JSON file was chosen because: (a) operators can inspect and understand the registry without
 * H2 tooling; (b) one fewer backup target; (c) the atomic-move + synchronized combo satisfies
 * the concurrency safety requirement at Wave-1 scale; (d) H2 transactional guarantees are not
 * needed for a registry that is only written at tenant-creation time, not on every request.
 *
 * <h2>Thread safety (AC-CONCURRENT-REGISTER)</h2>
 * <p>All read/write operations on the registry are synchronized on {@code this}. Two threads
 * calling {@link #register} with DIFFERENT tenant IDs both succeed. Two threads calling
 * {@link #register} with the SAME tenant ID: exactly one succeeds; the other receives
 * {@link DuplicateTenantException}.
 *
 * <h2>Corrupt registry (AC5)</h2>
 * <p>If the registry file exists but is unreadable or malformed, the constructor throws
 * {@link IllegalStateException} with a message naming the registry file. The file is NOT
 * silently recreated — silent recreation would risk hidden data loss.
 *
 * <h2>Missing data dir (AC4)</h2>
 * <p>If {@code dataDir} does not exist, the constructor creates it. If the path exists but is
 * not writable, the first write operation throws {@link IllegalStateException} naming the path.
 *
 * @see TenantRegistryPort
 * @see TenantDirectoryHelper
 * @see <a href="../../../../../../../../docs/governance/stories/E14S02.story.md">Story E14S02</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22</a>
 */
public class TenantFileRegistry implements TenantRegistryPort {

    /** Name of the registry JSON file within the data directory. */
    static final String REGISTRY_FILE_NAME = "tenant-registry.json";

    private final Path registryFile;
    private final ObjectMapper objectMapper;

    /** In-memory cache, keyed by tenant UUID string for fast lookup (loaded from file on init). */
    private final Map<UUID, TenantRecord> inMemoryRegistry;

    /**
     * Constructs a {@code TenantFileRegistry} backed by the given data directory.
     *
     * <p>If the data directory does not exist, it is created (AC4). If the registry file
     * exists, it is loaded; if it is corrupt, {@link IllegalStateException} is thrown (AC5).
     *
     * @param dataDir the root data directory ({@code ${tm.data.dir}}); must not be {@code null}
     * @throws IllegalArgumentException if {@code dataDir} is {@code null}
     * @throws IllegalStateException    if the registry file exists but is corrupt (AC5),
     *                                  or if the data directory cannot be created (AC4)
     */
    public TenantFileRegistry(Path dataDir) {
        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir must not be null");
        }
        this.objectMapper = new ObjectMapper();
        this.registryFile = dataDir.resolve(REGISTRY_FILE_NAME);
        this.inMemoryRegistry = new LinkedHashMap<>();

        ensureDataDirExists(dataDir);
        loadRegistryFromFile();
    }

    // -------------------------------------------------------------------------
    // TenantRegistryPort implementation
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@link Optional#empty()} for unknown tenants — does NOT throw (AC3 of E14S01).
     */
    @Override
    public synchronized Optional<TenantRecord> lookup(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        return Optional.ofNullable(inMemoryRegistry.get(tenantId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Registers the tenant durably: updates the in-memory map and persists atomically to
     * {@code tenant-registry.json} (write-to-temp + atomic move). Thread-safe: synchronized on
     * this instance (AC-CONCURRENT-REGISTER).
     *
     * @throws DuplicateTenantException if {@code tenantId} is already registered (AC6)
     * @throws IllegalArgumentException if {@code tenantId} or {@code displayName} is null
     * @throws IllegalStateException    if the registry file cannot be written (AC4 error path)
     */
    @Override
    public synchronized void register(UUID tenantId, String displayName) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (displayName == null) {
            throw new IllegalArgumentException("displayName must not be null");
        }
        if (inMemoryRegistry.containsKey(tenantId)) {
            throw new DuplicateTenantException(tenantId);
        }

        TenantRecord record = new TenantRecord(tenantId, displayName);
        inMemoryRegistry.put(tenantId, record);
        persistRegistryToFile();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a snapshot of all registered tenants at the time of the call.
     */
    @Override
    public synchronized List<TenantRecord> findAll() {
        return Collections.unmodifiableList(new ArrayList<>(inMemoryRegistry.values()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans the in-memory registry for the unique tenant entry whose
     * {@code displayName} equals {@link DefaultTenantBootstrapRunner#DEFAULT_TENANT_DISPLAY_NAME}
     * ({@code "Default (LAN)"}), which is the display name assigned by E14S05.
     *
     * <h2>Thread safety</h2>
     * <p>Synchronized on this instance — consistent with all other registry operations.
     * For Wave-1 with a single default tenant bootstrapped at app start, the value is stable
     * for the lifetime of the process. Caching is an acceptable future optimization (Wave-2 scope).
     *
     * @throws IllegalStateException if zero or multiple default tenants are registered
     */
    @Override
    public synchronized UUID getDefault() {
        List<TenantRecord> defaults = inMemoryRegistry.values().stream()
                .filter(r -> DefaultTenantBootstrapRunner.DEFAULT_TENANT_DISPLAY_NAME.equals(r.displayName()))
                .toList();
        if (defaults.isEmpty()) {
            throw new IllegalStateException(
                    "no default tenant registered \u2014 bootstrap not complete");
        }
        if (defaults.size() > 1) {
            throw new IllegalStateException(
                    "registry violates single-default invariant");
        }
        return defaults.get(0).tenantId();
    }

    /**
     * Atomically registers the tenant only if no entry with the given {@code displayName}
     * is already present in the registry. Used by {@link DefaultTenantBootstrapRunner} to
     * prevent duplicate default-tenant entries under concurrent first-start scenarios (E14S05 AC6).
     *
     * <p>This method is intentionally package-private — it is an implementation detail of the
     * {@code tenant.internal} package and MUST NOT be called from outside this package.
     * It is NOT part of the {@link de.vvwt.tm.tenant.TenantRegistryPort} public API.
     *
     * <h2>Atomicity</h2>
     * <p>Both the existence check and the write are performed inside the same {@code synchronized}
     * block. This makes "check + register" atomic with respect to other threads that call
     * {@link #register} or {@link #findAll} on the same instance.
     *
     * @param tenantId    the UUID of the new tenant; must not be {@code null}
     * @param displayName the display name to check for uniqueness; must not be {@code null}
     * @return {@code true} if the registration succeeded (no prior entry with this displayName);
     *         {@code false} if an entry with the same displayName already existed (concurrent winner)
     * @throws IllegalArgumentException if {@code tenantId} or {@code displayName} is null
     * @throws IllegalStateException    if the registry file cannot be written
     * @see DefaultTenantBootstrapRunner
     * @see <a href="../../../../../../../../docs/governance/stories/E14S05.story.md">Story E14S05 AC6</a>
     */
    synchronized boolean registerIfDisplayNameAbsent(UUID tenantId, String displayName) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (displayName == null) {
            throw new IllegalArgumentException("displayName must not be null");
        }

        // Check if any entry with this displayName already exists
        boolean alreadyExists = inMemoryRegistry.values().stream()
                .anyMatch(r -> displayName.equals(r.displayName()));

        if (alreadyExists) {
            return false; // Concurrent winner already registered this displayName
        }

        // Also check by UUID (DuplicateTenantException safety)
        if (inMemoryRegistry.containsKey(tenantId)) {
            throw new DuplicateTenantException(tenantId);
        }

        TenantRecord record = new TenantRecord(tenantId, displayName);
        inMemoryRegistry.put(tenantId, record);
        persistRegistryToFile();
        return true;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void ensureDataDirExists(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot create or access data directory '" + dataDir.toAbsolutePath()
                    + "'. Ensure the path exists and is writable. "
                    + "Required permission: write access. Underlying error: " + e.getMessage(), e);
        }
    }

    private void loadRegistryFromFile() {
        if (!Files.exists(registryFile)) {
            // No file yet — empty registry, nothing to load
            return;
        }
        try {
            List<RegistryEntry> entries = objectMapper.readValue(
                    registryFile.toFile(),
                    new TypeReference<List<RegistryEntry>>() {});
            for (RegistryEntry entry : entries) {
                inMemoryRegistry.put(entry.tenantId(), new TenantRecord(entry.tenantId(), entry.displayName()));
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "The tenant registry file is present but cannot be read or is malformed. "
                    + "Registry location: '" + registryFile.toAbsolutePath() + "'. "
                    + "Do NOT delete this file — it may contain tenant data. "
                    + "Inspect the file manually and repair the JSON. "
                    + "Underlying error: " + e.getMessage(), e);
        }
    }

    private void persistRegistryToFile() {
        List<RegistryEntry> entries = new ArrayList<>();
        for (TenantRecord record : inMemoryRegistry.values()) {
            entries.add(new RegistryEntry(record.tenantId(), record.displayName()));
        }

        // Write to a temp file first, then atomically move — crash-safe (AC3)
        Path tempFile = registryFile.resolveSibling(REGISTRY_FILE_NAME + ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), entries);
            Files.move(tempFile, registryFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot write the tenant registry to '" + registryFile.toAbsolutePath()
                    + "'. Ensure the data directory is writable. "
                    + "Underlying error: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /**
     * JSON serialization DTO for a single registry entry.
     * Kept package-private — implementation detail of this class.
     */
    record RegistryEntry(
            @JsonProperty("tenantId") UUID tenantId,
            @JsonProperty("displayName") String displayName) {
    }

    /**
     * Thrown by {@link TenantFileRegistry#register(UUID, String)} when the given tenant UUID
     * is already present in the registry (AC6 of E14S02).
     *
     * <p>This is a fast-fail, deterministic exception — no silent overwrite is ever performed.
     * The message includes the duplicate tenant UUID to aid debugging.
     */
    public static class DuplicateTenantException extends RuntimeException {

        private final UUID tenantId;

        /**
         * Constructs a {@code DuplicateTenantException} for the given tenant UUID.
         *
         * @param tenantId the UUID that was already registered
         */
        public DuplicateTenantException(UUID tenantId) {
            super("Tenant '" + tenantId + "' is already registered in the registry. "
                    + "No silent overwrite is performed. "
                    + "If re-registration is intentional, remove the existing entry first.");
            this.tenantId = tenantId;
        }

        /**
         * Returns the duplicate tenant UUID.
         *
         * @return the tenant UUID that caused the conflict; never {@code null}
         */
        public UUID getTenantId() {
            return tenantId;
        }
    }
}
