package de.vvwt.tm.domain.repo;

import java.util.UUID;

/**
 * Test helper for setting and clearing the package-private {@link TenantContext}
 * from test code outside the {@code de.vvwt.tm.domain.repo} package.
 *
 * <p>This class lives in the same package as {@link TenantContext} so it can
 * access the package-private {@link TenantContext#set(UUID)} and
 * {@link TenantContext#clear()} methods. Integration tests in other packages
 * delegate to this helper.
 *
 * <p>For test use only. Do not use in production code.
 */
public final class TenantContextTestHelper {

    private TenantContextTestHelper() {
        // utility class — no instances
    }

    /**
     * Sets the active tenant ID for the current thread.
     *
     * @param tenantContext the {@link TenantContext} bean
     * @param tenantId      the tenant ID to activate
     */
    public static void set(TenantContext tenantContext, UUID tenantId) {
        tenantContext.set(tenantId);
    }

    /**
     * Clears the active tenant ID for the current thread.
     *
     * @param tenantContext the {@link TenantContext} bean
     */
    public static void clear(TenantContext tenantContext) {
        tenantContext.clear();
    }
}
