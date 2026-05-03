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
 * <p>Included: {@code tenant} — {@link de.vvwt.tm.display.internal.DefaultDisplayOverviewService}
 * now injects {@link de.vvwt.tm.tenant.TenantContext} to obtain the current tenant UUID (replacing
 * the removed {@code device.getTenantId()} column-discriminator call per E45S06 / DEC-50). {@code
 * web} (display module does not depend on the web module — data flows display → web, not vice
 * versa).
 *
 * @see DEC-21
 * @see DEC-35
 * @see E25S01
 * @since E25S01
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"tournament", "tournament::exceptions", "tenant"})
package de.vvwt.tm.display;
