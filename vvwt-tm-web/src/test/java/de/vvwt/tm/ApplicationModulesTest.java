// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Baseline module-structure verification for the Tournament Manager.
 *
 * <p>Executes {@link ApplicationModules#verify()} against the pre-reconstruction codebase. At this
 * stage ({@code E13S01}) the project has one implicit module ({@code de.vvwt.tm}), so {@code
 * verify()} passes trivially — this confirms the Modulith machinery runs and understands the
 * project. Boundary enforcement becomes meaningful once bounded-context packages are declared in
 * E14 and beyond.
 *
 * <p>Per DEC-21, this test MUST NOT be {@code @Disabled} or ignored. Boundary violations introduced
 * by future context migrations (E14, E15, ...) MUST fail this test.
 *
 * <p>Per DEC-22 (TDD Iron Law), the red state is preserved in the previous git commit. Reviewers
 * can reproduce the red state by checking out that commit and running:
 *
 * <pre>{@code mvn -pl vvwt-tm-web test}</pre>
 *
 * <p>Story: E13S01 — Wave-1 Modulith bootstrap (DEC-21, DEC-22).
 */
class ApplicationModulesTest {

    @Test
    void verifiesModuleStructure() {
        ApplicationModules.of(TournamentManagerApplication.class).verify();
    }
}
