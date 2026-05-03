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
 * <p>Consequently, {@code allowedDependencies = {}} (empty) at this commit. When future E38 Phase
 * 2+ stories add cross-module integration (e.g., infoportal subscribing to tournament-context
 * events for snapshot publication), {@code allowedDependencies} expands at that story's commit per
 * DEC-21.
 *
 * <h2>DEC-35 internal split (deferred — E45S02 scope boundary)</h2>
 *
 * <p>All current {@code infoportal} classes reside at the package root. The DEC-35 pragmatic-
 * hexagonal split (service interfaces in public package, implementations in {@code .internal}) is
 * deferred to a follow-up story or Wave-3 cleanup. S02 ships the module declaration only. Candidate
 * for future {@code .internal} relocation: {@link de.vvwt.tm.infoportal.Ed25519KeypairManager}
 * (implementation detail). See {@code impl-report.md} for the classification rationale.
 *
 * <p>Authorizing decisions: DEC-21 (Spring Modulith layout + {@code allowedDependencies} contract),
 * DEC-35 (package layout; {@code .internal} split deferred per AC-DEC35-INTERNAL-CLASSIFICATION-
 * DEFERRED), DEC-40 (Primary-Adapter-Isolation — REST controller {@link
 * de.vvwt.tm.infoportal.InfoPortalStatusController} resides in {@code de.vvwt.tm.web} scope; the
 * controller class is currently at the infoportal root but routes through the web module per
 * DEC-40), DEC-42 (PPIS as third subsystem — {@code vvwt-info-dto} is the only cross-subsystem
 * dependency from TM side).
 *
 * @since E45S02
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
package de.vvwt.tm.infoportal;
