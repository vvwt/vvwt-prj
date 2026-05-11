package de.vvwt.tm.phaselifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.test.context.ActiveProfiles;

/**
 * Module-structure IT for the {@code phaselifecycle} bounded context — AC-TEST-APPLICATION-MODULES-VERIFY-GREEN-RED.
 *
 * <p>RED-first per DEC-22 Iron Law Pattern B (new code). The RED commit is authored before the
 * {@code de.vvwt.tm.phaselifecycle} package-info.java exists. At RED time, {@code
 * ApplicationModules.of(TournamentManagerApplication.class)} does not discover a module named
 * {@code phaselifecycle}, so the {@code getModuleByName} assertion fails.
 *
 * <p>GREEN state: after the package-info.java with {@code @ApplicationModule(allowedDependencies =
 * {"tournament", "slotopt", "tenant"})} lands, the module is discovered and the assertion passes.
 *
 * <h2>DEC-44 compliance</h2>
 *
 * <p>Uses {@code @SpringBootTest(NONE, classes = TournamentManagerApplication.class)} — the
 * Modulith-verification-only scope has no web surface; NONE is the correct environment per DEC-44
 * (web-surface carve-out applies only to {@code de.vvwt.tm.web} module ITs).
 *
 * <p>Authorizing decisions: DEC-21 (Spring Modulith boundary enforcement), DEC-22 (TDD Iron Law),
 * DEC-44 (IT annotation — NONE for non-web module ITs), DEC-64 D-2 ({@code phaselifecycle} module
 * declaration with the stated allowedDependencies).
 *
 * @since E55S01
 */
@SpringBootTest(
    classes = TournamentManagerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhaseLifecycleModuleStructureIT {

  @Test
  void verifiesApplicationModulesIncludingPhaseLifecycle() {
    ApplicationModules modules = ApplicationModules.of(TournamentManagerApplication.class);
    modules.verify();
    assertThat(modules.getModuleByName("phaselifecycle"))
        .as(
            "The phaselifecycle module must be discovered by ApplicationModules.verify(). "
                + "RED state: package-info.java missing → module not found. "
                + "GREEN state: package-info.java with @ApplicationModule(allowedDependencies = "
                + "{\"tournament\", \"slotopt\", \"tenant\"}) exists.")
        .isPresent();
  }
}
