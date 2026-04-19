/**
 * Public API of the {@code auth} bounded context.
 *
 * <p>This package is the contract surface for the {@code auth} module.
 * Types declared here are consumable by other Modulith modules; types
 * in {@code de.vvwt.tm.auth.internal} are implementation details and
 * MUST NOT be accessed by any other module.
 *
 * <h2>Public API surface (Wave-1, reconstruction-in-place)</h2>
 * <ul>
 *   <li>{@link de.vvwt.tm.auth.AdminCredentialsProvider} — provides the bcrypt hash of
 *       the admin password after bootstrap; consumed by Spring Security (E15S04).</li>
 * </ul>
 *
 * <h2>Allowed dependencies (DEC-21)</h2>
 * <p>The {@code auth} context may depend on {@code tenant} (public API only) — justified by
 * DEC-20 (DB-per-Tenant) and DEC-21 (Modulith dependency declaration). No other bounded
 * context may be imported into {@code auth} during Wave-1.
 *
 * <h2>Reconstruction-in-place note (DEC-21, DEC-22)</h2>
 * <p>During Wave-1, the legacy {@code auth} package contains both old classes
 * ({@code AdminCredentialsBootstrap}, {@code AdminCredentialsProvider} legacy version,
 * {@code SecurityConfig}) and the new reconstruction-in-place classes (now in
 * {@code auth.internal}). The legacy classes fall under this {@code @ApplicationModule}
 * annotation; they do not import from {@code de.vvwt.tm.tenant.internal.*}, so the
 * annotation does not break the parallel phase (AC4). The legacy classes are deleted
 * at E15S07 atomic cutover (DEC-21).
 *
 * <p>Authorizing decisions: DEC-20 (DB-per-Tenant justifies tenant dependency),
 * DEC-21 (Spring Modulith layout + allowedDependencies contract),
 * DEC-22 (TDD Iron Law; violation spike in E15S06 plan proves enforcement).
 *
 * @since E15S06
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"tenant"})
package de.vvwt.tm.auth;
