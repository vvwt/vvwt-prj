// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test — verifies the Spring application context loads without errors.
 *
 * <p>RED-first per DEC-22 / AC-DISPATCHER-APPLICATION-CLASS: this test is written before {@link
 * DispatcherApplication} exists; it fails with ClassNotFoundException until the production class is
 * created (TDD Step 3→4).
 *
 * <p>Spec: E37S04 AC-DISPATCHER-APPLICATION-CLASS.
 */
@SpringBootTest
class DispatcherApplicationTest {

    @Test
    void contextLoads() {
        // If the context loads, this test passes (no assertion required).
    }
}
