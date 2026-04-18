package de.vvwt.tm;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Baseline module-structure verification for the Tournament Manager.
 *
 * <p>NOTE — RED STATE COMMIT (DEC-22 AC1): This version of the file is intentionally broken.
 * It references {@code TournamentManagerApplicationStub} which does not exist, causing a
 * compilation failure. This demonstrates the "red" state of the TDD cycle.
 *
 * <p>Running {@code mvn -pl vvwt-tm-web test} at this commit hash produces a non-zero exit.
 * Per DEC-22, reviewers can verify the red state by checking out this commit.
 *
 * <p>The NEXT commit fixes this by referencing {@link TournamentManagerApplication}.
 */
class ApplicationModulesTest {

    @Test
    void verifiesModuleStructure() {
        // RED STATE: TournamentManagerApplicationStub does not exist — compilation fails here.
        // This is the replayable red state required by DEC-22 / AC1.
        ApplicationModules.of(TournamentManagerApplicationStub.class).verify();
    }
}
