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
 *       etc. Required because Spring Modulith named-interface sub-packages are NOT accessible via
 *       the root-module declaration alone.
 *   <li>{@code tournament::dto} — HTTP request/response DTOs in {@code tournament.internal.dto.*}.
 *       Exposed as a named interface so that relocated controllers in {@code web} can reference
 *       these wire types without violating Modulith boundary rules (E22S07, DEC-40 Clause A).
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
 *   <li>{@code display} — {@link de.vvwt.tm.web.GlobalExceptionHandler} catches
 *       {@link de.vvwt.tm.display.NoActivePhaseException}; relocated display controllers
 *       in {@code web} consume display-module services (E25S01+).
 *       Added at E25S01 per DEC-40 Pattern A migration cadence.
 *   <li>{@code timer} — {@link de.vvwt.tm.web.GlobalExceptionHandler} catches
 *       {@link de.vvwt.tm.timer.InvalidTimerUrlException} and
 *       {@link de.vvwt.tm.timer.NoActiveTournamentException}; {@code web.TimerController}
 *       (E26S03) consumes {@link de.vvwt.tm.timer.TimerDataService} and returns
 *       {@link de.vvwt.tm.timer.TimerDataResponse} (Pattern A).
 *       Added at E26S01 because {@code GlobalExceptionHandler} was forced to import from
 *       {@code timer.*} when {@code domain.timer.*} was deleted in E26S01
 *       AC-DELETE-LEGACY-FIRST (commit 93c7b57). Post-story excl.-tenant count = 7
 *       (tournament, scoring, photo, certificate, print, display, timer). DEC-45 D2 FIRM verdict:
 *       L2 stays for E26 per audit (vi) Trigger-(ii) NOT-satisfied.
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
            "scoring",
            "photo",
            "certificate",
            "print",
            "display",
            "timer"
        })
package de.vvwt.tm.web;
