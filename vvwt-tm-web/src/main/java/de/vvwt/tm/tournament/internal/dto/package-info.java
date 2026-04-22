/**
 * REST DTOs (request/response records) published by the {@code tournament} bounded context.
 *
 * <p>This package is exposed as the named interface {@code "dto"} of the {@code tournament} module,
 * allowing the {@code web} module to reference these HTTP wire types without violating Spring
 * Modulith boundary rules (DEC-40 Clause A, DEC-21).
 *
 * <p>Consumers declare {@code allowedDependencies = {"tournament", "tournament::exceptions",
 * "tournament::dto"}} in their {@code @ApplicationModule} annotation.
 *
 * <p>Types in the parent {@code tournament.internal} package (service implementations, repository
 * adapters) remain private and MUST NOT be accessed by any other module.
 *
 * @since E22S07 — named interface added to support web module controller relocation (DEC-40)
 */
@org.springframework.modulith.NamedInterface("dto")
package de.vvwt.tm.tournament.internal.dto;
