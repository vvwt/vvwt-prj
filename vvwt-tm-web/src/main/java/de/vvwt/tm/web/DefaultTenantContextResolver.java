package de.vvwt.tm.web;

import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.tenant.DefaultTenantProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Resolves the default-tenant context for each incoming HTTP request (AC9, DEC-17).
 *
 * <p>This interceptor sets the {@link TenantContext} to the default-tenant UUID at the start
 * of every HTTP request and clears it in {@code afterCompletion} (always called — even on
 * exception). This ensures the ThreadLocal is always cleaned up in the servlet thread pool.
 *
 * <h2>Default-tenant LAN mode (DEC-5, DEC-17)</h2>
 * <p>In V1 single-tenant runtime, every HTTP request resolves to the default tenant UUID.
 * Cloud-tenant authentication (V2+) would replace this interceptor with one that reads a
 * tenant claim from the JWT — the {@link TenantContext} interface remains the same.
 *
 * <h2>Mapping (AC9)</h2>
 * <p>Applied to all paths ({@code /**}) including Actuator endpoints. Actuator endpoints
 * ({@code /actuator/**}) don't use the repository layer, so the overhead is negligible.
 *
 * <h2>Error handling (AC10)</h2>
 * <p>If {@link DefaultTenantProvider#getDefaultTenantId()} throws (bootstrap not complete —
 * should not happen since ApplicationRunner order guarantees bootstrap runs first), the
 * exception propagates and Spring MVC returns a 500 error. The log entry from the exception
 * will name the missing context.
 *
 * @see TenantContext
 * @see DefaultTenantProvider
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story E03S05</a>
 */
@Component
public class DefaultTenantContextResolver implements HandlerInterceptor, WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantContextResolver.class);

    private final TenantContext tenantContext;
    private final DefaultTenantProvider defaultTenantProvider;

    public DefaultTenantContextResolver(TenantContext tenantContext,
                                         DefaultTenantProvider defaultTenantProvider) {
        this.tenantContext = tenantContext;
        this.defaultTenantProvider = defaultTenantProvider;
    }

    /**
     * Sets the {@link TenantContext} to the default-tenant UUID before the request handler runs.
     *
     * @param request  the current HTTP request
     * @param response the current HTTP response
     * @param handler  the chosen handler
     * @return {@code true} always — processing continues
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        tenantContext.set(defaultTenantProvider.getDefaultTenantId());
        return true;
    }

    /**
     * Clears the {@link TenantContext} after the request completes (always called).
     *
     * <p>The {@code clear()} call is in {@code afterCompletion} (not {@code postHandle}) to
     * ensure it runs even when the handler throws an exception.
     *
     * @param request  the current HTTP request
     * @param response the current HTTP response
     * @param handler  the chosen handler
     * @param ex       any exception thrown by the handler, or {@code null}
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        tenantContext.clear();
    }

    /**
     * Registers this interceptor to apply to all URL patterns.
     *
     * @param registry the interceptor registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/**");
        log.info("[tm-tenant] DefaultTenantContextResolver registered for all paths");
    }
}
