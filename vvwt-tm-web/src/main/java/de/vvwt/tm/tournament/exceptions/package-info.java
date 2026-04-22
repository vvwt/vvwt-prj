/**
 * Domain exceptions published by the {@code tournament} bounded context.
 *
 * <p>This package is exposed as a named interface ({@code "exceptions"}) of the {@code tournament}
 * module, allowing other modules (e.g., {@code scoring}) to throw/catch these exceptions without
 * violating Spring Modulith boundary rules (DEC-21).
 *
 * <p>Consumers declare {@code allowedDependencies = {"tournament", "tournament::exceptions"}} in
 * their {@code @ApplicationModule} annotation (see {@code de.vvwt.tm.scoring package-info.java}).
 *
 * @since E31S03 — named interface added to support scoring module exception usage (DEC-21)
 */
@org.springframework.modulith.NamedInterface("exceptions")
package de.vvwt.tm.tournament.exceptions;
