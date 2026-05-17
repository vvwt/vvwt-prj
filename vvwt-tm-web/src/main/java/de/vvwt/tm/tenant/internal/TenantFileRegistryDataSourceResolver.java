// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant.internal;

import com.zaxxer.hikari.HikariDataSource;
import de.vvwt.tm.tenant.DiagnosticProperties;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.LoggerFactory;

/**
 * {@link TenantDataSourceResolver} backed by {@link TenantFileRegistry} and {@link
 * TenantDirectoryHelper}.
 *
 * <h2>Responsibility</h2>
 *
 * <p>For each tenant UUID, this resolver:
 *
 * <ol>
 *   <li>Verifies the tenant is registered in {@link TenantRegistryPort} (existence check).
 *   <li>Computes the H2 file path via {@link TenantDirectoryHelper}.
 *   <li>Creates and caches the H2 {@link DataSource} for that tenant (lazy, first access).
 * </ol>
 *
 * <h2>Caching</h2>
 *
 * <p>DataSource instances are cached in a {@link ConcurrentHashMap} keyed by tenant UUID. Once
 * created, a DataSource is reused for all subsequent connections from the same tenant context — H2
 * file-mode datasources are stateless factory objects, safe to cache.
 *
 * <h2>Unknown tenants</h2>
 *
 * <p>If a tenant UUID is not in the registry, {@link
 * TenantDataSourceResolver.UnknownTenantException} is thrown immediately (fail-fast, AC3). No
 * lazy-failing DataSource is ever returned.
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link ConcurrentHashMap#computeIfAbsent} provides atomic first-creation semantics. The H2
 * JDBC connection factory itself is thread-safe.
 *
 * @see TenantDirectoryHelper
 * @see TenantRegistryPort
 * @see <a href="../../../../../../../../docs/governance/stories/E14S03.story.md">Story E14S03</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-14.md">DEC-14</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 */
public class TenantFileRegistryDataSourceResolver implements TenantDataSourceResolver {

    private static final org.slf4j.Logger LOG =
            LoggerFactory.getLogger(TenantFileRegistryDataSourceResolver.class);

    private final TenantRegistryPort tenantRegistryPort;
    private final TmDataDirProperties dataDirProperties;
    private final DiagnosticProperties diagnosticProperties;
    private final TmHikariProperties hikariProperties;

    /** Cache: tenant UUID → DataSource. Populated lazily on first resolution. */
    private final ConcurrentMap<UUID, DataSource> cache = new ConcurrentHashMap<>();

    /**
     * Constructs a {@code TenantFileRegistryDataSourceResolver} with E55S10 feature flags.
     *
     * @param tenantRegistryPort the registry that tracks registered tenants; must not be {@code
     *     null}
     * @param dataDirProperties the data directory configuration used by {@link
     *     TenantDirectoryHelper} to compute H2 file paths; must not be {@code null}
     * @param diagnosticProperties E55S10 diagnostic feature flags (hikari-trace, spring-tx-trace);
     *     must not be {@code null}
     * @param hikariProperties E55S10 HikariCP feature flags (single-writer-per-tenant); must not be
     *     {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     * @since E55S10
     */
    public TenantFileRegistryDataSourceResolver(
            TenantRegistryPort tenantRegistryPort,
            TmDataDirProperties dataDirProperties,
            DiagnosticProperties diagnosticProperties,
            TmHikariProperties hikariProperties) {
        if (tenantRegistryPort == null) {
            throw new IllegalArgumentException("tenantRegistryPort must not be null");
        }
        if (dataDirProperties == null) {
            throw new IllegalArgumentException("dataDirProperties must not be null");
        }
        if (diagnosticProperties == null) {
            throw new IllegalArgumentException("diagnosticProperties must not be null");
        }
        if (hikariProperties == null) {
            throw new IllegalArgumentException("hikariProperties must not be null");
        }
        this.tenantRegistryPort = tenantRegistryPort;
        this.dataDirProperties = dataDirProperties;
        this.diagnosticProperties = diagnosticProperties;
        this.hikariProperties = hikariProperties;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Verifies the tenant is registered, then creates (or returns the cached) H2 file {@link
     * DataSource} for that tenant. The DataSource is created lazily on first access and cached
     * thereafter.
     *
     * @throws UnknownTenantException if the tenant UUID is not registered (fail-fast, AC3)
     * @throws IllegalArgumentException if {@code tenantId} is {@code null}
     * @throws IllegalStateException if the H2 path exceeds the safety limit (from {@link
     *     TenantDirectoryHelper})
     */
    @Override
    public DataSource resolve(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }

        // Verify the tenant exists in the registry — fail-fast (AC3)
        tenantRegistryPort.lookup(tenantId).orElseThrow(() -> new UnknownTenantException(tenantId));

        // Create the DataSource once and cache it
        return cache.computeIfAbsent(tenantId, this::createH2DataSource);
    }

    /**
     * Creates an H2 file-based {@link DataSource} for the given tenant.
     *
     * <p>File layout (DEC-20): {@code ${tm.data.dir}/tenants/{uuid}/db.mv.db} The H2 JDBC URL uses
     * the path WITHOUT the {@code .mv.db} extension — H2 appends it.
     *
     * <h2>E55S10 feature flags</h2>
     *
     * <p>When {@code tm.hikari.single-writer-per-tenant=true} (Stair-2 H-1 stopgap): the raw {@link
     * JdbcDataSource} is wrapped in a {@link HikariDataSource} with {@code maximumPoolSize=1},
     * serializing all per-tenant H2 connections. Default {@code false}.
     *
     * <p>When {@code tm.diagnostics.hikari-trace=true}: the DataSource (raw or HikariCP-wrapped) is
     * further wrapped in a {@link DiagnosticDataSourceWrapper} that emits structured INFO logs at
     * acquire/release time. Default {@code false}.
     *
     * @param tenantId the tenant UUID for which to create the DataSource
     * @return a new H2 JDBC DataSource pointing to the tenant's file, potentially wrapped
     * @since E55S10 (feature flags added)
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

        // CASE_INSENSITIVE_IDENTIFIERS=TRUE: Spring Data JDBC generates quoted lowercase
        // identifiers (e.g. SELECT COUNT("tournament"."ID")) but H2 stores unquoted names as
        // uppercase. Without this flag, H2 treats quoted "tournament" ≠ stored TOURNAMENT
        // (case-sensitive). This matches the behaviour of the flat DataSource (H2 mem) which also
        // has case-insensitive identifier matching via H2Dialect's IdentifierProcessing.
        String jdbcUrl =
                "jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

        DataSource ds;
        if (hikariProperties.isSingleWriterPerTenant()) {
            // E55S10 Stair-2 H-1 stopgap: wrap in HikariDataSource with maximumPoolSize=1
            // to serialize all per-tenant H2 connections (eliminates H-1b concurrent race).
            HikariDataSource hikariDs = new HikariDataSource();
            hikariDs.setJdbcUrl(jdbcUrl);
            hikariDs.setUsername("sa");
            hikariDs.setPassword("");
            hikariDs.setMaximumPoolSize(1);
            hikariDs.setMinimumIdle(0);
            hikariDs.setConnectionTimeout(30_000L);
            hikariDs.setPoolName("phaselifecycle-tenant-" + tenantId.toString().substring(0, 8));
            ds = hikariDs;
            LOG.info(
                    "[tenant] Single-writer-per-tenant mode ACTIVE for tenant={} (E55S10 H-1"
                            + " stopgap, maximumPoolSize=1)",
                    tenantId);
        } else {
            JdbcDataSource jdbcDs = new JdbcDataSource();
            jdbcDs.setURL(jdbcUrl);
            jdbcDs.setUser("sa");
            jdbcDs.setPassword("");
            ds = jdbcDs;
        }

        if (diagnosticProperties.isHikariTrace()) {
            // E55S10 diagnostic instrumentation: wrap in DiagnosticDataSourceWrapper
            ds = new DiagnosticDataSourceWrapper(ds, tenantId);
            LOG.info(
                    "[tenant] Diagnostic connection tracing ACTIVE for tenant={} (E55S10"
                            + " AC-DIAG-INSTRUMENT-HIKARICP-LIFECYCLE)",
                    tenantId);
        }

        return ds;
    }
}
