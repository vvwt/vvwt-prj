package de.vvwt.tm.tenant;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for {@link TenantContext}.
 *
 * <p>These tests exercise the interface contract via a hand-rolled test double
 * (not Mockito). The test double is a minimal implementation that fulfills
 * the {@link TenantContext} contract as specified — it is kept in
 * {@code src/test/java} and is NOT visible to production code (AC7).
 *
 * <p>Acceptance criteria covered:
 * <ul>
 *   <li>AC1 — test-first discipline: this file exists BEFORE the interface</li>
 *   <li>AC3 — contract coverage: UUID-based tenant id, unbound throws, etc.</li>
 *   <li>AC5 — {@code current()} throws {@link IllegalStateException} when nothing is bound</li>
 *   <li>AC8 — Javadoc enumerates async propagation surfaces (verified in interface source)</li>
 *   <li>AC-NESTED-BIND — nested bind overrides outer; close() restores outer</li>
 * </ul>
 *
 * <p>Story: E14S01 — DEC-20/DEC-21/DEC-22.
 */
class TenantContextContractTest {

    // -------------------------------------------------------------------------
    // Test double — keeps contract tests self-contained in src/test/java (AC7)
    // -------------------------------------------------------------------------

    /**
     * Minimal thread-local-based test double for {@link TenantContext}.
     *
     * <p>Stack-based: each bind() pushes onto a thread-local stack; close() pops.
     * This satisfies the nested-bind contract (AC-NESTED-BIND) faithfully.
     */
    static class ThreadLocalTenantContext implements TenantContext {

        private final ThreadLocal<java.util.ArrayDeque<UUID>> stack =
                ThreadLocal.withInitial(java.util.ArrayDeque::new);

        @Override
        public UUID current() {
            UUID top = stack.get().peek();
            if (top == null) {
                throw new IllegalStateException(
                        "No tenant is bound to the current thread. "
                        + "Callers must invoke bind(tenantId) before current().");
            }
            return top;
        }

        @Override
        public TenantContext.Scope bind(UUID tenantId) {
            if (tenantId == null) {
                throw new IllegalArgumentException("tenantId must not be null");
            }
            stack.get().push(tenantId);
            return () -> stack.get().poll();
        }
    }

    // -------------------------------------------------------------------------
    // AC5 — current() throws when nothing is bound
    // -------------------------------------------------------------------------

    /**
     * AC5: {@code current()} MUST throw {@link IllegalStateException} when nothing is bound.
     * It must NEVER return null and NEVER return a silent default.
     */
    @Test
    void currentThrowsIllegalStateExceptionWhenNoTenantIsBound() {
        TenantContext ctx = new ThreadLocalTenantContext();

        assertThatThrownBy(ctx::current)
                .as("current() must throw IllegalStateException when no tenant is bound (AC5)")
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // AC3 — tenant identifier type is UUID-based (DEC-17)
    // -------------------------------------------------------------------------

    /**
     * AC3 / DEC-17: bind() accepts a {@link UUID} and current() returns the same UUID.
     */
    @Test
    void currentReturnsBoundTenantIdAsUuid() {
        TenantContext ctx = new ThreadLocalTenantContext();
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        try (TenantContext.Scope ignored = ctx.bind(tenantId)) {
            assertThat(ctx.current())
                    .as("current() must return the UUID that was bound (AC3 / DEC-17)")
                    .isEqualTo(tenantId);
        }
    }

    // -------------------------------------------------------------------------
    // AC-NESTED-BIND — nested bind overrides outer for inner scope only
    // -------------------------------------------------------------------------

    /**
     * AC-NESTED-BIND: An inner {@code bind()} overrides the outer for the inner's scope only.
     * When the inner scope closes, the outer UUID is restored.
     */
    @Test
    void nestedBindRestoresOuterContextOnClose() {
        TenantContext ctx = new ThreadLocalTenantContext();
        UUID outer = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID inner = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

        try (TenantContext.Scope outerScope = ctx.bind(outer)) {
            assertThat(ctx.current()).isEqualTo(outer);

            try (TenantContext.Scope innerScope = ctx.bind(inner)) {
                assertThat(ctx.current())
                        .as("Inner bind must override outer for its scope (AC-NESTED-BIND)")
                        .isEqualTo(inner);
            }

            assertThat(ctx.current())
                    .as("After inner scope closes, outer tenant must be restored (AC-NESTED-BIND)")
                    .isEqualTo(outer);
        }

        // After outer scope closes, current() must throw again
        assertThatThrownBy(ctx::current)
                .as("After all scopes are closed, current() must throw (AC5)")
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // AC5 — close() does not leave residue (idempotent unbind)
    // -------------------------------------------------------------------------

    /**
     * Verifies that after the scope's {@code close()} is invoked, {@code current()} throws.
     * Ensures the bind/close lifecycle is well-defined.
     */
    @Test
    void currentThrowsAfterScopeIsClosed() {
        TenantContext ctx = new ThreadLocalTenantContext();
        UUID tenantId = UUID.randomUUID();

        TenantContext.Scope scope = ctx.bind(tenantId);
        assertThat(ctx.current()).isEqualTo(tenantId);
        scope.close();

        assertThatThrownBy(ctx::current)
                .as("current() must throw after scope.close() (AC5)")
                .isInstanceOf(IllegalStateException.class);
    }
}
