/**
 * Public API of the {@code infoportal} bounded context.
 *
 * <p>This package is the contract surface for the {@code infoportal} module — the TM-side publisher
 * integration for the Public Participant Info Service (PPIS, {@code vvwt-info}) per DEC-42 and E38.
 * Types declared here are consumable by other Modulith modules; types in {@code
 * de.vvwt.tm.infoportal.internal} (if and when introduced) are implementation details and MUST NOT
 * be accessed by any other module.
 *
 * <h2>Bounded-context responsibility</h2>
 *
 * <p>The {@code infoportal} context owns the TM-side lifecycle of publishing tournament state to
 * the PPIS:
 *
 * <ul>
 *   <li>Asymmetric-key management ({@link de.vvwt.tm.infoportal.Ed25519KeypairManager}) — keypair
 *       generation, persistence, and signing per DEC-6 / DEC-43.
 *   <li>Publisher service ({@link de.vvwt.tm.infoportal.InfoPortalPublisherService}) — tenant and
 *       tournament registration, delta publish, snapshot recovery, and algorithm-deprecation
 *       handling.
 *   <li>State persistence ({@link de.vvwt.tm.infoportal.InfoPortalStateDao}, {@link
 *       de.vvwt.tm.infoportal.InfoPortalStateRecord}) — per-tournament publish state, sequence
 *       numbers, and registration tokens.
 *   <li>JCS canonicalization ({@link de.vvwt.tm.infoportal.TmJcsCanonicalizer}) — JSON
 *       Canonicalization Scheme for signed payloads.
 *   <li>Configuration ({@link de.vvwt.tm.infoportal.InfoPortalProperties}, {@link
 *       de.vvwt.tm.infoportal.InfoPortalConfig}).
 * </ul>
 *
 * <h2>Allowed dependencies (DEC-21)</h2>
 *
 * <p>Empirical import audit (E45S02, 2026-05-03): {@code grep -rn 'import de\.vvwt\.tm\.'} on
 * production sources in this package returned zero cross-module imports. All imports are from
 * {@code de.vvwt.info.dto.*} (the cross-subsystem DTO contract module, {@code vvwt-info-dto}, which
 * is NOT a Modulith bounded-context module and is therefore not listed in {@code
 * allowedDependencies}), the Spring framework, and the Java standard library. No other TM
 * bounded-context module is imported.
 *
 * <p>{@code allowedDependencies = {"tournament", "tournament::events"}} as of E62S01 (DEC-21):
 *
 * <ul>
 *   <li>{@code "tournament"} — the root {@code de.vvwt.tm.tournament} module (Team, Match,
 *       TeamAvatar, Phase, PhaseBreak, Tournament, and their repositories) consumed by the {@link
 *       de.vvwt.tm.infoportal.TournamentSnapshotBuilder} (E62S01).
 *   <li>{@code "tournament::events"} — the {@code de.vvwt.tm.tournament.events} {@link
 *       org.springframework.modulith.NamedInterface} sub-package. Declared here so that E62S03's
 *       {@code @TransactionalEventListener} inherits a ready boundary edge without triggering an
 *       additional {@code allowedDependencies} edit at that story's commit. Spring Modulith {@code
 *       verify()} does not fail on a declared-but-not-yet-consumed dependency entry (per DEC-21
 *       Notes / DEC-40 {@code tournament::exceptions} precedent).
 * </ul>
 *
 * <h2>DEC-35 internal split</h2>
 *
 * <p>As of E62S01: {@link de.vvwt.tm.infoportal.TournamentSnapshotBuilder} (public interface) and
 * {@code de.vvwt.tm.infoportal.internal.DefaultTournamentSnapshotBuilder} (implementation) follow
 * the DEC-35 pragmatic-hexagonal split. Legacy classes at the package root (E38/E45S02 scope)
 * retain their deferred classification per the E45S02 rationale pending a future Wave-3 cleanup.
 *
 * <p>Authorizing decisions: DEC-21 (Spring Modulith layout + {@code allowedDependencies} contract),
 * DEC-35 (package layout — interface public, impl in {@code .internal}), DEC-40 (Primary-Adapter-
 * Isolation), DEC-42 (PPIS as third subsystem — {@code vvwt-info-dto} is the only cross-subsystem
 * dependency from TM side), DEC-58 (universal interface mandate for {@code @Service} beans).
 *
 * @since E45S02 (module declaration); E62S01 (allowedDependencies expansion +
 *     TournamentSnapshotBuilder)
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"tournament", "tournament::events"})
package de.vvwt.tm.infoportal;
