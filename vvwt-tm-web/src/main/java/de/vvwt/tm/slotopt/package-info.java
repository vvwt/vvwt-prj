/**
 * Public API of the {@code slotopt} bounded context.
 *
 * <p>This package is the contract surface for the {@code slotopt} Spring Modulith module. Types
 * declared here ({@link de.vvwt.tm.slotopt.SlotOptimizationClient}, {@link
 * de.vvwt.tm.slotopt.MappingResult}, etc.) are consumable by other Modulith modules; types in
 * {@code de.vvwt.tm.slotopt.internal} are implementation details and MUST NOT be accessed by any
 * other module.
 *
 * <h2>Allowed dependencies (DEC-21, DEC-49)</h2>
 *
 * <p>The {@code slotopt} context depends on {@code tournament} only — justified by the existing use
 * of {@link de.vvwt.tm.tournament.Match}, {@link de.vvwt.tm.tournament.MatchRepository}, {@link
 * de.vvwt.tm.tournament.PhaseRepository}, {@link de.vvwt.tm.tournament.TeamAvatar}, and {@link
 * de.vvwt.tm.tournament.TeamAvatarRepository} in the pre-existing {@code slotopt} classes. No
 * {@code tournament::exceptions} sub-package is referenced by any {@code slotopt} class (verified
 * empirically at E27S01 delivery — OMIT per DEC-40 per-entry-justification mandate).
 *
 * <p>The canonical {@link SlotOptimizationClient} entry point from E27S01 forward is {@link
 * de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient}. See DEC-49 for the routing rule
 * governing N-based leg selection (Legs 2/3 land in E27S02/S03).
 *
 * <p>Authorizing decisions: DEC-21 (Spring Modulith layout), DEC-22 (TDD Iron Law), DEC-40
 * (allowedDependencies per-entry justification), DEC-49 (routing rule + canonical entry point).
 *
 * @since E27S01
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"tournament"})
package de.vvwt.tm.slotopt;
