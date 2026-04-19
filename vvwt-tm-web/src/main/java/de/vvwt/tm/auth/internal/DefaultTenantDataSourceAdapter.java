package de.vvwt.tm.auth.internal;

import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} adapter that lazily resolves the default tenant's per-tenant {@link
 * DataSource} on the first {@link #getConnection()} call.
 *
 * <h2>Purpose</h2>
 *
 * <p>Used by {@link AuthConfiguration} to supply {@link AdminCredentialsDao} with the correct
 * per-tenant DataSource at bootstrap time. The resolution is deferred to {@code getConnection()}
 * rather than construction time because:
 *
 * <ul>
 *   <li>The {@link AuthConfiguration} bean is instantiated during the Spring context refresh,
 *       before {@code ApplicationRunner} instances have fired.
 *   <li>{@link TenantRegistryPort#getDefault()} requires the default tenant to be registered —
 *       which only happens after {@code DefaultTenantBootstrapRunner.run()} completes
 *       ({@code @Order(1)}).
 *   <li>{@link AdminCredentialsBootstrap} fires at {@code @Order(2)}, so {@code getConnection()} is
 *       first called from within {@link AdminCredentialsBootstrap#run()} — i.e., after the tenant
 *       is registered.
 * </ul>
 *
 * <h2>Thread-safety</h2>
 *
 * <p>Resolution is idempotent and stateless — each call to {@code getConnection()} resolves the
 * default tenant's UUID and delegates to {@link TenantDataSourceResolver#resolve(UUID)}. No caching
 * is performed here; caching is the resolver's responsibility.
 *
 * <h2>DEC-20 compliance</h2>
 *
 * <p>This adapter fulfils the DEC-20 requirement that all {@code auth}-context DB access goes to
 * the per-tenant DataSource. It does NOT use {@link de.vvwt.tm.tenant.TenantContext} — it accesses
 * the default tenant directly via {@link TenantRegistryPort#getDefault()} and is therefore safe to
 * call from {@code ApplicationRunner} contexts where no TenantContext is bound.
 *
 * @see AuthConfiguration
 * @see AdminCredentialsBootstrap
 * @see TenantRegistryPort#getDefault()
 * @see TenantDataSourceResolver#resolve(UUID)
 * @since E15S07
 */
final class DefaultTenantDataSourceAdapter implements DataSource {

    private final TenantRegistryPort tenantRegistryPort;
    private final TenantDataSourceResolver tenantDataSourceResolver;

    /**
     * Constructs a {@code DefaultTenantDataSourceAdapter}.
     *
     * @param tenantRegistryPort provides {@link TenantRegistryPort#getDefault()}; must not be
     *     {@code null}
     * @param tenantDataSourceResolver resolves the per-tenant DataSource; must not be {@code null}
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    DefaultTenantDataSourceAdapter(
            TenantRegistryPort tenantRegistryPort,
            TenantDataSourceResolver tenantDataSourceResolver) {
        if (tenantRegistryPort == null) {
            throw new IllegalArgumentException("tenantRegistryPort must not be null");
        }
        if (tenantDataSourceResolver == null) {
            throw new IllegalArgumentException("tenantDataSourceResolver must not be null");
        }
        this.tenantRegistryPort = tenantRegistryPort;
        this.tenantDataSourceResolver = tenantDataSourceResolver;
    }

    // -------------------------------------------------------------------------
    // DataSource — connection delegation
    // -------------------------------------------------------------------------

    /**
     * Resolves the default tenant's {@link DataSource} and returns a connection from it.
     *
     * <p>Called from within {@link AdminCredentialsBootstrap#run()} ({@code @Order(2)}), which is
     * guaranteed to fire after {@code DefaultTenantBootstrapRunner.run()} ({@code @Order(1)}). The
     * default tenant is therefore registered at this point.
     *
     * @return a JDBC connection to the default tenant's per-tenant H2 database
     * @throws SQLException if the connection cannot be obtained
     * @throws IllegalStateException if no default tenant is registered (bootstrap invariant
     *     violated)
     */
    @Override
    public Connection getConnection() throws SQLException {
        return resolveDefaultTenantDataSource().getConnection();
    }

    /**
     * Resolves the default tenant's {@link DataSource} and returns a connection from it (with
     * explicit credentials).
     *
     * @param username the JDBC username (passed through to the resolved DataSource)
     * @param password the JDBC password (passed through to the resolved DataSource)
     * @return a JDBC connection to the default tenant's per-tenant H2 database
     * @throws SQLException if the connection cannot be obtained
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return resolveDefaultTenantDataSource().getConnection(username, password);
    }

    // -------------------------------------------------------------------------
    // DataSource — metadata delegation
    // -------------------------------------------------------------------------

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return resolveDefaultTenantDataSource().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        resolveDefaultTenantDataSource().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        resolveDefaultTenantDataSource().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return resolveDefaultTenantDataSource().getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        try {
            return resolveDefaultTenantDataSource().getParentLogger();
        } catch (IllegalStateException e) {
            throw new SQLFeatureNotSupportedException(
                    "Cannot resolve default tenant DataSource: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return resolveDefaultTenantDataSource().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return resolveDefaultTenantDataSource().isWrapperFor(iface);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * Resolves the default tenant's per-tenant {@link DataSource} on every call. Caching is
     * delegated to the {@link TenantDataSourceResolver} implementation.
     *
     * @return the per-tenant DataSource for the default tenant; never {@code null}
     * @throws IllegalStateException if {@link TenantRegistryPort#getDefault()} throws (bootstrap
     *     not complete — should never happen when called from {@code
     *     AdminCredentialsBootstrap.run()})
     */
    private DataSource resolveDefaultTenantDataSource() {
        UUID defaultTenantId = tenantRegistryPort.getDefault();
        return tenantDataSourceResolver.resolve(defaultTenantId);
    }
}
