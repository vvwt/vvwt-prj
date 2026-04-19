/**
 * Public API of the {@code tenant} bounded context.
 *
 * <p>This package is the contract surface for the {@code tenant} module. Types declared here are
 * consumable by other Modulith modules via {@code @ApplicationModule(allowedDependencies =
 * "tenant")}.
 *
 * <p>Types in {@code de.vvwt.tm.tenant.internal} are implementation details and MUST NOT be
 * accessed by any other module.
 *
 * <p>Public API surface (E14S07 atomic cutover — legacy types deleted):
 *
 * <ul>
 *   <li>{@link de.vvwt.tm.tenant.TenantContext} — current-thread tenant identifier (E14S01)
 *   <li>{@link de.vvwt.tm.tenant.TenantDataSourceResolver} — per-tenant DataSource routing (E14S01)
 *   <li>{@link de.vvwt.tm.tenant.TenantRegistryPort} — tenant existence lookup (E14S01)
 *   <li>{@link de.vvwt.tm.tenant.LocationContext} — current-thread location identifier (E14S09,
 *       DEC-24 D2)
 * </ul>
 *
 * <p>The legacy types {@code DefaultTenantProvider} and {@code DefaultTenantBootstrap} were deleted
 * in the E14S07 atomic cutover (DEC-21 cutover protocol).
 *
 * <p>Authorizing decisions: DEC-20 (DB-per-Tenant), DEC-21 (Spring Modulith layout), DEC-24
 * (LocationContext).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
package de.vvwt.tm.tenant;
