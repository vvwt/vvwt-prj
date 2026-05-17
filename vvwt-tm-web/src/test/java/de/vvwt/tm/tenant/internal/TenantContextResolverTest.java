// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TenantContextResolver} — AC5 (E14S12).
 *
 * <p>Tests are written test-first per DEC-22 (TDD Iron Law) and use hand-rolled stubs for {@link
 * TenantContext} and {@link TenantRegistryPort} to avoid the Spring wiring anti-pattern
 * (testing-anti-patterns-java.md §6). No {@code @SpringBootTest} here.
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC5a — {@code preHandle} binds the correct UUID on the context
 *   <li>AC5b — {@code afterCompletion} closes the scope even when the handler threw
 *   <li>AC5c — null-safety: registry throws (no default) → error propagates, 500 response
 *   <li>AC5d — nested-bind: if inner handler also binds, outer is restored after
 *   <li>AC4 — constructor null-safety
 * </ul>
 *
 * <p>Story: E14S12 — DEC-21/DEC-22/DEC-24.
 */
class TenantContextResolverTest {

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    /**
     * Minimal stub for {@link TenantContext} that records bind/close calls. Delegates to a real
     * {@link ThreadLocalTenantContextImpl} so actual binding semantics are exercised — not just
     * mock interactions (anti-pattern §1).
     */
    static class RecordingTenantContext implements TenantContext {

        private final ThreadLocalTenantContextImpl delegate = new ThreadLocalTenantContextImpl();
        private int bindCount = 0;
        private int closeCount = 0;

        @Override
        public UUID current() {
            return delegate.current();
        }

        @Override
        public Scope bind(UUID tenantId) {
            bindCount++;
            Scope delegateScope = delegate.bind(tenantId);
            return () -> {
                closeCount++;
                delegateScope.close();
            };
        }
    }

    /** Stub for {@link TenantRegistryPort} that returns a configurable default tenant. */
    static class StubRegistryPort implements TenantRegistryPort {

        private UUID defaultTenantId;
        private boolean throwOnGetDefault = false;

        StubRegistryPort(UUID defaultTenantId) {
            this.defaultTenantId = defaultTenantId;
        }

        void configureThrowOnGetDefault() {
            this.throwOnGetDefault = true;
        }

        @Override
        public UUID getDefault() {
            if (throwOnGetDefault) {
                throw new IllegalStateException(
                        "no default tenant registered \u2014 bootstrap not complete");
            }
            return defaultTenantId;
        }

        @Override
        public Optional<TenantRecord> lookup(UUID tenantId) {
            return defaultTenantId.equals(tenantId)
                    ? Optional.of(new TenantRecord(tenantId, "Default (LAN)"))
                    : Optional.empty();
        }

        @Override
        public void register(UUID tenantId, String displayName) {
            this.defaultTenantId = tenantId;
        }

        @Override
        public List<TenantRecord> findAll() {
            return new ArrayList<>(List.of(new TenantRecord(defaultTenantId, "Default (LAN)")));
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static final UUID DEFAULT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private RecordingTenantContext tenantContext;
    private StubRegistryPort registry;
    private TenantContextResolver resolver;

    // Servlet stubs — behaviour under test doesn't depend on them
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final Object handler = new Object();

    @BeforeEach
    void setUp() {
        tenantContext = new RecordingTenantContext();
        registry = new StubRegistryPort(DEFAULT_ID);
        resolver = new TenantContextResolver(tenantContext, registry);
    }

    // -------------------------------------------------------------------------
    // AC4 — constructor null-safety
    // -------------------------------------------------------------------------

    @Test
    void constructorRejectsNullTenantContext() {
        assertThatThrownBy(() -> new TenantContextResolver(null, registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantContext must not be null");
    }

    @Test
    void constructorRejectsNullRegistryPort() {
        assertThatThrownBy(() -> new TenantContextResolver(tenantContext, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantRegistryPort must not be null");
    }

    // -------------------------------------------------------------------------
    // AC5a — preHandle binds the correct UUID
    // -------------------------------------------------------------------------

    /** AC5a: {@code preHandle} must bind the default-tenant UUID to the TenantContext. */
    @Test
    void preHandleBindsDefaultTenantUuid() {
        boolean result = resolver.preHandle(request, response, handler);

        assertThat(result).as("preHandle must return true to continue processing (AC5a)").isTrue();
        assertThat(tenantContext.current())
                .as("preHandle must bind the default-tenant UUID to TenantContext (AC5a)")
                .isEqualTo(DEFAULT_ID);
        assertThat(tenantContext.bindCount)
                .as("preHandle must call bind exactly once")
                .isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // AC5b — afterCompletion closes the scope (normal completion)
    // -------------------------------------------------------------------------

    /** AC5b: {@code afterCompletion} must close the scope when the handler completed normally. */
    @Test
    void afterCompletionClosesScopeOnNormalCompletion() {
        resolver.preHandle(request, response, handler);
        resolver.afterCompletion(request, response, handler, null /* no exception */);

        assertThat(tenantContext.closeCount)
                .as("afterCompletion must close the scope exactly once (AC5b)")
                .isEqualTo(1);
        assertThatThrownBy(tenantContext::current)
                .as("TenantContext must be empty after afterCompletion (scope closed)")
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // AC5b — afterCompletion closes the scope even when the handler threw
    // -------------------------------------------------------------------------

    /**
     * AC5b: {@code afterCompletion} must close the scope even when the handler threw an exception.
     */
    @Test
    void afterCompletionClosesScopeWhenHandlerThrew() {
        resolver.preHandle(request, response, handler);
        Exception handlerException = new RuntimeException("handler failure");

        resolver.afterCompletion(request, response, handler, handlerException);

        assertThat(tenantContext.closeCount)
                .as("afterCompletion must close the scope even when handler threw (AC5b)")
                .isEqualTo(1);
        assertThatThrownBy(tenantContext::current)
                .as("TenantContext must be empty after afterCompletion — even on handler exception")
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // AC5c — no default tenant registered → error propagates from preHandle
    // -------------------------------------------------------------------------

    /**
     * AC5c: when {@link TenantRegistryPort#getDefault()} throws (no default tenant), the exception
     * propagates from {@code preHandle}, producing a 500 response from Spring MVC.
     */
    @Test
    void preHandlePropagatesExceptionWhenNoDefaultTenant() {
        registry.configureThrowOnGetDefault();

        assertThatThrownBy(() -> resolver.preHandle(request, response, handler))
                .as(
                        "preHandle must propagate IllegalStateException when no default tenant is"
                                + " present (AC5c)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no default tenant registered");
    }

    // -------------------------------------------------------------------------
    // AC5d — nested-bind: outer is restored after inner scope closed
    // -------------------------------------------------------------------------

    /**
     * AC5d: if an inner handler also calls {@link TenantContext#bind(UUID)}, the outer tenant (set
     * by this resolver) is restored after the inner scope is closed. The outer bind stack frame is
     * then cleared by {@code afterCompletion}.
     *
     * <p>This verifies nested-bind compatibility per E14S01 AC-NESTED-BIND.
     */
    @Test
    void outerTenantRestoredAfterInnerScopeClosed() {
        UUID innerTenantId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

        // Outer bind (resolver's preHandle)
        resolver.preHandle(request, response, handler);
        assertThat(tenantContext.current()).isEqualTo(DEFAULT_ID);

        // Inner bind (simulates another component binding a different tenant)
        TenantContext.Scope innerScope = tenantContext.bind(innerTenantId);
        assertThat(tenantContext.current())
                .as("Inner bind must override outer for the inner scope")
                .isEqualTo(innerTenantId);

        // Close inner scope — outer should be restored
        innerScope.close();
        assertThat(tenantContext.current())
                .as(
                        "After inner scope closes, outer tenant (DEFAULT_ID) must be restored"
                                + " (AC5d, E14S01 AC-NESTED-BIND)")
                .isEqualTo(DEFAULT_ID);

        // afterCompletion closes the outer scope
        resolver.afterCompletion(request, response, handler, null);
        assertThatThrownBy(tenantContext::current)
                .as("After afterCompletion, TenantContext must be empty (outermost scope closed)")
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // afterCompletion is safe if preHandle was never called (no scope set)
    // -------------------------------------------------------------------------

    /**
     * Safety guard: {@code afterCompletion} is a no-op when no scope was set (e.g., if an upstream
     * interceptor aborted the chain before our preHandle ran).
     */
    @Test
    void afterCompletionIsNoOpWhenNoScopeSet() {
        // Do NOT call preHandle first
        // Should not throw
        resolver.afterCompletion(request, response, handler, null);

        assertThat(tenantContext.bindCount).isEqualTo(0);
        assertThat(tenantContext.closeCount).isEqualTo(0);
    }
}
