// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant;

import java.util.UUID;

/**
 * Provides access to the current location identifier bound to the calling thread.
 *
 * <h2>Intended consumer</h2>
 *
 * <p>Any bounded context that needs to scope an operation to the current venue location (e.g., a
 * WebSocket session handler that must distinguish court-specific vs. overview-mode display
 * behaviour). Consumers declare {@code @ApplicationModule(allowedDependencies = "tenant")} and
 * inject {@code LocationContext} via constructor injection.
 *
 * <h2>Relationship to {@link TenantContext}</h2>
 *
 * <p>{@code LocationContext} mirrors the shape of {@link TenantContext}: it offers {@link
 * #current()}, {@link #bind(UUID)}, and nested-bind semantics via the {@link Scope} inner
 * interface. The binding is per-thread (backed by a {@code ThreadLocal}).
 *
 * <h2>Wave-1 usage contract (DEC-24 D2)</h2>
 *
 * <p>At WebSocket handshake time, the {@code DeviceTokenHandshakeInterceptor} binds this context to
 * the device's {@code location_id} when the device has an assigned location, OR sets a
 * session-level "overview mode" marker and leaves this context UNBOUND when the device has {@code
 * location_id = NULL}. Handlers that need location-specific data MUST check whether a location
 * context is bound before calling {@link #current()}.
 *
 * <h2>Async propagation surfaces — Wave-1 documentation obligation</h2>
 *
 * <p>{@code ThreadLocal}-based implementations do NOT automatically propagate across:
 *
 * <ul>
 *   <li>{@code @Async} method invocations (Spring executes on a separate thread)
 *   <li>{@code CompletableFuture.supplyAsync(…)} using the default {@code ForkJoinPool} common pool
 *   <li>{@code @Scheduled} tasks (executor thread, not the caller thread)
 *   <li>Reactive {@code Mono}/{@code Flux} boundaries (Project Reactor uses its own scheduler)
 *   <li>Cross-thread {@code @Transactional(propagation=REQUIRES_NEW)} on a {@code TaskExecutor}
 * </ul>
 *
 * Each listed surface requires manual context-capture-and-restore: capture the UUID via {@code
 * current()} before crossing the boundary, then re-bind inside the async block using {@code
 * bind(capturedId)}. This is a Wave-1 documentation obligation; automatic propagation support is
 * Wave-2 scope.
 *
 * <h2>Wave-2 evolution note</h2>
 *
 * <p>DEC-24 acknowledges that a Wave-2 Discovery session may move {@code LocationContext} to its
 * own bounded context (e.g., a {@code location} module). For Wave 1, it lives beside {@link
 * TenantContext} in the {@code tenant} root package for locality.
 *
 * @see TenantContext
 * @see TenantRegistryPort
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E14S09.story.md">Story
 *     E14S09</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-24.md">DEC-24</a>
 */
public interface LocationContext {

    /**
     * Returns the location identifier bound to the current thread.
     *
     * @return the current location's UUID; never {@code null}
     * @throws IllegalStateException if no location is bound to the current thread. Callers MUST
     *     always be inside a {@link Scope} before invoking {@code current()}. This method NEVER
     *     returns {@code null} and NEVER silently falls back to a default.
     */
    UUID current();

    /**
     * Binds the given location identifier to the current thread for the duration of the returned
     * {@link Scope}. Intended for use in a try-with-resources block.
     *
     * <p>Nested calls are supported: an inner {@code bind} overrides the outer for its scope;
     * closing the inner scope restores the outer (AC3 nested-bind contract, mirrors {@link
     * TenantContext} AC-NESTED-BIND from E14S01).
     *
     * @param locationId the location to bind; must not be {@code null}
     * @return a {@link Scope} that, when closed, unbinds this location (or restores the outer
     *     location)
     * @throws IllegalArgumentException if {@code locationId} is {@code null}
     */
    Scope bind(UUID locationId);

    /**
     * A scope that, when closed, removes the location binding (or restores the outer binding).
     *
     * <p>Intended for use in try-with-resources:
     *
     * <pre>{@code
     * try (LocationContext.Scope scope = locationContext.bind(locationId)) {
     *     // scoped work
     * }
     * }</pre>
     */
    interface Scope extends AutoCloseable {

        /**
         * Unbinds the current location or restores the outer location if nested binds were used.
         *
         * <p>Implementations MUST NOT declare checked exceptions; callers should not have to handle
         * them in try-with-resources blocks.
         */
        @Override
        void close();
    }
}
