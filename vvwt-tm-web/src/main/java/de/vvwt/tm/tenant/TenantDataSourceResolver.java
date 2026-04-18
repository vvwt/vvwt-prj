package de.vvwt.tm.tenant;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Resolves the per-tenant {@link DataSource} for a given tenant identifier.
 *
 * <h2>Intended consumer</h2>
 * <p>{@code AbstractRoutingDataSource} implementations (E14S03) that need to route
 * database connections to the correct per-tenant H2 file (DEC-20). Consumers
 * declare {@code @ApplicationModule(allowedDependencies = "tenant")} and inject
 * this interface.
 *
 * <h2>Thread-safety</h2>
 * <p>Implementations MUST be thread-safe. {@code resolve()} may be called from multiple
 * threads concurrently during normal request processing. Implementations typically hold
 * a concurrent registry of tenant → DataSource mappings.
 *
 * <h2>Transactional semantics</h2>
 * <p>{@code resolve()} is called BEFORE a transaction is started — at the point where
 * Spring's {@code AbstractRoutingDataSource} selects the routing key and acquires a
 * connection. Implementations MUST NOT open transactions internally. The method is a
 * pure lookup and must complete without side effects.
 *
 * <h2>Resolution semantics for unknown tenants (AC3 / AC6)</h2>
 * <p>If the given {@code tenantId} is not registered in the resolver:
 * <ul>
 *   <li>MUST throw {@link UnknownTenantException} immediately.</li>
 *   <li>MUST NOT return {@code null}.</li>
 *   <li>MUST NOT return a shared or fallback {@link DataSource}.</li>
 *   <li>MUST NOT return a {@link DataSource} that fails lazily (e.g., one that throws on
 *       {@code getConnection()}) — the exception MUST be thrown here, at resolution time,
 *       for fail-fast behaviour.</li>
 * </ul>
 * This is distinct from the {@link TenantRegistryPort#lookup(UUID)} contract, which returns
 * {@link java.util.Optional#empty()} for an unknown tenant. {@code resolve()} is
 * DataSource-oriented and must never silently provide a shared connection.
 *
 * <h2>Async propagation surfaces (Wave-1 documentation)</h2>
 * <p>The tenant identifier passed to {@code resolve()} typically originates from
 * {@link TenantContext#current()}. {@link TenantContext} does NOT propagate automatically
 * across {@code @Async}, {@code CompletableFuture} default-pool, {@code @Scheduled},
 * reactive boundaries, or cross-thread {@code @Transactional(propagation=REQUIRES_NEW)}.
 * Callers crossing those boundaries must capture the UUID via {@link TenantContext#current()}
 * and re-bind it before invoking {@code resolve()}.
 *
 * @see TenantContext
 * @see TenantRegistryPort
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E14S01.story.md">Story E14S01</a>
 */
public interface TenantDataSourceResolver {

    /**
     * Returns the {@link DataSource} registered for the given tenant.
     *
     * @param tenantId the tenant whose DataSource is requested; must not be {@code null}
     * @return the per-tenant {@link DataSource}; never {@code null}
     * @throws UnknownTenantException if no DataSource is registered for {@code tenantId}.
     *         Fail-fast: the exception is thrown at resolution time, not deferred to
     *         connection acquisition (AC6).
     */
    DataSource resolve(UUID tenantId);

    /**
     * Thrown by {@link TenantDataSourceResolver#resolve(UUID)} when the requested tenant
     * is not registered in the resolver.
     *
     * <p>This is a fast-fail exception — it is raised at resolution time, before any
     * connection attempt is made. The message includes the unknown tenant UUID to aid
     * debugging (AC6).
     */
    class UnknownTenantException extends RuntimeException {

        private final UUID tenantId;

        /**
         * Constructs an {@code UnknownTenantException} for the given tenant UUID.
         *
         * @param tenantId the UUID that was not found in the registry
         */
        public UnknownTenantException(UUID tenantId) {
            super("No DataSource is registered for tenant '" + tenantId
                    + "'. Ensure the tenant has been registered via TenantRegistryPort "
                    + "before routing data-access operations to it.");
            this.tenantId = tenantId;
        }

        /**
         * Returns the tenant UUID that could not be resolved.
         *
         * @return the unresolvable tenant UUID; never {@code null}
         */
        public UUID getTenantId() {
            return tenantId;
        }
    }
}
