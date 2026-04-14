package de.vvwt.tm.domain.repo;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Holds the active {@code tenant_id} for the current thread.
 *
 * <p>This is a singleton Spring bean whose <em>state</em> is kept in a {@link ThreadLocal}.
 * This gives each concurrent thread (HTTP request, background job) its own isolated tenant
 * scope without Spring proxy overhead or request-scope complications.
 *
 * <h2>Lifecycle (DEC-5, DEC-17, AC1)</h2>
 * <p>The {@link DefaultTenantContextResolver} interceptor calls
 * {@link #set(UUID)} at the start of each HTTP request and {@link #clear()} in its
 * {@code afterCompletion} hook, ensuring the ThreadLocal is always cleaned up.
 *
 * <h2>Runtime guard (AC1, AC6)</h2>
 * <p>{@link #getTenantId()} throws {@link IllegalStateException} if no tenant has been set.
 * This fires before any SQL is executed, guaranteeing that unscoped queries are structurally
 * impossible through the repository layer.
 *
 * <h2>Package-private access (AC1)</h2>
 * <p>{@link #set(UUID)} and {@link #clear()} are package-private. Only
 * {@link DefaultTenantContextResolver} (which lives in the same package) and tests may invoke
 * them. Domain code and service code may only call {@link #getTenantId()}.
 *
 * @see DefaultTenantContextResolver
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story E03S05</a>
 */
@Component
public class TenantContext {

    private static final ThreadLocal<UUID> TENANT_ID_HOLDER = new ThreadLocal<>();

    /**
     * Returns the active tenant ID for the current thread.
     *
     * @return the active tenant ID; never {@code null}
     * @throws IllegalStateException if no tenant context has been set for the current thread —
     *                               the calling code must ensure the context is resolved before
     *                               invoking any repository method
     */
    public UUID getTenantId() {
        UUID id = TENANT_ID_HOLDER.get();
        if (id == null) {
            throw new IllegalStateException(
                    "No active TenantContext — caller must resolve tenant before repository access. "
                    + "If this is an HTTP request, verify that DefaultTenantContextResolver is "
                    + "registered and that the request path is covered by the interceptor mapping. "
                    + "If this is a background job, set the TenantContext explicitly before "
                    + "calling any repository method.");
        }
        return id;
    }

    /**
     * Sets the active tenant ID for the current thread.
     *
     * <p>Intended for:
     * <ul>
     *   <li>{@link DefaultTenantContextResolver} — sets the tenant at the start of each
     *       HTTP request</li>
     *   <li>WebSocket STOMP ChannelInterceptors — e.g. in E07S06, the
     *       {@code WebSocketSecurityConfig} interceptor must set the TenantContext before
     *       calling tenant-scoped repository methods on the STOMP thread</li>
     *   <li>Test infrastructure — integration tests that need to scope repository access</li>
     * </ul>
     *
     * <p>Domain and service code must NOT call this method — they use {@link #getTenantId()}
     * only. Callers must always pair a {@code set()} with a {@link #clear()} in a
     * {@code finally} block to prevent ThreadLocal leaks in thread pools.
     *
     * @param tenantId the tenant ID to activate (must not be {@code null})
     * @throws NullPointerException if {@code tenantId} is {@code null}
     */
    public void set(UUID tenantId) {
        if (tenantId == null) {
            throw new NullPointerException("tenantId must not be null when setting TenantContext");
        }
        TENANT_ID_HOLDER.set(tenantId);
    }

    /**
     * Clears the active tenant ID for the current thread.
     *
     * <p>Must be called in {@code afterCompletion} / {@code finally} to prevent ThreadLocal
     * leaks in thread pools.
     *
     * @see #set(UUID)
     */
    public void clear() {
        TENANT_ID_HOLDER.remove();
    }
}
