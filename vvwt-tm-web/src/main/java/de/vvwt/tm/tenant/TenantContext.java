package de.vvwt.tm.tenant;

import java.util.UUID;

/**
 * Provides access to the current tenant identifier bound to the calling thread.
 *
 * <h2>Intended consumer</h2>
 * <p>Any bounded context that needs to scope a data-access operation to the current tenant.
 * Consumers declare {@code @ApplicationModule(allowedDependencies = "tenant")} and inject
 * {@code TenantContext} via constructor injection. Usage example:
 * <pre>{@code
 * try (TenantContext.Scope scope = tenantContext.bind(tenantId)) {
 *     // all data-access within this block is scoped to tenantId
 * }
 * }</pre>
 *
 * <h2>Thread-safety</h2>
 * <p>Implementations MUST be thread-safe. The binding is per-thread (typically backed by a
 * {@code ThreadLocal}). Each thread maintains its own binding stack; bindings in one thread
 * are never visible to another thread.
 *
 * <h2>Transactional semantics</h2>
 * <p>The tenant binding MUST be established BEFORE a {@code @Transactional} boundary is
 * entered. DataSource routing (via {@code AbstractRoutingDataSource}) reads the binding
 * at connection-acquisition time, which is the moment a transaction begins. Binding after
 * transaction start has no effect on the active connection.
 *
 * <h2>Nested-bind contract (AC-NESTED-BIND)</h2>
 * <p>{@link #bind(UUID)} MUST support re-entrant / nested binding. An inner {@code bind}
 * overrides the outer for the inner's scope only; closing the inner scope restores the outer.
 * Downstream stories (E14S03+) consume this contract and MUST NOT re-open it.
 *
 * <h2>Async propagation surfaces — Wave-1 documentation obligation</h2>
 * <p>{@code ThreadLocal}-based implementations do NOT automatically propagate across:
 * <ul>
 *   <li>{@code @Async} method invocations (Spring executes on a separate thread)</li>
 *   <li>{@code CompletableFuture.supplyAsync(…)} using the default {@code ForkJoinPool} common pool</li>
 *   <li>{@code @Scheduled} tasks (executor thread, not the caller thread)</li>
 *   <li>Reactive {@code Mono}/{@code Flux} boundaries (Project Reactor uses its own scheduler)</li>
 *   <li>Cross-thread {@code @Transactional(propagation=REQUIRES_NEW)} on a {@code TaskExecutor}</li>
 * </ul>
 * Each listed surface requires manual context-capture-and-restore:
 * capture the UUID via {@code current()} before crossing the boundary,
 * then re-bind inside the async block using {@code bind(capturedId)}.
 * This is a Wave-1 documentation obligation; automatic propagation support is Wave-2 scope.
 *
 * @see TenantDataSourceResolver
 * @see TenantRegistryPort
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E14S01.story.md">Story E14S01</a>
 */
public interface TenantContext {

    /**
     * Returns the tenant identifier bound to the current thread.
     *
     * @return the current tenant's UUID; never {@code null}
     * @throws IllegalStateException if no tenant is bound to the current thread.
     *         This is a programming error — callers MUST always be inside a {@link Scope}
     *         before invoking {@code current()}. This method NEVER returns {@code null}
     *         and NEVER silently returns a shared or default DataSource (AC5).
     */
    UUID current();

    /**
     * Binds the given tenant identifier to the current thread for the duration of the returned
     * {@link Scope}. Intended for use in a try-with-resources block.
     *
     * <p>Nested calls are supported: an inner {@code bind} overrides the outer for its scope;
     * closing the inner scope restores the outer (AC-NESTED-BIND).
     *
     * @param tenantId the tenant to bind; must not be {@code null}
     * @return a {@link Scope} that, when closed, unbinds this tenant (or restores the outer tenant)
     * @throws IllegalArgumentException if {@code tenantId} is {@code null}
     */
    Scope bind(UUID tenantId);

    /**
     * A scope that, when closed, removes the tenant binding (or restores the outer binding).
     *
     * <p>Intended for use in try-with-resources:
     * <pre>{@code
     * try (TenantContext.Scope scope = tenantContext.bind(tenantId)) {
     *     // scoped work
     * }
     * }</pre>
     */
    interface Scope extends AutoCloseable {

        /**
         * Unbinds the current tenant or restores the outer tenant if nested binds were used.
         *
         * <p>Implementations MUST NOT declare checked exceptions; callers should not have to
         * handle them in try-with-resources blocks.
         */
        @Override
        void close();
    }
}
