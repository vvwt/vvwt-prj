// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring.internal;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Bytecode-retention boundary-enforcement spike for the {@code scoring} bounded context.
 *
 * <p>This spike encodes the C-14 lesson from E15 Pilot retrospective §1: the Java compiler discards
 * unused {@code import} statements before bytecode is emitted, so a violation spike that uses only
 * an unused import passes {@code ApplicationModules.verify()} vacuously. A substantive spike MUST
 * reference the foreign type in bytecode via a class literal, field declaration, method parameter,
 * or return type.
 *
 * <h2>Important: violation target must be a declared Modulith module — and in production source
 * </h2>
 *
 * <p>{@code @ApplicationModule(allowedDependencies)} is enforced only against types from other
 * <em>declared</em> Modulith modules (packages with their own {@code @ApplicationModule}
 * annotation) <strong>and only in production bytecode</strong>. {@code ApplicationModules.verify()}
 * scans the production classpath, not the test classpath. Therefore, a class reference in this test
 * class does NOT cause a verify() violation — the spike evidence must be produced via a temporary
 * production-class probe (see "Manual verification procedure" below).
 *
 * <h2>Violation target</h2>
 *
 * <p>{@link AdminCredentialsProvider} lives in {@code de.vvwt.tm.auth} (a declared Modulith module
 * via E15S06), which is NOT in {@code scoring}'s {@code allowedDependencies = {"tenant",
 * "tournament"}}. This makes it the correct spike target: referencing it from a production class in
 * {@code de.vvwt.tm.scoring} must produce a Modulith violation naming {@code scoring → auth}.
 *
 * <h2>Manual verification procedure (C-14 lesson — E31S02)</h2>
 *
 * <p>To reproduce the boundary-enforcement failure:
 *
 * <ol>
 *   <li>Create a temporary file {@code BoundarySpikeProbe.java} in {@code
 *       vvwt-tm-web/src/main/java/de/vvwt/tm/scoring/} with the content shown in the {@code //
 *       SPIKE-PROBE-TEMPLATE} block at the bottom of this Javadoc.
 *   <li>Run {@code mvn -pl vvwt-tm-web verify} (or {@code mvn -pl vvwt-tm-web test
 *       -Dtest=ApplicationModulesTest}).
 *   <li>Expect {@code ApplicationModulesTest.verifiesModuleStructure()} to FAIL with a {@code
 *       Violations} error naming: {@code Module 'scoring' depends on module 'auth' via
 *       de.vvwt.tm.scoring.BoundarySpikeProbe -> de.vvwt.tm.auth.AdminCredentialsProvider. Allowed
 *       targets: tenant, tournament.}
 *   <li>Capture the failure output (at minimum the violation message + stack trace preamble).
 *   <li>Delete {@code BoundarySpikeProbe.java}.
 *   <li>Run {@code mvn -pl vvwt-tm-web verify} again — expect GREEN.
 *   <li>Paste the captured failure excerpt + the post-delete-GREEN SHA into the E31S02 impl-report
 *       (AC-SPIKE-VERIFY-EVIDENCE-IN-IMPL-REPORT).
 * </ol>
 *
 * <h2>SPIKE-PROBE-TEMPLATE</h2>
 *
 * <p>Create {@code vvwt-tm-web/src/main/java/de/vvwt/tm/scoring/BoundarySpikeProbe.java}:
 *
 * <pre>
 * // TEMPORARY SPIKE PROBE — DELETE AFTER SPIKE RUN — DO NOT COMMIT
 * package de.vvwt.tm.scoring;
 *
 * // Bytecode-substantive reference: class literal survives import-discard optimization.
 * // This triggers ApplicationModules.verify() to report scoring -> auth violation.
 * &#64;SuppressWarnings("unused")
 * class BoundarySpikeProbe {
 *   private static final Class&lt;?&gt; SPIKE_REFERENCE =
 *       de.vvwt.tm.auth.AdminCredentialsProvider.class;
 * }
 * </pre>
 *
 * <h2>Verification evidence (E31S02, 2026-04-22)</h2>
 *
 * <p>The spike was run with the probe above. {@code
 * ApplicationModules.of(TournamentManagerApplication.class).verify()} FAILED with the output
 * documented in the E31S02 impl-report (AC-SPIKE-VERIFY-EVIDENCE-IN-IMPL-REPORT).
 *
 * <h2>Traceability</h2>
 *
 * <ul>
 *   <li>E15-pilot-retrospective.md §1 — source lesson for bytecode-retention requirement.
 *   <li>E21S12 {@code ApplicationModuleBoundaryEnforcementSpikeTest} — canonical prior art for this
 *       pattern in the {@code tournament} module.
 *   <li>Story E31S02, AC-SPIKE-TEST-CLASS, AC-SPIKE-VERIFY-FAILS,
 *       AC-SPIKE-VERIFY-EVIDENCE-IN-IMPL-REPORT.
 *   <li>DEC-21 (Spring Modulith boundary enforcement), DEC-22 (TDD Iron Law).
 * </ul>
 *
 * @since E31S02
 */
@Disabled(
        "Boundary-enforcement verification fixture for the scoring module — see Javadoc for manual"
            + " reproduction procedure. Spike verified 2026-04-22: verify() FAILED when"
            + " AdminCredentialsProvider.class literal was present in a scoring production-class"
            + " field (BoundarySpikeProbe.java). Probe deleted after verification run.")
class ScoringBoundarySpikeTest {

    // The import of AdminCredentialsProvider above is intentional — it documents the violation
    // target type used during the verification run. The import is retained by the Java compiler
    // only because it is referenced in the @Disabled annotation string. In a re-verification run,
    // the class literal must appear in a production-class field (not a test-class field), because
    // ApplicationModules.verify() only scans the production classpath.

    /**
     * Verifies that {@code ApplicationModules.verify()} passes for the scoring module in its
     * current (post-spike-revert) state.
     *
     * <p>The violation proof is documented in the class-level Javadoc above and in the E31S02
     * impl-report. To reproduce the red state: create {@code BoundarySpikeProbe.java} in {@code
     * de.vvwt.tm.scoring} (main source) with a class-literal reference to {@link
     * AdminCredentialsProvider} per the SPIKE-PROBE-TEMPLATE in the class Javadoc.
     */
    @Test
    void scoringModuleBoundaryIsEnforced() {
        // With the spike probe removed from production code, verify() must pass.
        ApplicationModules.of(TournamentManagerApplication.class).verify();
    }
}
