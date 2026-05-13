package de.vvwt.tm.tenant.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * HikariCP-related feature-flag properties for per-tenant DataSource configuration (E55S10).
 *
 * <h2>H-1 Stopgap (Stair 2)</h2>
 *
 * <p>When {@code tm.hikari.single-writer-per-tenant=true}, each per-tenant {@link
 * javax.sql.DataSource} is wrapped in a {@link com.zaxxer.hikari.HikariDataSource} with {@code
 * maximumPoolSize=1}. This serializes all per-tenant H2 file connections — at most one active JDBC
 * connection per tenant at any time. Eliminates H-1b (raw H2 concurrent connection race) and H-2,
 * H-4 hypothesis classes simultaneously, at the cost of per-tenant request serialization.
 *
 * <p>Per AC-ERROR-HANDLING-PRODUCER-NOT-FOUND-STAIR-STEP-ESCALATE Stair 2: this stopgap is
 * implemented regardless of structural IT verdict. If the stopgap eliminates the operator-observed
 * bug (empirically confirmed by operator in E55S11), it ships as the structural fix.
 *
 * @see TenantFileRegistryDataSourceResolver
 * @since E55S10
 */
@Component
@ConfigurationProperties(prefix = "tm.hikari")
public class TmHikariProperties {

    /**
     * Wrap each per-tenant H2 DataSource in a HikariDataSource with maximumPoolSize=1 to serialize
     * all per-tenant JDBC connections. Default {@code false} (production-safe; raw JdbcDataSource).
     *
     * <p>Per AC-ERROR-HANDLING-MAXPOOLSIZE-1-FALLBACK: if H-1b is confirmed and this flag is
     * structurally sound, it ships as the H-1b fix. If latency is unacceptable, T-1 PostgreSQL
     * migration is the escalation path.
     */
    private boolean singleWriterPerTenant = false;

    /** Returns whether single-writer-per-tenant mode is enabled. */
    public boolean isSingleWriterPerTenant() {
        return singleWriterPerTenant;
    }

    /** Sets single-writer-per-tenant mode. */
    public void setSingleWriterPerTenant(boolean singleWriterPerTenant) {
        this.singleWriterPerTenant = singleWriterPerTenant;
    }
}
