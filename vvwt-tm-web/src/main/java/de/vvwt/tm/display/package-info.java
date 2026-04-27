/**
 * Display bounded-context module for the Tournament Manager (DEC-21, DEC-35, E25S01).
 *
 * <p>This package is the public API of the {@code display} Spring Modulith module. It exposes the
 * {@link de.vvwt.tm.display.DisplayOverviewService} port interface and the {@link
 * de.vvwt.tm.display.NoActivePhaseException} domain exception. The implementation {@link
 * de.vvwt.tm.display.internal.DefaultDisplayOverviewService} lives in {@code display.internal}.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <ul>
 *   <li>{@code tournament} — 8 repository interfaces (DeviceRepository, TournamentRepository,
 *       PhaseRepository, MatchRepository, TeamAvatarRepository, TeamAvatarRatingRepository,
 *       TeamRepository, SetResultRepository) + entity types; all in tournament ROOT package per
 *       audit (i) empirical verification (E25 Discovery, commit {@code 1aded97}).
 *   <li>{@code tournament::exceptions} — {@code UnauthorizedException} thrown by token validation.
 * </ul>
 *
 * <p>NOT included: {@code tenant} (no direct compile-time tenant import; tenant context resolved by
 * the HTTP interceptor outside domain service scope per audit (iii)); {@code web} (display module
 * does not depend on the web module — data flows display → web, not vice versa).
 *
 * <p>Exactly 2 array entries per AC-DISPLAY-ALLOWED-DEPS-EXACTLY-2.
 *
 * @see DEC-21
 * @see DEC-35
 * @see E25S01
 * @since E25S01
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"tournament", "tournament::exceptions"})
package de.vvwt.tm.display;
