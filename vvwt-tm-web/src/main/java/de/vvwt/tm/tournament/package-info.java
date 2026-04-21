/**
 * Public API of the {@code tournament} bounded context.
 *
 * <p>This package is the contract surface for the {@code tournament} module. Types declared here
 * are consumable by other Modulith modules; types in {@code de.vvwt.tm.tournament.internal} are
 * implementation details and MUST NOT be accessed by any other module.
 *
 * <h2>Allowed dependencies (DEC-21, D-5)</h2>
 *
 * <p>The {@code tournament} context depends on {@code tenant} only — justified by DEC-20
 * (DB-per-Tenant DataSource routing). Six downstream contexts ({@code scoring}, {@code
 * certificate}, {@code print}, {@code display}, {@code timer}, {@code slotopt-integration}) consume
 * tournament's public API; tournament does NOT consume them. The dependency is one-way by design
 * (Session Brief {@code discovery-2026-04-20-e21-full-refinement} D-5).
 *
 * <h2>Reconstruction-in-place note (DEC-21, DEC-22, E21S12)</h2>
 *
 * <p>At E21S12 commit time, the legacy packages {@code de.vvwt.tm.domain.*} and {@code
 * de.vvwt.tm.infrastructure.web.*} still exist unannotated; their removal is E21S13 scope.
 * Tournament's {@code @ApplicationModule} annotation does not trigger false positives against
 * still-legacy code because the new tournament code (E21S02–S11) does not import from those legacy
 * paths.
 *
 * <p>Authorizing decisions: DEC-20 (DB-per-Tenant), DEC-21 (Spring Modulith layout +
 * allowedDependencies contract), DEC-22 (TDD Iron Law).
 *
 * @since E21S12
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"tenant"})
package de.vvwt.tm.tournament;
