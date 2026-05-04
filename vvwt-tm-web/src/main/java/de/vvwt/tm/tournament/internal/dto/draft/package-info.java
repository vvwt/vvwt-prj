/**
 * Draft wire-format DTOs (request/response records) in the {@code tournament} bounded context.
 *
 * <p>This sub-package is exposed as a separate named interface {@code "draft-dto"} of the {@code
 * tournament} module. Spring Modulith 2.x does NOT automatically extend a parent package's {@code
 * @NamedInterface} to its sub-packages: {@code tournament::dto} (on {@code tournament.internal.dto})
 * covers only types directly in that package. The {@code tournament.internal.dto.draft} sub-package
 * requires its own {@code @NamedInterface} so that the {@code web} module can import {@link
 * de.vvwt.tm.tournament.internal.dto.draft.DraftRequest}, {@link
 * de.vvwt.tm.tournament.internal.dto.draft.DraftResponse}, and related records cross-module without
 * violating Spring Modulith boundary rules.
 *
 * <p>Consumers declare {@code allowedDependencies = {"tournament::draft-dto", ...}} in their {@code
 * @ApplicationModule} annotation.
 *
 * @since E21S19 — named interface added to support DraftController relocation to {@code web} module
 *     (DEC-40 Clause A; AC-NEW-DRAFT-DTO-NAMED-INTERFACE)
 * @see de.vvwt.tm.tournament.internal.dto package-info — parent named interface "dto" (does not
 *     extend to this sub-package)
 * @see <a href="DEC-21">DEC-21 — Spring Modulith: named-interface sub-packages</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation: web module controller relocation</a>
 */
@org.springframework.modulith.NamedInterface("draft-dto")
package de.vvwt.tm.tournament.internal.dto.draft;
