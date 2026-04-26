/**
 * Public API of the {@code photo} bounded context (E23S01).
 *
 * <p>This package is the contract surface for the {@code photo} module. Types declared here are
 * consumable by other Modulith modules; types in {@code de.vvwt.tm.photo.internal} are
 * implementation details and MUST NOT be accessed by any other module.
 *
 * <h2>Allowed dependencies (DEC-21, DEC-35, DEC-40)</h2>
 *
 * <p>The {@code photo} context may depend on:
 *
 * <ul>
 *   <li>{@code tenant} — DB-per-Tenant DataSource routing (DEC-20).
 *   <li>{@code tournament} — {@link de.vvwt.tm.tournament.Team}, {@link
 *       de.vvwt.tm.tournament.TeamRepository}, and {@link
 *       de.vvwt.tm.tournament.TournamentRepository} are consumed by {@link
 *       de.vvwt.tm.photo.internal.DefaultPhotoStorageService} for tenant-scoped validation
 *       (empirically verified 2026-04-23: PhotoStorageServiceImpl imports tournament.Team +
 *       TeamRepository + TournamentRepository).
 * </ul>
 *
 * <p>No other bounded contexts are allowed. REST controllers that consume {@link
 * de.vvwt.tm.photo.PhotoStorageService} reside in {@code de.vvwt.tm.web} per DEC-40
 * (Primary-Adapter-Isolation); they are NOT in this module.
 *
 * <h2>History: E23S01 parallel-phase + E36S01 Q-1a rebuild</h2>
 *
 * <p>E23S01 established this module during the parallel phase (until E23S05 Cutover-1), at which
 * point the legacy photo domain package was deleted. E36S01 completed the Q-1a TDD rebuild of all
 * photo-domain artefacts at this canonical FQN (Option γ — delete-then-rebuild at same FQN per
 * DEC-22 Iron Law). The {@code @ComponentScan} exclusion introduced during parallel-phase was
 * removed at E23S05 Cutover-1.
 *
 * <p>Authorizing decisions: DEC-21 (Spring Modulith layout), DEC-35 (service interface in public
 * package, implementation in {@code .internal}; {@code Default*Service} naming canon), DEC-40
 * (Primary-Adapter-Isolation — controllers in {@code web}, NOT here).
 *
 * @since E23S01
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"tenant", "tournament"})
package de.vvwt.tm.photo;
