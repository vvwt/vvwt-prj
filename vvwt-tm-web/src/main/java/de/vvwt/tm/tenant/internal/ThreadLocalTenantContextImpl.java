// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.TenantContext;
import java.util.ArrayDeque;
import java.util.UUID;

/**
 * {@link ThreadLocal}-backed implementation of {@link TenantContext}.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Each thread maintains its own independent binding stack. Bindings in one thread are never
 * visible to another thread.
 *
 * <h2>Nested-bind contract (AC-NESTED-BIND from E14S01)</h2>
 *
 * <p>Implemented via a stack ({@code ArrayDeque}) per thread:
 *
 * <ul>
 *   <li>{@link #bind(UUID)} pushes onto the stack.
 *   <li>{@link Scope#close()} pops from the stack.
 *   <li>An inner {@code bind} therefore overrides the outer for its scope only; closing the inner
 *       scope restores the outer tenant.
 * </ul>
 *
 * This story (E14S03) does NOT re-open or refine this contract — it implements the interface as
 * specified in E14S01's Javadoc.
 *
 * <h2>Async propagation limitation (documented per AC Wave-1 obligation)</h2>
 *
 * <p>{@code ThreadLocal}-based context does NOT propagate automatically across: {@code @Async}
 * invocations, {@code CompletableFuture} default pool, {@code @Scheduled} tasks, reactive
 * boundaries (Project Reactor), or cross-thread {@code @Transactional(propagation=REQUIRES_NEW)}.
 * Callers crossing these boundaries must capture the tenant UUID via {@link #current()} before
 * crossing, then re-bind inside the async block. This is a Wave-1 documentation obligation;
 * automatic propagation is Wave-2 scope.
 *
 * @see TenantContext
 * @see <a href="../../../../../../../../docs/governance/stories/E14S03.story.md">Story E14S03</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E14S01.story.md">Story E14S01
 *     (AC-NESTED-BIND)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22</a>
 */
public class ThreadLocalTenantContextImpl implements TenantContext {

    /** Per-thread stack of tenant UUID bindings. The top of the stack is the current tenant. */
    private final ThreadLocal<ArrayDeque<UUID>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if no tenant is bound to the current thread. This is a
     *     programming error — callers MUST always be inside a {@link Scope}.
     */
    @Override
    public UUID current() {
        UUID top = stack.get().peek();
        if (top == null) {
            throw new IllegalStateException(
                    "No tenant is bound to the current thread. Callers must establish a"
                        + " TenantContext.Scope via bind(tenantId) before invoking current(). See"
                        + " TenantContext Javadoc for the transactional semantics constraint (bind"
                        + " before @Transactional entry).");
        }
        return top;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pushes {@code tenantId} onto the per-thread stack. Closing the returned {@link Scope} pops
     * it, restoring the previous tenant (or leaving the stack empty if there was no outer binding).
     *
     * @throws IllegalArgumentException if {@code tenantId} is {@code null}
     */
    @Override
    public TenantContext.Scope bind(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        stack.get().push(tenantId);
        return () -> stack.get().poll();
    }
}
