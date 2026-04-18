package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.datasource.SmartDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link TenantDataSourceResolver} backed by {@link TenantFileRegistry} and
 * {@link TenantDirectoryHelper}.
 *
 * <h2>Responsibility</h2>
 * <p>For each tenant UUID, this resolver:
 * <ol>
 *   <li>Verifies the tenant is registered in {@link TenantRegistryPort} (existence check).</li>
 *   <li>Computes the H2 file path via {@link TenantDirectoryHelper}.</li>
 *   <li>Creates and caches the H2 {@link DataSource} for that tenant (lazy, first access).</li>
 * </ol>
 *
 * <h2>Caching</h2>
 * <p>DataSource instances are cached in a {@link ConcurrentHashMap} keyed by tenant UUID.
 * Once created, a DataSource is reused for all subsequent connections from the same tenant
 * context — H2 file-mode datasources are stateless factory objects, safe to cache.
 *
 * <h2>Unknown tenants</h2>
 * <p>If a tenant UUID is not in the registry, {@link TenantDataSourceResolver.UnknownTenantException}
 * is thrown immediately (fail-fast, AC3). No lazy-failing DataSource is ever returned.
 *
 * <h2>Thread safety</h2>
 * <p>{@link ConcurrentHashMap#computeIfAbsent} provides atomic first-creation semantics.
 * The H2 JDBC connection factory itself is thread-safe.
 *
 * @see TenantDirectoryHelper
 * @see TenantRegistryPort
 * @see <a href="../../../../../../../../docs/governance/stories/E14S03.story.md">Story E14S03</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-14.md">DEC-14</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 */
public class TenantFileRegistryDataSourceResolver implements TenantDataSourceResolver {

    private final TenantRegistryPort tenantRegistryPort;
    private final TmDataDirProperties dataDirProperties;

    /** Cache: tenant UUID → DataSource. Populated lazily on first resolution. */
    private final ConcurrentMap<UUID, DataSource> cache = new ConcurrentHashMap<>();

    /**
     * Constructs a {@code TenantFileRegistryDataSourceResolver}.
     *
     * @param tenantRegistryPort the registry that tracks registered tenants; must not be
     *                           {@code null}
     * @param dataDirProperties  the data directory configuration used by {@link TenantDirectoryHelper}
     *                           to compute H2 file paths; must not be {@code null}
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public TenantFileRegistryDataSourceResolver(TenantRegistryPort tenantRegistryPort,
                                                TmDataDirProperties dataDirProperties) {
        if (tenantRegistryPort == null) {
            throw new IllegalArgumentException("tenantRegistryPort must not be null");
        }
        if (dataDirProperties == null) {
            throw new IllegalArgumentException("dataDirProperties must not be null");
        }
        this.tenantRegistryPort = tenantRegistryPort;
        this.dataDirProperties = dataDirProperties;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Verifies the tenant is registered, then creates (or returns the cached) H2 file
     * {@link DataSource} for that tenant. The DataSource is created lazily on first access
     * and cached thereafter.
     *
     * @throws UnknownTenantException   if the tenant UUID is not registered (fail-fast, AC3)
     * @throws IllegalArgumentException if {@code tenantId} is {@code null}
     * @throws IllegalStateException    if the H2 path exceeds the safety limit
     *                                  (from {@link TenantDirectoryHelper})
     */
    @Override
    public DataSource resolve(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }

        // Verify the tenant exists in the registry — fail-fast (AC3)
        tenantRegistryPort.lookup(tenantId)
                .orElseThrow(() -> new UnknownTenantException(tenantId));

        // Create the DataSource once and cache it
        return cache.computeIfAbsent(tenantId, this::createH2DataSource);
    }

    /**
     * Creates an H2 file-based {@link DataSource} for the given tenant.
     *
     * <p>File layout (DEC-20): {@code ${tm.data.dir}/tenants/{uuid}/db.mv.db}
     * The H2 JDBC URL uses the path WITHOUT the {@code .mv.db} extension — H2 appends it.
     *
     * @param tenantId the tenant UUID for which to create the DataSource
     * @return a new H2 JDBC DataSource pointing to the tenant's file
     */
    private DataSource createH2DataSource(UUID tenantId) {
        Path dataDir = dataDirProperties.asPath();
        // tenantDbPath returns the full path including ".mv.db" extension;
        // H2 JDBC URL needs the path WITHOUT the extension (H2 appends it).
        Path dbFile = TenantDirectoryHelper.tenantDbPath(dataDir, tenantId);
        // Remove the ".mv.db" suffix for the JDBC URL
        String dbPath = dbFile.toAbsolutePath().toString();
        if (dbPath.endsWith(".mv.db")) {
            dbPath = dbPath.substring(0, dbPath.length() - ".mv.db".length());
        }

        TenantDirectoryHelper.createTenantDirectory(dataDir, tenantId);

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }
}
