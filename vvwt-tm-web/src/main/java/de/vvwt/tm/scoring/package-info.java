/**
 * Public API of the {@code scoring} bounded context.
 *
 * <p>This package is the contract surface for the {@code scoring} module. Types declared here are
 * consumable by other Modulith modules; types in {@code de.vvwt.tm.scoring.internal} are
 * implementation details and MUST NOT be accessed by any other module.
 *
 * <h2>Allowed dependencies (DEC-21, DEC-35)</h2>
 *
 * <p>The {@code scoring} context may depend on:
 *
 * <ul>
 *   <li>{@code tenant} — DB-per-Tenant DataSource routing (DEC-20).
 *   <li>{@code tournament} — Tournament aggregate, Match, Phase, SetResult, domain events,
 *       exceptions (consumed by {@code DefaultScoringService} in E31S03+).
 * </ul>
 *
 * <p>No other bounded contexts are allowed: {@code certificate}, {@code print}, {@code display},
 * {@code timer}, {@code slotopt-integration}, and {@code auth} are all explicitly excluded.
 *
 * <h2>Bootstrap note (E31S02)</h2>
 *
 * <p>At E31S02 commit time, the {@code scoring} production package contains EXACTLY this file. No
 * functional code ({@code ScoringService} interface, {@code DefaultScoringService} implementation,
 * repositories, entities) is introduced here — that is E31S03 scope per Brief D-π S-5. The {@code
 * de.vvwt.tm.scoring.internal} sub-package is NOT created in production sources at this story's
 * commit time; it comes into existence in E31S03 when {@code DefaultScoringService.java} is added.
 *
 * <h2>Boundary enforcement (DEC-21 § C-14 — bytecode-substantive spike)</h2>
 *
 * <p>The bytecode-substantive spike test at {@code
 * de.vvwt.tm.scoring.internal.ScoringBoundarySpikeTest} (test sources) documents the verification
 * procedure confirming that {@code ApplicationModules.verify()} enforces this module's boundary
 * against production bytecode — not merely via unused imports. See that class's Javadoc for the
 * manual reproduction procedure.
 *
 * <p>Authorizing decisions: DEC-20 (DB-per-Tenant justifies tenant dependency), DEC-21 (Spring
 * Modulith layout + allowedDependencies contract), DEC-22 (TDD Iron Law), DEC-35 (package layout —
 * services as interfaces in public package, implementations in {@code .internal}).
 *
 * @since E31S02
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"tenant", "tournament"})
package de.vvwt.tm.scoring;
