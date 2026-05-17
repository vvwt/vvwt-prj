// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant;

/**
 * Tenant-scoped location display-name lookup service for rendering controllers.
 *
 * <p>Resolves the display name of the location for the current tenant. Returns the {@code
 * display_name} of the first {@code locations} row in the per-tenant DataSource (DEC-20
 * AbstractRoutingDataSource ensures all rows belong to the bound tenant), or an empty string {@code
 * ""} when no location row exists — never throws an exception for an absent result.
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
 * <h2>E45S04 — DEC-39/DEC-50 predicate removal</h2>
 *
 * <p>The {@code tenant_id = ?} WHERE predicate is dropped from the SQL query. Under DEC-20
 * DB-per-Tenant, all rows in the per-tenant DataSource belong to one tenant by connection-level
 * routing — the discriminator is redundant. The {@code tenantId} parameter is removed accordingly.
 *
 * @see de.vvwt.tm.tenant.internal.DefaultLocationDisplayResolver
 * @since E24S04; amended E45S04 (DEC-39/DEC-50 predicate removal)
 */
public interface LocationDisplayResolver {

    /**
     * Resolves the display name of the location for the current tenant.
     *
     * <p>Returns the {@code display_name} column of the first {@code locations} row in the
     * per-tenant DataSource. Per-tenant routing (DEC-20) ensures all rows belong to the bound
     * tenant — no {@code tenant_id} filter is required. Returns an empty string {@code ""} when no
     * location row exists — never throws an exception for an absent result.
     *
     * @return the location display name, or {@code ""} when no location row exists
     */
    String resolveLocationDisplayName();
}
