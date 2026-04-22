package de.vvwt.tm.tournament.governance;

import de.vvwt.tm.tournament.internal.DefaultTournamentRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * DEC-36 enforcement validation fixture (E31S01, AC-QA-SKILL-CROSS-PACKAGE-VALIDATION).
 *
 * <h2>Purpose</h2>
 *
 * <p>This test class intentionally violates DEC-36 (cross-package test typing rule): it is in
 * {@code de.vvwt.tm.tournament.governance} (different package from {@code
 * de.vvwt.tm.tournament.internal}) and references the concrete implementation class {@link
 * DefaultTournamentRepository} — a {@code Default*Repository} impl — directly, rather than the
 * public interface {@code de.vvwt.tm.tournament.TournamentRepository}.
 *
 * <p>This mirrors the permanent-disabled-checked-in governance spike pattern used by E15S06,
 * E21S12, and E31S02. The class is permanently {@code @Disabled} and must NEVER be removed; it
 * exists solely to demonstrate that the {@code qa-review} skill's cross-package check (Step 6, DEC-
 * 36, operationalized by E31S01) fires a HIGH-severity finding when this violation is present.
 *
 * <h2>How to reproduce the qa-review HIGH-severity finding</h2>
 *
 * <ol>
 *   <li>Remove the {@code @Disabled} annotation from this class.
 *   <li>In the delivery workflow, execute the {@code qa-review} skill (Step 7 of {@code
 *       delivery-loop.workflow.md}) with this file in scope.
 *   <li>The skill's Step 6 (Cross-Package Test Typing Check) scans the modified test class,
 *       identifies {@code DefaultTournamentRepository} as a cross-package implementation reference
 *       (this class is in {@code governance}, the referenced class is in {@code
 *       tournament.internal}), and emits a HIGH-severity finding.
 *   <li>Expected output (paraphrase): "HIGH: CrossPackageInterfaceRuleSpikeTest.java references
 *       de.vvwt.tm.tournament.internal.DefaultTournamentRepository from a different package
 *       (de.vvwt.tm.tournament.governance). DEC-36 requires cross-package test code to reference
 *       the public interface de.vvwt.tm.tournament.TournamentRepository instead."
 *   <li>Re-add the {@code @Disabled} annotation to restore the fixture to its committed state.
 * </ol>
 *
 * <h2>Evidence captured during E31S01 qa-review reproduction</h2>
 *
 * <pre>
 * qa-review Step 6 — Cross-Package Test Typing Check
 * File: CrossPackageInterfaceRuleSpikeTest.java (package: de.vvwt.tm.tournament.governance)
 * Reference: DefaultTournamentRepository (package: de.vvwt.tm.tournament.internal) [DIFFERENT PACKAGE]
 * Type: implementation class (Default* naming pattern)
 * Interface: de.vvwt.tm.tournament.TournamentRepository
 * Severity: HIGH
 * Finding: cross-package test references Default* impl; must reference public interface instead (DEC-36)
 * Action: replace DefaultTournamentRepository field/param type with TournamentRepository; FAIL
 * </pre>
 *
 * @see <a href="DEC-36">DEC-36 — Cross-package test typing rule (DEC-22 amendment)</a>
 * @see <a href="DEC-35">DEC-35 — Module package layout: services + custom repos as interfaces</a>
 * @see <a href="E31S01">E31S01 — DEC-36 enforcement operationalization</a>
 */
@Disabled(
        "DEC-36 enforcement validation fixture — remove @Disabled to reproduce qa-review"
                + " HIGH-severity finding (see Javadoc for reproduction steps)")
class CrossPackageInterfaceRuleSpikeTest {

    /**
     * This field intentionally declares the concrete {@code Default*} implementation class from a
     * different package ({@code tournament.internal}), violating DEC-36. The correct type for
     * cross-package reference is the public interface {@code de.vvwt.tm.tournament
     * .TournamentRepository}.
     *
     * <p>DO NOT FIX THIS FIELD — the violation is the point of this fixture.
     */
    @SuppressWarnings("unused")
    private DefaultTournamentRepository violatingImplReference;

    /**
     * Placeholder test body — content is irrelevant since the class is {@code @Disabled} and the
     * DEC-36 violation is at the field declaration level (not the test logic level).
     */
    @Test
    void thisTestIsDisabledSeeClassJavadoc() {
        // @Disabled at class level — this body never executes.
        // The DEC-36 violation is the field type above, not anything in this method.
    }
}
