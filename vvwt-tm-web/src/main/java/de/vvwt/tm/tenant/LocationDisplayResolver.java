package de.vvwt.tm.tenant;

import java.util.UUID;

/**
 * Tenant-scoped location display-name lookup service for rendering controllers.
 *
 * <p>Resolves the display name of the location associated with a given tenant. Returns the {@code
 * display_name} of the first matching {@code locations} row for the tenant, or an empty string
 * {@code ""} when no location row exists for the tenant — never throws an exception for an absent
 * result.
 *
 * <p>Consumed by fresh {@code CertificateRenderController} (S05) and fresh {@code PrintController}
 * (S06) in the {@code de.vvwt.tm.web} module. The legacy {@code
 * de.vvwt.tm.infrastructure.print.PrintController} retains its own in-class {@code
 * resolveLocationDisplayName} method until S07 atomic cutover.
 *
 * <h2>Naming rationale (DEC-35)</h2>
 *
 * <p>{@code LocationDisplayResolver} (not {@code LocationDisplayService}) because:
 *
 * <ol>
 *   <li>Single-method, read-only responsibility — not a general-purpose service.
 *   <li>Consistent with existing tenant-module {@code Resolver} precedent: {@code
 *       TenantDataSourceResolver}, {@code TenantContextResolver}, {@code
 *       TenantFileRegistryDataSourceResolver}.
 * </ol>
 *
 * @see de.vvwt.tm.tenant.internal.DefaultLocationDisplayResolver
 * @since E24S04
 */
public interface LocationDisplayResolver {

    /**
     * Resolves the display name of the location associated with the given tenant.
     *
     * <p>Returns the {@code display_name} column of the first (and, via {@code LIMIT 1}, only)
     * matching {@code locations} row for the given tenant identifier. Returns an empty string {@code
     * ""} when no location row exists — never throws an exception for an absent result.
     *
     * @param tenantId the UUID identifier of the tenant whose location display name is requested;
     *     must not be {@code null}
     * @return the location display name, or {@code ""} when no location row exists for the tenant
     */
    String resolveLocationDisplayName(UUID tenantId);
}
