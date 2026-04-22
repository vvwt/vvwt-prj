/**
 * Primary-adapter isolation module for the Tournament Manager (DEC-40 Clause A).
 *
 * <p>This package is the canonical home for all driving-side REST adapters (Spring MVC
 * {@code @Controller} and {@code @RestController} classes) across all bounded contexts. Bounded
 * contexts ({@code tournament}, {@code scoring}, {@code tenant}, etc.) expose their domain logic
 * via public service interfaces and entity types; the {@code web} module consumes those APIs and
 * maps them to HTTP responses.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <ul>
 *   <li>{@code tenant} — multi-tenant request routing; controllers read tenant context for
 *       per-tenant DataSource routing (DEC-20).
 *   <li>{@code tournament} — tournament root-package types: entities ({@code Tournament}, {@code
 *       Match}, {@code Phase}, {@code Team}), repository interfaces, service interfaces, enums.
 *   <li>{@code tournament::exceptions} — {@code ValidationException}, {@code ForbiddenException},
 *       {@code DraftAlreadyAppliedException}, etc. Required because Spring Modulith named-interface
 *       sub-packages are NOT accessible via the root-module declaration alone.
 *   <li>{@code tournament::dto} — HTTP request/response DTOs in {@code tournament.internal.dto.*}.
 *       Exposed as a named interface so that relocated controllers in {@code web} can reference
 *       these wire types without violating Modulith boundary rules (E22S07, DEC-40 Clause A).
 *   <li>{@code tournament::draft-dto} — Draft request/response DTOs in {@code
 *       tournament.internal.dto.draft.*} ({@code DraftRequest}, {@code DraftSectionRequest}, {@code
 *       DraftApplyResponse}, {@code DraftPreviewResponse}, etc.). Separate named interface required
 *       because Spring Modulith named interfaces do NOT cover sub-packages automatically; the
 *       parent {@code tournament::dto} covers only {@code tournament.internal.dto.*}. Required by
 *       {@code DraftController} relocated to {@code web} in E22S08 (DEC-40 Clause A expansion).
 *   <li>{@code tournament::draft} — Draft value objects ({@code DraftBreak}, {@code DraftConfig},
 *       {@code DraftPreviewResult}, {@code DraftSection}) in {@code tournament.draft.*}. Required
 *       by {@code DraftController} relocated to {@code web} in E22S08 (DEC-40 Clause A expansion).
 *   <li>{@code scoring} — controllers that invoke {@code ScoringService} or scoring registries
 *       ({@code ScoringRuleRegistry}, {@code SetValidationRuleRegistry}) import from the scoring
 *       root package.
 * </ul>
 *
 * <h2>Boundary rules (DEC-40 § Clause A)</h2>
 *
 * <ul>
 *   <li>Bounded-context modules MUST NOT contain REST controllers.
 *   <li>This module MUST NOT be depended on by any bounded-context module (single direction: web →
 *       context).
 *   <li>REST DTOs for HTTP request/response serialization live in {@code
 *       de.vvwt.tm.web.internal.dto.*}.
 * </ul>
 *
 * <p>See DEC-40 for the full Primary-Adapter-Isolation decision and migration cadence.
 *
 * @since E22S01
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "tenant",
            "tournament",
            "tournament::exceptions",
            "tournament::dto",
            "tournament::draft-dto",
            "tournament::draft",
            "scoring"
        })
package de.vvwt.tm.web;
