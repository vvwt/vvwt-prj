/**
 * Domain events published by the {@code tournament} bounded context.
 *
 * <p>This package is exposed as a named interface ({@code "events"}) of the {@code tournament}
 * module, allowing other modules (e.g., {@code scoring}) to consume published events without
 * violating Spring Modulith boundary rules (DEC-21).
 *
 * <p>Consumers declare {@code allowedDependencies = {"tournament", "tournament::events"}} in their
 * {@code @ApplicationModule} annotation (see {@code de.vvwt.tm.scoring package-info.java}).
 *
 * @since E31S03 — named interface added to support scoring module event consumption (DEC-21)
 */
@org.springframework.modulith.NamedInterface("events")
package de.vvwt.tm.tournament.events;
