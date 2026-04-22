/**
 * Draft-specific REST request/response DTOs, part of the {@code "dto"} named interface of the
 * {@code tournament} module.
 *
 * <p>This sub-package is included in the {@code tournament::dto} named interface (declared on the
 * parent {@code de.vvwt.tm.tournament.internal.dto} package). The {@code @NamedInterface}
 * annotation is repeated here because Spring Modulith does NOT automatically extend named
 * interfaces to sub-packages — each sub-package must be explicitly annotated to be accessible to
 * external modules (e.g., {@code de.vvwt.tm.web}).
 *
 * <p>Consumers declare {@code allowedDependencies = {"tournament::dto"}} in their
 * {@code @ApplicationModule} annotation to access both the parent DTO package and this draft-DTO
 * sub-package.
 *
 * @since E22S08 — named interface added to support {@code DraftController} relocation to {@code
 *     de.vvwt.tm.web} (DEC-40 Clause A; a separate named interface {@code "draft-dto"} is required
 *     because Spring Modulith named interfaces do NOT extend to sub-packages; the parent {@code
 *     dto} named interface covers only {@code tournament.internal.dto.*} and not this sub-package).
 */
@org.springframework.modulith.NamedInterface("draft-dto")
package de.vvwt.tm.tournament.internal.dto.draft;
