package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import java.util.Collections;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * {@link AbstractRoutingDataSource} that routes every connection to the per-tenant H2 file
 * DataSource, keyed on {@link TenantContext#current()}.
 *
 * <h2>Routing key (DEC-20)</h2>
 *
 * <p>{@link #determineCurrentLookupKey()} returns the {@link UUID} from {@link
 * TenantContext#current()}. If no tenant is bound, the {@link IllegalStateException} from {@code
 * TenantContext} propagates immediately — the routing DataSource NEVER silently falls back to a
 * shared or default DataSource (AC3).
 *
 * <h2>Target DataSource resolution</h2>
 *
 * <p>{@link #determineTargetDataSource()} delegates to {@link
 * TenantDataSourceResolver#resolve(UUID)}, which throws {@link
 * TenantDataSourceResolver.UnknownTenantException} fail-fast for unknown tenants (AC3). The Spring
 * framework's standard map-lookup in the superclass is bypassed in favour of this resolver delegate
 * for deterministic fail-fast behaviour.
 *
 * <h2>No per-tenant EntityManagerFactory (DEC-20)</h2>
 *
 * <p>All JPA metadata is shared; per-tenant isolation is connection-level only. No new {@code
 * EntityManagerFactory} is introduced by this class or its configuration (AC8).
 *
 * <h2>Internal placement (AC6)</h2>
 *
 * <p>This class lives in {@code de.vvwt.tm.tenant.internal} and MUST NOT be imported by any class
 * outside the {@code tenant} module. Other modules interact with tenancy only through the public
 * API surface: {@link TenantContext}, {@link TenantDataSourceResolver}, {@link
 * de.vvwt.tm.tenant.TenantRegistryPort}.
 *
 * <h2>{@code @Primary} activation — E14S11 (AC9)</h2>
 *
 * <p>{@code @Primary} was added to the {@link TenantContextConfiguration#routingTenantDataSource()}
 * {@code @Bean} declaration in E14S11. From that commit onwards, all consumers of the {@link
 * DataSource} abstraction in the Spring context route through per-tenant context.
 *
 * @see TenantContext
 * @see TenantDataSourceResolver
 * @see TenantContextConfiguration
 * @see <a href="../../../../../../../../docs/governance/stories/E14S03.story.md">Story E14S03</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E14S11.story.md">Story E14S11
 *     (@Primary activation)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22</a>
 */
public class RoutingTenantDataSource extends AbstractRoutingDataSource {

    private final TenantContext tenantContext;
    private final TenantDataSourceResolver tenantDataSourceResolver;

    /**
     * Constructs a {@code RoutingTenantDataSource}.
     *
     * @param tenantContext the tenant context from which the routing key is read; must not be
     *     {@code null}
     * @param tenantDataSourceResolver resolves the per-tenant {@link DataSource} for a given UUID;
     *     must not be {@code null}
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public RoutingTenantDataSource(
            TenantContext tenantContext, TenantDataSourceResolver tenantDataSourceResolver) {
        if (tenantContext == null) {
            throw new IllegalArgumentException("tenantContext must not be null");
        }
        if (tenantDataSourceResolver == null) {
            throw new IllegalArgumentException("tenantDataSourceResolver must not be null");
        }
        this.tenantContext = tenantContext;
        this.tenantDataSourceResolver = tenantDataSourceResolver;
    }

    /**
     * Satisfies the {@link AbstractRoutingDataSource#afterPropertiesSet()} lifecycle contract.
     *
     * <p>{@link AbstractRoutingDataSource#afterPropertiesSet()} calls {@link
     * AbstractRoutingDataSource#initialize()}, which requires {@code targetDataSources} to be
     * non-null. We set an empty map before delegating: the map is never consulted because {@link
     * #determineTargetDataSource()} is fully overridden to delegate to {@link
     * TenantDataSourceResolver} (E14S11).
     */
    @Override
    public void afterPropertiesSet() {
        setTargetDataSources(Collections.emptyMap());
        super.afterPropertiesSet();
    }

    /**
     * Returns the routing key for the current request — the tenant UUID bound to this thread.
     *
     * <p>Propagates {@link IllegalStateException} from {@link TenantContext#current()} unchanged
     * when no tenant is bound. This is the correct fail-fast behaviour (AC3): the exception is
     * thrown at routing time (connection acquisition), not silently suppressed with a fallback.
     *
     * @return the current tenant's {@link UUID}; never {@code null}
     * @throws IllegalStateException if no tenant is bound to the current thread (AC3)
     */
    @Override
    public Object determineCurrentLookupKey() {
        // Propagates IllegalStateException if no tenant is bound (AC3)
        return tenantContext.current();
    }

    /**
     * Resolves the target {@link DataSource} for the current tenant.
     *
     * <p>Delegates to {@link TenantDataSourceResolver#resolve(UUID)} rather than the superclass's
     * map-lookup, providing deterministic fail-fast behaviour for unknown tenants.
     *
     * @return the per-tenant {@link DataSource}; never {@code null}
     * @throws IllegalStateException if no tenant is bound (propagated from {@link
     *     #determineCurrentLookupKey()})
     * @throws TenantDataSourceResolver.UnknownTenantException if the tenant is not registered (AC3)
     */
    @Override
    protected DataSource determineTargetDataSource() {
        UUID tenantId = (UUID) determineCurrentLookupKey();
        return tenantDataSourceResolver.resolve(tenantId);
    }
}
