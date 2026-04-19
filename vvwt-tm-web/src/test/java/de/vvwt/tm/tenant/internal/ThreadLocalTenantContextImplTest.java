package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ThreadLocalTenantContextImpl}.
 *
 * <p>Tests are written TDD-first (DEC-22). This file was committed before the implementation class
 * existed (AC1 red-phase proof).
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC1 — test-first: committed before implementation (RED verified)
 *   <li>AC3 — unbound current() → IllegalStateException (not NPE, not fallback DS)
 *   <li>AC4 — context leak: after exception in scoped work, context clears to unbound
 *   <li>AC5 — ApplicationModulesTest remains green (structural — verified separately)
 *   <li>AC6 — internal placement: ThreadLocalTenantContextImpl in tenant.internal (structural)
 *   <li>AC7 — AC-NESTED-BIND contract: nested bind/close restores outer
 * </ul>
 *
 * <p>Story: E14S03 — DEC-10/DEC-14/DEC-20/DEC-21/DEC-22.
 *
 * <p>@SuppressWarnings("try") at class level: all try-with-resources blocks in this test class use
 * TenantContext.Scope for its RAII side-effect (bind+auto-restore) — the scope variable is
 * intentionally unreferenced in the body. This is the correct usage of AutoCloseable context
 * guards. (E18S01 / DEC-29)
 */
@SuppressWarnings("try")
class ThreadLocalTenantContextImplTest {

    // -------------------------------------------------------------------------
    // AC3 — current() throws IllegalStateException when no tenant is bound
    // -------------------------------------------------------------------------

    /**
     * AC3: {@code current()} MUST throw {@link IllegalStateException} — not return null, not return
     * a fallback DataSource silently.
     */
    @Test
    void currentThrowsIllegalStateExceptionWhenNoBind() {
        TenantContext ctx = new ThreadLocalTenantContextImpl();

        assertThatThrownBy(ctx::current)
                .as("current() must throw IllegalStateException when no tenant is bound (AC3)")
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Basic bind/current/close
    // -------------------------------------------------------------------------

    @Test
    void bindMakesTenantAvailableViaCurrent() {
        TenantContext ctx = new ThreadLocalTenantContextImpl();
        UUID tenantId = UUID.fromString("11111111-0000-0000-0000-000000000001");

        try (TenantContext.Scope ignored = ctx.bind(tenantId)) {
            assertThat(ctx.current())
                    .as("current() must return the bound tenant UUID")
                    .isEqualTo(tenantId);
        }
    }

    @Test
    void currentThrowsAfterScopeClose() {
        TenantContext ctx = new ThreadLocalTenantContextImpl();
        UUID tenantId = UUID.randomUUID();

        TenantContext.Scope scope = ctx.bind(tenantId);
        assertThat(ctx.current()).isEqualTo(tenantId);
        scope.close();

        assertThatThrownBy(ctx::current)
                .as("current() must throw after scope.close() — no residue allowed")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bindRejectsNullTenantId() {
        TenantContext ctx = new ThreadLocalTenantContextImpl();

        assertThatThrownBy(() -> ctx.bind(null))
                .as("bind(null) must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // AC7 — AC-NESTED-BIND: nested bind overrides outer; close restores outer
    // -------------------------------------------------------------------------

    /**
     * AC7: The {@link TenantContext} implementation honours AC-NESTED-BIND from E14S01. Inner
     * {@code bind} overrides the outer for the inner's scope only; closing the inner scope restores
     * the outer. E14S03 does NOT re-open this contract — it implements it.
     */
    @Test
    void nestedBindOverridesOuterAndRestoresOnClose() {
        TenantContext ctx = new ThreadLocalTenantContextImpl();
        UUID outer = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID inner = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

        try (TenantContext.Scope outerScope = ctx.bind(outer)) {
            assertThat(ctx.current()).isEqualTo(outer);

            try (TenantContext.Scope innerScope = ctx.bind(inner)) {
                assertThat(ctx.current())
                        .as("Inner bind must override outer for its scope (AC-NESTED-BIND / AC7)")
                        .isEqualTo(inner);
            }

            assertThat(ctx.current())
                    .as("After inner scope closes, outer must be restored (AC-NESTED-BIND / AC7)")
                    .isEqualTo(outer);
        }

        assertThatThrownBy(ctx::current)
                .as("After all scopes closed, current() must throw (AC3)")
                .isInstanceOf(IllegalStateException.class);
    }

    /** AC7: Three levels of nesting — outer→middle→inner. Each level restores correctly. */
    @Test
    void tripleNestedBindRestoresEachLevelOnClose() {
        TenantContext ctx = new ThreadLocalTenantContextImpl();
        UUID t1 = UUID.fromString("aaaaaaaa-0001-0001-0001-000000000001");
        UUID t2 = UUID.fromString("bbbbbbbb-0002-0002-0002-000000000002");
        UUID t3 = UUID.fromString("cccccccc-0003-0003-0003-000000000003");

        try (TenantContext.Scope s1 = ctx.bind(t1)) {
            assertThat(ctx.current()).isEqualTo(t1);
            try (TenantContext.Scope s2 = ctx.bind(t2)) {
                assertThat(ctx.current()).isEqualTo(t2);
                try (TenantContext.Scope s3 = ctx.bind(t3)) {
                    assertThat(ctx.current()).isEqualTo(t3);
                }
                assertThat(ctx.current()).isEqualTo(t2);
            }
            assertThat(ctx.current()).isEqualTo(t1);
        }

        assertThatThrownBy(ctx::current).isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // AC4 — context leak: after exception inside scope, context clears
    // -------------------------------------------------------------------------

    /**
     * AC4: After a tenant-scoped operation throws, {@code current()} returns to the unbound state.
     * No context leaks across requests/operations.
     */
    @Test
    void contextClearsAfterExceptionInScopedWork() {
        TenantContext ctx = new ThreadLocalTenantContextImpl();
        UUID tenantId = UUID.fromString("dddddddd-0001-0001-0001-000000000001");

        try {
            try (TenantContext.Scope ignored = ctx.bind(tenantId)) {
                assertThat(ctx.current()).isEqualTo(tenantId);
                throw new RuntimeException("simulated failure inside scoped work");
            }
        } catch (RuntimeException ignored) {
            // expected
        }

        assertThatThrownBy(ctx::current)
                .as("After exception in scoped work, current() must throw (AC4 context-leak)")
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Thread isolation — bindings in one thread are not visible to another
    // -------------------------------------------------------------------------

    @Test
    void threadLocalBindingNotVisibleInAnotherThread() throws InterruptedException {
        TenantContext ctx = new ThreadLocalTenantContextImpl();
        UUID tenantId = UUID.randomUUID();

        try (TenantContext.Scope ignored = ctx.bind(tenantId)) {
            // In a separate thread, current() must throw — ThreadLocal is per-thread
            AssertionError[] result = new AssertionError[1];
            Thread thread =
                    new Thread(
                            () -> {
                                try {
                                    ctx.current(); // must throw
                                    result[0] =
                                            new AssertionError(
                                                    "Expected IllegalStateException — not thrown");
                                } catch (IllegalStateException e) {
                                    // expected — binding is not visible across threads
                                }
                            });
            thread.start();
            thread.join(3000);

            assertThat(result[0])
                    .as("Other thread must not see this thread's tenant binding")
                    .isNull();
        }
    }
}
