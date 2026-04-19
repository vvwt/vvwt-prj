package de.vvwt.tm.domain.repo;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Holds the active {@code tenant_id} for the current thread.
 *
 * <p>This is a singleton Spring bean whose <em>state</em> is kept in a {@link ThreadLocal}. This
 * gives each concurrent thread (HTTP request, background job) its own isolated tenant scope without
 * Spring proxy overhead or request-scope complications.
 *
 * <h2>Lifecycle (DEC-5, DEC-17, AC1)</h2>
 *
 * <p>From E14S12 onward, the {@code TenantContextResolver} interceptor (in {@code
 * de.vvwt.tm.tenant.internal}) calls {@link de.vvwt.tm.tenant.TenantContext#bind(UUID)} at the
 * start of each HTTP request and closes the returned {@link de.vvwt.tm.tenant.TenantContext.Scope}
 * in {@code afterCompletion}. This class bridges the legacy repository layer by delegating {@link
 * #getTenantId()} to the new {@link de.vvwt.tm.tenant.TenantContext#current()} — sharing the same
 * per-thread binding that the new resolver establishes. This bridge is in effect during the
 * parallel development phase (E14S12 → E14S07); at E14S07 atomic cutover, this legacy class is
 * deleted along with the rest of the {@code domain.repo} legacy infrastructure.
 *
 * <h2>Runtime guard (AC1, AC6)</h2>
 *
 * <p>{@link #getTenantId()} throws {@link IllegalStateException} if no tenant has been set. This
 * fires before any SQL is executed, guaranteeing that unscoped queries are structurally impossible
 * through the repository layer.
 *
 * <h2>Parallel-phase bridge (E14S12 — DEC-21)</h2>
 *
 * <p>The {@link #set(UUID)} and {@link #clear()} methods remain functional for test infrastructure
 * that calls them directly (e.g., {@code E03S05RepositoryIT}). They operate on the local {@link
 * ThreadLocal} and do NOT propagate to the new {@link de.vvwt.tm.tenant.TenantContext} bean — but
 * the {@link #getTenantId()} read path now reads from the new bean, which the new interceptor
 * populates for HTTP requests. This ensures HTTP request paths work correctly via the new
 * interceptor, while direct test setup (using {@link #set(UUID)}) continues to work for tests that
 * call the repositories directly without going through an interceptor.
 *
 * @see de.vvwt.tm.tenant.TenantContext
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story
 *     E03S05</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E14S12.story.md">Story E14S12
 *     (bridge)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21 (parallel
 *     phase)</a>
 */
@Component
public class TenantContext {

    /** Local ThreadLocal for direct {@link #set(UUID)} / {@link #clear()} calls from tests. */
    private static final ThreadLocal<UUID> LOCAL_HOLDER = new ThreadLocal<>();

    /**
     * The new {@code tenant::api} {@link de.vvwt.tm.tenant.TenantContext} bean. Populated at HTTP
     * request time by the new {@code TenantContextResolver}. Used as the authoritative read source
     * in {@link #getTenantId()}.
     */
    private final de.vvwt.tm.tenant.TenantContext newTenantContext;

    /**
     * Constructs the bridge, injecting the new TenantContext bean.
     *
     * @param newTenantContext the new {@code tenantRoutingContext} bean from {@code tenant::api}
     */
    @Autowired
    public TenantContext(
            @Qualifier("tenantRoutingContext") de.vvwt.tm.tenant.TenantContext newTenantContext) {
        this.newTenantContext = newTenantContext;
    }

    /**
     * Returns the active tenant ID for the current thread.
     *
     * <p>Read priority:
     *
     * <ol>
     *   <li>If the new {@link de.vvwt.tm.tenant.TenantContext} has a bound tenant (set by the new
     *       {@code TenantContextResolver} for HTTP requests), returns that UUID.
     *   <li>If not bound in the new context, falls back to the local {@link ThreadLocal} (set by
     *       {@link #set(UUID)} for direct test calls and background jobs that use the legacy API).
     * </ol>
     *
     * @return the active tenant ID; never {@code null}
     * @throws IllegalStateException if no tenant is bound to the current thread
     */
    public UUID getTenantId() {
        // Try the new TenantContext first (HTTP requests via TenantContextResolver)
        try {
            return newTenantContext.current();
        } catch (IllegalStateException ignored) {
            // New context not bound — fall through to local holder
        }
        // Fall back to legacy local holder (direct test setup or background jobs)
        UUID id = LOCAL_HOLDER.get();
        if (id == null) {
            throw new IllegalStateException(
                    "No active TenantContext — caller must resolve tenant before repository access."
                        + " If this is an HTTP request, verify that TenantContextResolver is"
                        + " registered and that the request path is covered by the interceptor"
                        + " mapping. If this is a background job, set the TenantContext explicitly"
                        + " before calling any repository method.");
        }
        return id;
    }

    /**
     * Sets the active tenant ID for the current thread via the local {@link ThreadLocal}.
     *
     * <p>Used by:
     *
     * <ul>
     *   <li>Test infrastructure — integration tests that need to scope repository access directly
     *   <li>Background jobs / tasks that use the legacy API
     * </ul>
     *
     * <p>HTTP request paths are served by the new {@code TenantContextResolver}, which calls {@link
     * de.vvwt.tm.tenant.TenantContext#bind(UUID)} — callers using that path do NOT need to call
     * {@code set()} directly.
     *
     * <p>Callers must always pair a {@code set()} with a {@link #clear()} in a {@code finally}
     * block to prevent ThreadLocal leaks in thread pools.
     *
     * @param tenantId the tenant ID to activate (must not be {@code null})
     * @throws NullPointerException if {@code tenantId} is {@code null}
     */
    public void set(UUID tenantId) {
        if (tenantId == null) {
            throw new NullPointerException("tenantId must not be null when setting TenantContext");
        }
        LOCAL_HOLDER.set(tenantId);
    }

    /**
     * Clears the active tenant ID for the current thread (legacy local holder only).
     *
     * <p>HTTP-request-scoped bindings (from the new {@code TenantContextResolver}) are managed by
     * that resolver's {@code afterCompletion}; this method does NOT close them. Call {@link
     * #clear()} only when you called {@link #set(UUID)} first.
     */
    public void clear() {
        LOCAL_HOLDER.remove();
    }
}
