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
 *   <li>{@code tournament::activity} — activity sub-module types ({@code ActivityType}, {@code
 *       ActivityTypeRepository}, {@code ActivityTypeService}, {@code ActivityAssignmentService},
 *       {@code ActivityAssignment}, {@code ActivityAssignmentResult}, {@code AssignmentRule}).
 *       {@code PrintController} fetches activity types for both print endpoints (Laufzettel,
 *       ActivitySchedule). Added at E45S01 following relocation from {@code de.vvwt.tm.domain.*}.
 *   <li>{@code tournament::exceptions} — {@code ValidationException}, {@code ForbiddenException},
 *       etc. Required because Spring Modulith named-interface sub-packages are NOT accessible via
 *       the root-module declaration alone.
 *   <li>{@code tournament::dto} — HTTP request/response DTOs in {@code tournament.internal.dto.*}.
 *       Exposed as a named interface so that relocated controllers in {@code web} can reference
 *       these wire types without violating Modulith boundary rules (E22S07, DEC-40 Clause A).
 *   <li>{@code tournament::draft-dto} — Draft wire-format DTOs in {@code
 *       tournament.internal.dto.draft.*} ({@link
 *       de.vvwt.tm.tournament.internal.dto.draft.DraftRequest}, {@link
 *       de.vvwt.tm.tournament.internal.dto.draft.DraftResponse}, {@link
 *       de.vvwt.tm.tournament.internal.dto.draft.DraftSectionRequest}, {@link
 *       de.vvwt.tm.tournament.internal.dto.draft.DraftSectionResponse}, {@link
 *       de.vvwt.tm.tournament.internal.dto.draft.DraftBreakRequest}, {@link
 *       de.vvwt.tm.tournament.internal.dto.draft.DraftBreakResponse}). Spring Modulith 2.x does NOT
 *       extend {@code tournament::dto} to sub-packages; this separate named interface is required
 *       for the relocated {@code web.DraftController} to access draft wire types cross-module
 *       (E21S19, AC-NEW-DRAFT-DTO-NAMED-INTERFACE, DEC-40 Clause A).
 *   <li>{@code tournament::draft} — Draft value objects (VOs) in {@code tournament.draft.*} ({@link
 *       de.vvwt.tm.tournament.draft.DraftConfig}, {@link de.vvwt.tm.tournament.draft.DraftSection},
 *       {@link de.vvwt.tm.tournament.draft.DraftBreak}, etc.). Required by the relocated {@code
 *       web.DraftController} for the {@code GET} and {@code PUT} handler logic that converts
 *       between VOs and wire DTOs (E21S19, AC-WEB-ALLOWED-DEPS-EXTENDED, DEC-40 Clause A). Named
 *       interface already declared at {@code tournament.draft.package-info.java} by E33S04.
 *   <li>{@code scoring} — controllers that invoke {@code ScoringService} import from the scoring
 *       root package.
 *   <li>{@code photo} — {@code TeamPhotoController} (at {@code infrastructure.web.photo.*},
 *       relocated to {@code web} at E23S04) consumes {@link de.vvwt.tm.photo.PhotoStorageService}.
 *       Added at E23S01 (first photo-track story per DEC-40 Clause A migration cadence).
 *   <li>{@code certificate} — {@code CertificateTemplateController} (relocated from {@code
 *       infrastructure.web.certificate.*} at E23S09) consumes {@link
 *       de.vvwt.tm.certificate.CertificateTemplateService}. Post-story excl.-tenant count = 4
 *       (tournament, scoring, photo, certificate). Still below Trigger α (≥5). Added at E23S09 per
 *       DEC-40 Clause A migration cadence (AC-ALLOWED-DEPS-EXPANDED).
 *   <li>{@code print} — fresh {@code web.PrintController} (E24S06) consumes {@link
 *       de.vvwt.tm.print.LaufzettelAssembler} and {@link
 *       de.vvwt.tm.print.ActivityScheduleAssembler}. Post-story excl.-tenant count = 5 (tournament,
 *       scoring, photo, certificate, print). Trigger-α fires at ≥5; binding verdict L2 stays
 *       (examined at E24S01). Added at E24S06 (AC-WEB-ALLOWEDDEPS-ADD-PRINT).
 *   <li>{@code display} — {@link de.vvwt.tm.web.GlobalExceptionHandler} catches {@link
 *       de.vvwt.tm.display.NoActivePhaseException}; relocated display controllers in {@code web}
 *       consume display-module services (E25S01+). Added at E25S01 per DEC-40 Pattern A migration
 *       cadence.
 *   <li>{@code timer} — {@link de.vvwt.tm.web.GlobalExceptionHandler} catches {@link
 *       de.vvwt.tm.timer.InvalidTimerUrlException} and {@link
 *       de.vvwt.tm.timer.NoActiveTournamentException}; {@code web.TimerController} (E26S03)
 *       consumes {@link de.vvwt.tm.timer.TimerDataService} and returns {@link
 *       de.vvwt.tm.timer.TimerDataResponse} (Pattern A). Added at E26S01 because {@code
 *       GlobalExceptionHandler} was forced to import from {@code timer.*} when {@code
 *       domain.timer.*} was deleted in E26S01 AC-DELETE-LEGACY-FIRST (commit 93c7b57). Post-story
 *       excl.-tenant count = 7 (tournament, scoring, photo, certificate, print, display, timer).
 *       DEC-45 D2 FIRM verdict: L2 stays for E26 per audit (vi) Trigger-(ii) NOT-satisfied.
 *   <li>{@code timer::audio} — {@link de.vvwt.tm.web.GlobalExceptionHandler} catches {@link
 *       de.vvwt.tm.timer.audio.AudioFormatException}, {@link
 *       de.vvwt.tm.timer.audio.AudioSizeLimitException}, and {@link
 *       de.vvwt.tm.timer.audio.AudioStorageException}; {@code web.AudioController} (E11S01)
 *       consumes {@link de.vvwt.tm.timer.audio.AudioStorageService} and uses {@link
 *       de.vvwt.tm.timer.audio.AudioCategory} and {@link de.vvwt.tm.timer.audio.AudioFileMetadata}
 *       (Pattern A). Added at E26S02 — empirical discovery: Spring Modulith 2.x treats {@code
 *       timer.audio} as a separate named module; {@code "timer"} alone does not cover it
 *       (AC-TIMER-AUDIO-NAMED-INTERFACE-CONSIDERATION remediation).
 *   <li>{@code slotopt} — {@link de.vvwt.tm.web.slotopt.SlotOptimizationCancelController} (E27S02,
 *       DEC-40 Clause A) consumes {@link de.vvwt.tm.slotopt.SlotOptimizationJobRegistry} from the
 *       {@code slotopt} module root. Post-story excl.-tenant count = 8 (tournament, scoring, photo,
 *       certificate, print, display, timer, slotopt). DEC-45 D2 FIRM verdict: L2 stays (Trigger-α
 *       not newly satisfied by single addition). Added at E27S02 per DEC-40 Clause A + DEC-45 D2
 *       unconditional pre-approval.
 *   <li>{@code phaselifecycle} — {@link de.vvwt.tm.web.slotopt.SlotOptimizationCancelController}
 *       (E55S05) injects {@link de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository} to set
 *       {@code cancelled=TRUE} on the RUNNING job row and {@link
 *       de.vvwt.tm.phaselifecycle.CancelFlagRegistry} to signal the in-memory cancel mirror (DEC-64
 *       D-10 cooperative cancel). Added at E55S05 per DEC-40 Clause A.
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
            "tournament::activity",
            "tournament::exceptions",
            "tournament::dto",
            "tournament::draft-dto",
            "tournament::draft",
            "scoring",
            "photo",
            "certificate",
            "print",
            "display",
            "timer",
            "timer::audio",
            "slotopt",
            "phaselifecycle"
        })
package de.vvwt.tm.web;
