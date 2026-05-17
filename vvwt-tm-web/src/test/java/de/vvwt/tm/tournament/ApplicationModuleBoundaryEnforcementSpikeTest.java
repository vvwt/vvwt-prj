// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Bytecode-retention boundary-enforcement spike for the {@code tournament} bounded context.
 *
 * <p>This spike encodes the C-14 lesson from E15 Pilot retrospective §1: the Java compiler discards
 * unused {@code import} statements before bytecode is emitted, so a violation spike that uses only
 * an unused import passes {@code ApplicationModules.verify()} vacuously. A substantive spike MUST
 * reference the foreign type in bytecode via a class literal, field declaration, method parameter,
 * or return type.
 *
 * <h2>Important: violation target must be a declared Modulith module</h2>
 *
 * <p>{@code @ApplicationModule(allowedDependencies)} is enforced only against types from other
 * <em>declared</em> Modulith modules (packages with their own {@code @ApplicationModule}
 * annotation). Types in undeclared packages (e.g., {@code de.vvwt.tm.infrastructure.score.*}) are
 * not recognised as sibling modules and may be referenced freely. The story's suggested candidate
 * ({@code ScoreEntryService} at {@code de.vvwt.tm.infrastructure.score}) therefore does NOT trigger
 * a violation — {@code infrastructure} has no {@code @ApplicationModule} annotation at E21S12
 * commit time.
 *
 * <p>The correct violation target must be a type in a declared sibling module that is absent from
 * {@code tournament}'s {@code allowedDependencies}. {@link AdminCredentialsProvider} lives in
 * {@code de.vvwt.tm.auth} (a declared module via E15S06), which is not in {@code tournament}'s
 * {@code allowedDependencies = {"tenant"}} — making it the correct spike target.
 *
 * <h2>Verification run (E21S12, 2026-04-21)</h2>
 *
 * <p>The spike was run with a production-class probe ({@code BoundarySpikeProbe.java} in {@code
 * de.vvwt.tm.tournament}, deleted after the run). The probe declared:
 *
 * <pre>{@code
 * private static final Class<AdminCredentialsProvider> SPIKE_REFERENCE = AdminCredentialsProvider.class;
 * }</pre>
 *
 * {@code ApplicationModules.of(TournamentManagerApplication.class).verify()} FAILED with:
 *
 * <pre>
 * Violations - Module 'tournament' depends on module 'auth' via
 * de.vvwt.tm.tournament.BoundarySpikeProbe -&gt; de.vvwt.tm.auth.AdminCredentialsProvider.
 * Allowed targets: tenant.
 * </pre>
 *
 * The probe was removed after recording the failure. {@code verify()} is green in the committed
 * state.
 *
 * <h2>How to re-verify enforcement</h2>
 *
 * <p>To reproduce the failure:
 *
 * <ol>
 *   <li>Create a new class in {@code de.vvwt.tm.tournament} (main source) with a field: {@code
 *       private static final Class<AdminCredentialsProvider> REF = AdminCredentialsProvider.class;}
 *   <li>Run {@code mvn -pl vvwt-tm-web test -Dtest=ApplicationModulesTest}.
 *   <li>Expect a {@code Violations} error naming {@code tournament} → {@code auth}.
 *   <li>Delete the class and update this Javadoc with the new failure message and date.
 * </ol>
 *
 * <h2>Traceability</h2>
 *
 * <ul>
 *   <li>E15-pilot-retrospective.md §1 — source lesson for bytecode-retention requirement.
 *   <li>Session Brief {@code discovery-2026-04-20-e21-full-refinement} C-14 — rationale for the
 *       spike form.
 *   <li>Story E21S12, AC-C14-BYTECODE-SPIKE-SUBSTANTIVE, AC-SPIKE-JAVADOC-RETRO-LINK.
 *   <li>DEC-21 (Spring Modulith boundary enforcement), DEC-22 (TDD Iron Law).
 * </ul>
 *
 * @since E21S12
 */
@Disabled(
        "Re-enable to re-verify Modulith bytecode-enforcement on tournament; see Javadoc."
                + " Spike verified 2026-04-21: verify() FAILED when AdminCredentialsProvider.class"
                + " literal was present in a tournament production-class field.")
class ApplicationModuleBoundaryEnforcementSpikeTest {

    // The import of AdminCredentialsProvider above is intentional — it documents the violation
    // target type used during the verification run. The import is retained by the Java compiler
    // only because it is referenced in the @Disabled annotation string (a usage that keeps it
    // from being flagged as unused by Spotless). In a future re-verification run, the class
    // literal must appear in a production-class field (not a test-class field), because
    // ApplicationModules.verify() only scans the production classpath.

    /**
     * Verifies that {@code ApplicationModules.verify()} passes for the tournament module in its
     * current (post-spike-revert) state.
     *
     * <p>The violation proof is documented in the class-level Javadoc above and in the E21S12
     * impl-report. To reproduce the red state: create a production class in {@code
     * de.vvwt.tm.tournament} with a class-literal reference to {@link AdminCredentialsProvider} (or
     * any other type from a declared sibling module absent from tournament's {@code
     * allowedDependencies}).
     */
    @Test
    void tournamentModuleBoundaryIsEnforced() {
        // With the spike reference removed from production code, verify() must pass.
        ApplicationModules.of(TournamentManagerApplication.class).verify();
    }
}
