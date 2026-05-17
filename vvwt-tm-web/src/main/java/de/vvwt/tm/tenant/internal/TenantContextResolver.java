// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Resolves the tenant context for each incoming HTTP request using the new {@code tenant::api}
 * ({@link TenantRegistryPort#getDefault()}).
 *
 * <p>Replaces the legacy {@code de.vvwt.tm.domain.repo.DefaultTenantContextResolver}, which was
 * deleted as part of E14S12 per DEC-24 (final consumer migration away from {@code
 * DefaultTenantProvider}).
 *
 * <h2>Relocation rationale (DEC-21)</h2>
 *
 * <p>The legacy resolver lived in {@code de.vvwt.tm.domain.repo} to access the package-private
 * {@code TenantContext#set(UUID)} and {@code #clear()} methods. The new {@link TenantContext}
 * public API (from E14S01) exposes {@link TenantContext#bind(UUID)} returning a {@link
 * TenantContext.Scope} — no package-private access is needed. This resolver belongs in {@code
 * tenant.internal}: it is tenant-infrastructure, not a domain-repository class. Under Spring
 * Modulith (DEC-21), no other module imports it — only Spring's component scan discovers it.
 *
 * <h2>Tenant resolution (Wave-1 LAN mode, DEC-5)</h2>
 *
 * <p>In Wave-1 single-tenant runtime, every HTTP request resolves to the default tenant UUID via
 * {@link TenantRegistryPort#getDefault()}. This method returns the unique bootstrapped default
 * tenant (see E14S05). Cloud-tenant authentication (Wave-2+) would replace or supplement this
 * resolver with one that reads a tenant claim from the JWT.
 *
 * <h2>Scope lifecycle</h2>
 *
 * <p>The {@link TenantContext.Scope} returned by {@link TenantContext#bind(UUID)} is stored in a
 * per-thread variable and closed in {@link #afterCompletion}, which is always called by Spring MVC
 * even when the handler throws. This ensures the ThreadLocal stack is always cleaned up in the
 * servlet thread pool (no leaks).
 *
 * <h2>Nested-bind compatibility (E14S01 AC-NESTED-BIND)</h2>
 *
 * <p>If an inner handler (WebSocket interceptor, async task) also calls {@link
 * TenantContext#bind(UUID)}, the outer scope is restored when that inner scope is closed — because
 * {@link TenantContext#bind(UUID)} uses a stack-based implementation. This resolver's scope is the
 * outermost frame; closing it in {@code afterCompletion} restores the pre-request state (empty
 * stack).
 *
 * <h2>Error handling (AC-error-handling)</h2>
 *
 * <p>If {@link TenantRegistryPort#getDefault()} throws (bootstrap not complete), the exception
 * propagates from {@link #preHandle}, and Spring MVC returns a 500 error.
 *
 * <h2>No feature flags (DEC-21)</h2>
 *
 * <p>No {@code @Profile}, no {@code @ConditionalOnProperty}, no {@code @Conditional*}. Per DEC-21,
 * the atomic-cutover protocol manages activation — not feature flags.
 *
 * @see TenantContext
 * @see TenantRegistryPort
 * @see <a href="../../../../../../../../docs/governance/stories/E14S12.story.md">Story E14S12</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-24.md">DEC-24 (mandate)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21 (Modulith + no
 *     feature flags)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22 (TDD)</a>
 */
@Component
public class TenantContextResolver implements HandlerInterceptor, WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TenantContextResolver.class);

    /**
     * Per-thread storage for the active {@link TenantContext.Scope}.
     *
     * <p>Set in {@link #preHandle}; consumed (and removed) in {@link #afterCompletion}. {@code
     * afterCompletion} is always called by Spring MVC even when the handler throws, so the
     * ThreadLocal is always cleaned up.
     */
    private final ThreadLocal<TenantContext.Scope> activeScope = new ThreadLocal<>();

    private final TenantContext tenantContext;
    private final TenantRegistryPort tenantRegistryPort;

    /**
     * Constructs a {@code TenantContextResolver}.
     *
     * @param tenantContext the new module-API {@link TenantContext} (the {@code
     *     tenantRoutingContext} bean from {@link TenantContextConfiguration}); must not be {@code
     *     null}
     * @param tenantRegistryPort the tenant registry; must not be {@code null}
     */
    public TenantContextResolver(
            TenantContext tenantContext, TenantRegistryPort tenantRegistryPort) {
        if (tenantContext == null) {
            throw new IllegalArgumentException("tenantContext must not be null");
        }
        if (tenantRegistryPort == null) {
            throw new IllegalArgumentException("tenantRegistryPort must not be null");
        }
        this.tenantContext = tenantContext;
        this.tenantRegistryPort = tenantRegistryPort;
    }

    /**
     * Resolves the default tenant UUID via {@link TenantRegistryPort#getDefault()} and binds it to
     * the current thread via {@link TenantContext#bind(UUID)}.
     *
     * <p>The returned {@link TenantContext.Scope} is stored in a thread-local for cleanup in {@link
     * #afterCompletion}.
     *
     * @param request the current HTTP request
     * @param response the current HTTP response
     * @param handler the chosen handler
     * @return {@code true} always — processing continues
     * @throws IllegalStateException if the registry has no default tenant (bootstrap not complete)
     */
    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        UUID defaultTenantId = tenantRegistryPort.getDefault();
        TenantContext.Scope scope = tenantContext.bind(defaultTenantId);
        activeScope.set(scope);
        return true;
    }

    /**
     * Closes the {@link TenantContext.Scope} that was opened in {@link #preHandle}.
     *
     * <p>Always called by Spring MVC — even when the handler threw an exception — ensuring the
     * ThreadLocal stack is always cleaned up (no leaks in thread pools).
     *
     * @param request the current HTTP request
     * @param response the current HTTP response
     * @param handler the chosen handler
     * @param ex any exception thrown by the handler, or {@code null}
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        TenantContext.Scope scope = activeScope.get();
        if (scope != null) {
            scope.close();
            activeScope.remove();
        }
    }

    /**
     * Registers this interceptor to apply to all URL patterns.
     *
     * @param registry the interceptor registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/**");
        log.info("[tm-tenant] TenantContextResolver registered for all paths (E14S12, DEC-24)");
    }
}
