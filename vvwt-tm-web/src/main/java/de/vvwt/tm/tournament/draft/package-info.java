/**
 * Draft value objects (VOs) exposed as a named interface ({@code "draft"}) of the {@code
 * tournament} module.
 *
 * <p>This package is exposed as a named interface ({@code "draft"}) of the {@code tournament}
 * module, allowing other modules (e.g., {@code de.vvwt.tm.web}) to consume Draft VOs without
 * violating Spring Modulith boundary rules (DEC-21, DEC-35).
 *
 * <p>Consumers declare {@code allowedDependencies = {"tournament", "tournament::draft"}} in their
 * {@code @ApplicationModule} annotation (see {@code de.vvwt.tm.web package-info.java}).
 *
 * @since E33S04 — named interface added to support DraftService interface extraction (E33S05) and
 *     DraftController migration to web module (E22, DEC-40 Clause D)
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout: named-interface sub-packages</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation: web module consumes draft VOs</a>
 */
@org.springframework.modulith.NamedInterface("draft")
package de.vvwt.tm.tournament.draft;
