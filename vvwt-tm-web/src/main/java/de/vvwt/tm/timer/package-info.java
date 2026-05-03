/**
 * Public API of the {@code timer} bounded context.
 *
 * <p>This package is the contract surface for the {@code timer} Modulith module (DEC-21 + DEC-35).
 * Types declared here are consumable by other Modulith modules; types in {@code
 * de.vvwt.tm.timer.internal} are implementation details and MUST NOT be accessed by any other
 * module.
 *
 * <p>The {@code timer.audio} sub-package ({@code de.vvwt.tm.timer.audio.*}) is a PUBLIC sub-package
 * of this module (not a separate Modulith module) per user decision option A (2026-04-27). Per
 * empirical E26S02 delivery finding: Spring Modulith 2.x treats sub-packages as separate named
 * modules — cross-module consumers reaching {@code timer.audio.*} types MUST declare {@code
 * "timer::audio"} as a named interface (not just {@code "timer"}). {@code timer::audio} named
 * interface is declared here per AC-TIMER-AUDIO-NAMED-INTERFACE-CONSIDERATION remediation. This
 * corrects the Discovery assumption ("no separate named interface is needed").
 *
 * <h2>Allowed dependencies (DEC-21, DEC-40 Clause A per-entry-justification)</h2>
 *
 * <ul>
 *   <li>{@code tournament} — {@code DefaultTimerDataService} imports 13 tournament root-package
 *       types: {@code Tournament}, {@code TournamentRepository}, {@code Phase}, {@code
 *       PhaseRepository}, {@code Match}, {@code MatchRepository}, {@code PhaseBreak}, {@code
 *       PhaseBreakRepository}, {@code PhaseBreakConfig}, {@code PhaseConfig}, {@code
 *       TimelineCalculationService}, {@code TimelineEntry}, {@code TimelineEntryType} — all at
 *       {@code de.vvwt.tm.tournament.*} root.
 * </ul>
 *
 * <p>{@code tournament::exceptions} is NOT included. Empirical inspection of legacy {@code
 * domain.timer.TimerDataService} (lines 1-29) confirmed ZERO {@code tournament.exceptions.*}
 * imports. Delivery grep on new {@code DefaultTimerDataService} source confirmed same. Omitted per
 * AC-TIMER-ALLOWED-DEPS-TOURNAMENT-ONLY + DEC-40 "per-entry justification (mandatory)" principle
 * (no allowedDependencies-bloat).
 *
 * <p>{@code tenant} — now included. {@code DefaultTimerDataService} injects {@link
 * de.vvwt.tm.tenant.TenantContext} to obtain the current tenant UUID for the STOMP routing key
 * field in {@link de.vvwt.tm.timer.TimerDataResponse#tenantId} (preserved per E45S06 allowlist).
 * Previously omitted; added by E45S06 when {@code tournament.getTenantId()} was removed per DEC-50.
 *
 * <p>Reconstruction of legacy timer domain package (formerly at the domain.timer package location,
 * no {@code @ApplicationModule} declaration) via D-7 Coexistence Option γ (E26S01). Story E26S01.
 *
 * @since E26S01
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"tournament", "tenant"})
package de.vvwt.tm.timer;
