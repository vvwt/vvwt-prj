// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant.internal;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Diagnostic wrapper around a per-tenant {@link DataSource} (E55S10).
 *
 * <h2>Purpose</h2>
 *
 * <p>Emits structured INFO-level log lines at every H2 connection acquire and release, capturing:
 *
 * <ul>
 *   <li>Thread name (to correlate with per-tournament executor threads vs HTTP-request threads)
 *   <li>Tenant UUID context
 *   <li>{@code autoCommit} state at acquire time (detects H-4: failed autoCommit reset)
 *   <li>Spring TX active flag ({@link
 *       TransactionSynchronizationManager#isActualTransactionActive()})
 *   <li>Current TX name ({@link TransactionSynchronizationManager#getCurrentTransactionName()})
 * </ul>
 *
 * <p>Active only when {@code tm.diagnostics.hikari-trace=true} (default false per
 * AC-SEC-NO-DEBUG-LEAK-IN-PROD).
 *
 * <h2>Design</h2>
 *
 * <p>This class is a plain utility wrapper — NOT a Spring bean — created programmatically inside
 * {@link TenantFileRegistryDataSourceResolver} when the feature flag is active. It decorates each
 * per-tenant {@link DataSource} and returns a connection proxy that logs {@code close()} as
 * "release".
 *
 * @see TenantFileRegistryDataSourceResolver
 * @see de.vvwt.tm.tenant.DiagnosticProperties
 * @since E55S10
 */
class DiagnosticDataSourceWrapper implements DataSource {

    private static final org.slf4j.Logger LOG =
            LoggerFactory.getLogger(DiagnosticDataSourceWrapper.class);

    private final DataSource delegate;
    private final UUID tenantId;

    DiagnosticDataSourceWrapper(DataSource delegate, UUID tenantId) {
        this.delegate = delegate;
        this.tenantId = tenantId;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection conn = delegate.getConnection();
        logAcquire(conn, null);
        return new DiagnosticConnectionWrapper(conn, tenantId);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection conn = delegate.getConnection(username, password);
        logAcquire(conn, null);
        return new DiagnosticConnectionWrapper(conn, tenantId);
    }

    // ── Delegate passthrough ──────────────────────────────────────────────────

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }

    // ── Logging helpers ───────────────────────────────────────────────────────

    private void logAcquire(Connection conn, @SuppressWarnings("unused") Object ignored) {
        boolean autoCommit;
        try {
            autoCommit = conn.getAutoCommit();
        } catch (SQLException e) {
            autoCommit = false;
        }
        boolean txActive = TransactionSynchronizationManager.isActualTransactionActive();
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        LOG.info(
                "[diag-conn] ACQUIRE tenant={} thread={} autoCommit={} txActive={} txName={}",
                tenantId,
                Thread.currentThread().getName(),
                autoCommit,
                txActive,
                txName != null ? txName : "<none>");
    }

    static void logRelease(UUID tenantId, boolean autoCommit, boolean txWasActive) {
        LOG.info(
                "[diag-conn] RELEASE tenant={} thread={} autoCommit={} txWasActive={}",
                tenantId,
                Thread.currentThread().getName(),
                autoCommit,
                txWasActive);
    }

    // ── Inner: connection proxy that logs close() ─────────────────────────────

    /**
     * Connection wrapper that emits a release log line on {@code close()}.
     *
     * <p>Captures {@code autoCommit} state and TX-active state at close time to detect H-4
     * (HikariCP autoCommit reset failure — manifests as connections returned to pool with {@code
     * autoCommit=false}).
     */
    static final class DiagnosticConnectionWrapper
            implements Connection, java.lang.reflect.InvocationHandler {

        private final Connection delegate;
        private final UUID tenantId;

        DiagnosticConnectionWrapper(Connection delegate, UUID tenantId) {
            this.delegate = delegate;
            this.tenantId = tenantId;
        }

        @Override
        public void close() throws SQLException {
            boolean autoCommit;
            try {
                autoCommit = delegate.getAutoCommit();
            } catch (SQLException e) {
                autoCommit = false;
            }
            boolean txActive = TransactionSynchronizationManager.isActualTransactionActive();
            logRelease(tenantId, autoCommit, txActive);
            delegate.close();
        }

        // ── Delegate all other Connection methods ─────────────────────────────

        @Override
        public java.sql.Statement createStatement() throws SQLException {
            return delegate.createStatement();
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException {
            return delegate.prepareStatement(sql);
        }

        @Override
        public java.sql.CallableStatement prepareCall(String sql) throws SQLException {
            return delegate.prepareCall(sql);
        }

        @Override
        public String nativeSQL(String sql) throws SQLException {
            return delegate.nativeSQL(sql);
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException {
            delegate.setAutoCommit(autoCommit);
        }

        @Override
        public boolean getAutoCommit() throws SQLException {
            return delegate.getAutoCommit();
        }

        @Override
        public void commit() throws SQLException {
            delegate.commit();
        }

        @Override
        public void rollback() throws SQLException {
            delegate.rollback();
        }

        @Override
        public boolean isClosed() throws SQLException {
            return delegate.isClosed();
        }

        @Override
        public java.sql.DatabaseMetaData getMetaData() throws SQLException {
            return delegate.getMetaData();
        }

        @Override
        public void setReadOnly(boolean readOnly) throws SQLException {
            delegate.setReadOnly(readOnly);
        }

        @Override
        public boolean isReadOnly() throws SQLException {
            return delegate.isReadOnly();
        }

        @Override
        public void setCatalog(String catalog) throws SQLException {
            delegate.setCatalog(catalog);
        }

        @Override
        public String getCatalog() throws SQLException {
            return delegate.getCatalog();
        }

        @Override
        public void setTransactionIsolation(int level) throws SQLException {
            delegate.setTransactionIsolation(level);
        }

        @Override
        public int getTransactionIsolation() throws SQLException {
            return delegate.getTransactionIsolation();
        }

        @Override
        public java.sql.SQLWarning getWarnings() throws SQLException {
            return delegate.getWarnings();
        }

        @Override
        public void clearWarnings() throws SQLException {
            delegate.clearWarnings();
        }

        @Override
        public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency)
                throws SQLException {
            return delegate.createStatement(resultSetType, resultSetConcurrency);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(
                String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency);
        }

        @Override
        public java.sql.CallableStatement prepareCall(
                String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return delegate.prepareCall(sql, resultSetType, resultSetConcurrency);
        }

        @Override
        public java.util.Map<String, Class<?>> getTypeMap() throws SQLException {
            return delegate.getTypeMap();
        }

        @Override
        public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException {
            delegate.setTypeMap(map);
        }

        @Override
        public void setHoldability(int holdability) throws SQLException {
            delegate.setHoldability(holdability);
        }

        @Override
        public int getHoldability() throws SQLException {
            return delegate.getHoldability();
        }

        @Override
        public java.sql.Savepoint setSavepoint() throws SQLException {
            return delegate.setSavepoint();
        }

        @Override
        public java.sql.Savepoint setSavepoint(String name) throws SQLException {
            return delegate.setSavepoint(name);
        }

        @Override
        public void rollback(java.sql.Savepoint savepoint) throws SQLException {
            delegate.rollback(savepoint);
        }

        @Override
        public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException {
            delegate.releaseSavepoint(savepoint);
        }

        @Override
        public java.sql.Statement createStatement(
                int resultSetType, int resultSetConcurrency, int resultSetHoldability)
                throws SQLException {
            return delegate.createStatement(
                    resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(
                String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
                throws SQLException {
            return delegate.prepareStatement(
                    sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public java.sql.CallableStatement prepareCall(
                String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
                throws SQLException {
            return delegate.prepareCall(
                    sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int autoGeneratedKeys)
                throws SQLException {
            return delegate.prepareStatement(sql, autoGeneratedKeys);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int[] columnIndexes)
                throws SQLException {
            return delegate.prepareStatement(sql, columnIndexes);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, String[] columnNames)
                throws SQLException {
            return delegate.prepareStatement(sql, columnNames);
        }

        @Override
        public java.sql.Clob createClob() throws SQLException {
            return delegate.createClob();
        }

        @Override
        public java.sql.Blob createBlob() throws SQLException {
            return delegate.createBlob();
        }

        @Override
        public java.sql.NClob createNClob() throws SQLException {
            return delegate.createNClob();
        }

        @Override
        public java.sql.SQLXML createSQLXML() throws SQLException {
            return delegate.createSQLXML();
        }

        @Override
        public boolean isValid(int timeout) throws SQLException {
            return delegate.isValid(timeout);
        }

        @Override
        public void setClientInfo(String name, String value)
                throws java.sql.SQLClientInfoException {
            delegate.setClientInfo(name, value);
        }

        @Override
        public void setClientInfo(java.util.Properties properties)
                throws java.sql.SQLClientInfoException {
            delegate.setClientInfo(properties);
        }

        @Override
        public String getClientInfo(String name) throws SQLException {
            return delegate.getClientInfo(name);
        }

        @Override
        public java.util.Properties getClientInfo() throws SQLException {
            return delegate.getClientInfo();
        }

        @Override
        public java.sql.Array createArrayOf(String typeName, Object[] elements)
                throws SQLException {
            return delegate.createArrayOf(typeName, elements);
        }

        @Override
        public java.sql.Struct createStruct(String typeName, Object[] attributes)
                throws SQLException {
            return delegate.createStruct(typeName, attributes);
        }

        @Override
        public void setSchema(String schema) throws SQLException {
            delegate.setSchema(schema);
        }

        @Override
        public String getSchema() throws SQLException {
            return delegate.getSchema();
        }

        @Override
        public void abort(java.util.concurrent.Executor executor) throws SQLException {
            delegate.abort(executor);
        }

        @Override
        public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds)
                throws SQLException {
            delegate.setNetworkTimeout(executor, milliseconds);
        }

        @Override
        public int getNetworkTimeout() throws SQLException {
            return delegate.getNetworkTimeout();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            return method.invoke(delegate, args);
        }
    }
}
