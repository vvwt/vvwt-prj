package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.LocationContext;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * {@link ThreadLocal}-backed implementation of {@link LocationContext}.
 *
 * <h2>Thread safety</h2>
 * <p>Each thread maintains its own independent binding stack.
 * Bindings in one thread are never visible to another thread.
 *
 * <h2>Nested-bind contract (AC3, E14S09)</h2>
 * <p>Implemented via a stack ({@code ArrayDeque}) per thread, mirroring
 * the {@link ThreadLocalTenantContextImpl} pattern established in E14S03:
 * <ul>
 *   <li>{@link #bind(UUID)} pushes onto the stack.</li>
 *   <li>{@link Scope#close()} pops from the stack.</li>
 *   <li>An inner {@code bind} therefore overrides the outer for its scope only;
 *       closing the inner scope restores the outer location.</li>
 * </ul>
 *
 * <h2>Async propagation limitation (documented per E14S09 Wave-1 obligation)</h2>
 * <p>{@code ThreadLocal}-based context does NOT propagate automatically across:
 * {@code @Async} invocations, {@code CompletableFuture} default pool, {@code @Scheduled} tasks,
 * reactive boundaries (Project Reactor), or cross-thread
 * {@code @Transactional(propagation=REQUIRES_NEW)}. This is a Wave-1 documentation obligation;
 * automatic propagation is Wave-2 scope. See {@link LocationContext} Javadoc for the full list.
 *
 * @see LocationContext
 * @see ThreadLocalTenantContextImpl
 * @see <a href="../../../../../../../../docs/governance/stories/E14S09.story.md">Story E14S09</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-24.md">DEC-24</a>
 */
public class ThreadLocalLocationContextImpl implements LocationContext {

    /** Per-thread stack of location UUID bindings. The top of the stack is the current location. */
    private final ThreadLocal<ArrayDeque<UUID>> stack =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if no location is bound to the current thread.
     *         This is a programming error — callers MUST always be inside a {@link Scope}.
     */
    @Override
    public UUID current() {
        UUID top = stack.get().peek();
        if (top == null) {
            throw new IllegalStateException(
                    "No location is bound to the current thread. "
                    + "Callers must establish a LocationContext.Scope via bind(locationId) "
                    + "before invoking current(). See LocationContext Javadoc.");
        }
        return top;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pushes {@code locationId} onto the per-thread stack. Closing the returned
     * {@link Scope} pops it, restoring the previous location (or leaving the stack empty
     * if there was no outer binding).
     *
     * @throws IllegalArgumentException if {@code locationId} is {@code null}
     */
    @Override
    public LocationContext.Scope bind(UUID locationId) {
        if (locationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
        stack.get().push(locationId);
        return () -> stack.get().poll();
    }
}
