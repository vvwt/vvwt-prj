package de.vvwt.tm.tenant.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Helper class for computing and creating per-tenant H2 file paths.
 *
 * <h2>File layout (DEC-20, AC2)</h2>
 * <pre>
 * ${tm.data.dir}/
 *   tenants/
 *     {tenant-uuid}/
 *       db.mv.db          ← per-tenant H2 database file
 *   tenant-registry.json  ← registry (separate from tenant dirs)
 * </pre>
 *
 * <h2>Path length safety (AC-PATH-LENGTH)</h2>
 * <p>If the total path for {@code db.mv.db} exceeds 250 characters (conservative below the
 * Windows default MAX_PATH of 260), {@link #tenantDbPath(Path, UUID)} throws
 * {@link IllegalStateException} with a message naming both the computed path and its length.
 * This prevents silent failures on Windows deployments with long {@code tm.data.dir} values.
 *
 * @see TenantFileRegistry
 * @see <a href="../../../../../../../../docs/governance/stories/E14S02.story.md">Story E14S02 AC2, AC4, AC-PATH-LENGTH</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 */
public final class TenantDirectoryHelper {

    /** Maximum permitted absolute path length (conservative buffer below Windows 260 MAX_PATH). */
    static final int MAX_PATH_LENGTH = 250;

    /** Relative suffix appended after the data dir: {@code /tenants/{uuid}/db.mv.db} */
    // 9 + 36 + 9 = 54 characters
    private static final String RELATIVE_SUFFIX_TEMPLATE = "/tenants/%s/db.mv.db";

    private TenantDirectoryHelper() {
        // utility class — no instances
    }

    /**
     * Computes the absolute path for the given tenant's H2 database file.
     *
     * <p>Layout: {@code ${dataDir}/tenants/{tenantId}/db.mv.db}
     *
     * @param dataDir  the root data directory ({@code ${tm.data.dir}}); must not be {@code null}
     * @param tenantId the tenant UUID; must not be {@code null}
     * @return the absolute path to the tenant's H2 database file
     * @throws IllegalArgumentException if {@code dataDir} or {@code tenantId} is {@code null}
     * @throws IllegalStateException    if the computed path exceeds {@value #MAX_PATH_LENGTH} characters
     *                                  (AC-PATH-LENGTH)
     */
    public static Path tenantDbPath(Path dataDir, UUID tenantId) {
        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir must not be null");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }

        Path dbPath = dataDir.resolve("tenants")
                .resolve(tenantId.toString())
                .resolve("db.mv.db");

        String absoluteStr = dbPath.toAbsolutePath().toString();
        if (absoluteStr.length() > MAX_PATH_LENGTH) {
            throw new IllegalStateException(
                    "Computed tenant DB path exceeds the safe limit of " + MAX_PATH_LENGTH
                    + " characters. Computed path: '" + absoluteStr
                    + "' (length: " + absoluteStr.length() + "). "
                    + "Shorten the 'tm.data.dir' configuration value: '" + dataDir + "'. "
                    + "See AC-PATH-LENGTH in E14S02 for details.");
        }

        return dbPath;
    }

    /**
     * Creates the tenant-specific directory (parent of the H2 database file) if it does not exist.
     *
     * <p>If the directory already exists, this method is a no-op.
     *
     * @param dataDir  the root data directory ({@code ${tm.data.dir}}); must not be {@code null}
     * @param tenantId the tenant UUID; must not be {@code null}
     * @throws IllegalArgumentException if {@code dataDir} or {@code tenantId} is {@code null}
     * @throws IllegalStateException    if the directory cannot be created (e.g., parent is not writable);
     *                                  the message names the path and the required permission (AC4)
     */
    public static void createTenantDirectory(Path dataDir, UUID tenantId) {
        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir must not be null");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }

        Path tenantDir = dataDir.resolve("tenants").resolve(tenantId.toString());
        try {
            Files.createDirectories(tenantDir);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot create tenant directory '" + tenantDir.toAbsolutePath()
                    + "'. Ensure the data directory '" + dataDir.toAbsolutePath()
                    + "' exists and is writable. Required permission: write access. "
                    + "Underlying error: " + e.getMessage(), e);
        }
    }
}
