/**
 * Print bounded-context module for the Tournament Manager (DEC-21, DEC-40 Clause A, E24S01).
 *
 * <p>This is the canonical home for all print-domain production classes: assemblers, resolvers,
 * and value objects that produce Mustache-template-ready data models for print endpoints. REST
 * controllers that consume this module live in {@code de.vvwt.tm.web} per DEC-40 Clause A
 * (Primary-Adapter-Isolation) and are added to {@code web.allowedDependencies} at E24S06.
 *
 * <h2>Allowed dependencies (DEC-40 Clause A per-entry justification)</h2>
 *
 * <ul>
 *   <li>{@code tournament} — Print assemblers (S02: LaufzettelAssembler, S03:
 *       ActivityScheduleAssembler) consume tournament entities ({@code Tournament}, {@code Match},
 *       {@code Phase}, {@code Team}, {@code Match} result types) to produce print-ready models.
 *       {@code tournament} is the sole bounded-context dependency at E24 scope.
 * </ul>
 *
 * <p>Explicitly excluded from {@code allowedDependencies}:
 *
 * <ul>
 *   <li>{@code tenant} — Print assemblers are pure data-transformation logic; they receive
 *       tenant-scoped data as method arguments from the controller layer but do not import tenant
 *       context directly.
 *   <li>{@code certificate} — No certificate types consumed by print assemblers.
 *   <li>{@code photo} — No photo types consumed by print assemblers.
 *   <li>{@code scoring} — No direct scoring-module dependency; scoring results are carried by
 *       tournament entities (matches have score fields). Print assemblers read through the
 *       tournament-module surface only.
 * </ul>
 *
 * <h2>DEC-40 Trigger-α status (E24S01 examination)</h2>
 *
 * <p>Pre-E24 {@code web.allowedDependencies} count (excl. {@code tenant}): 4 ({@code tournament},
 * {@code scoring}, {@code photo}, {@code certificate}). Adding {@code print} at E24S06 raises the
 * count to 5, which meets the Trigger-α threshold (≥5). The forced examination verdict is
 * documented in the E24S01 impl-report (AC-ALPHA-VERDICT-BINDING): <strong>L2 stays</strong>.
 * Re-examination deferred to E25.
 *
 * <h2>Module structure at E24S01</h2>
 *
 * <p>At S01 end this package contains ONLY {@code package-info.java}. No classes are present.
 * Assemblers (S02, S03), resolver (S04), and supporting types land in subsequent E24 stories.
 * {@code ApplicationModules.verify()} passes with an empty declared module (Modulith 2.x tolerates
 * empty modules).
 *
 * <h2>Legacy coexistence (DEC-22 reconstruction-in-place)</h2>
 *
 * <p>Legacy print code at {@code de.vvwt.tm.infrastructure.print.*} (assemblers, controllers,
 * exception class) is UNTOUCHED until the E24S07 atomic cutover (DEC-21). During E24S01–E24S06,
 * both the new print module (this package) and the legacy {@code infrastructure.print.*} unassigned
 * package coexist in the Spring ApplicationContext.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-21.md">DEC-21 — Spring Modulith adoption</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-40.md">DEC-40 — Primary-Adapter-Isolation</a>
 * @since E24S01
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"tournament"})
package de.vvwt.tm.print;
