// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.phaselifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Consumer-import IT for the 5 public interfaces of the {@code phaselifecycle} module —
 * AC-TEST-INTERFACE-CONSUMER-IMPORT-RED.
 *
 * <p>RED-first per DEC-22 Iron Law Pattern B (new code). The RED commit is authored before any of
 * the 5 interface declarations exist. At RED time this class FAILS TO COMPILE because the import
 * targets ({@code PhaseLifecycleOrchestrator}, {@code PhaseLifecycleJobRepository}, {@code
 * WorkerRegistry}, {@code JobDrainService}, {@code CancelFlagRegistry}) are not yet declared.
 *
 * <p>GREEN state: after all 5 interface + {@code Default*} implementation classes land, the Spring
 * context discovers and auto-wires each bean. All 5 {@code assertThat(...).isNotNull()} assertions
 * pass.
 *
 * <h2>DEC-44 compliance</h2>
 *
 * <p>Uses {@code @SpringBootTest(NONE, classes = TournamentManagerApplication.class)} — the scope
 * is interface autowiring only; no controller surface is exercised in this Story. DEC-44 NONE
 * environment is correct for bounded-context-module ITs with no web surface.
 *
 * <h2>DEC-58 compliance</h2>
 *
 * <p>This IT acts as the machine-checkable DEC-58 interface-mandate compliance smoke test for all 5
 * beans in the {@code phaselifecycle} module: each interface ({@code Default*} impl) must be
 * discoverable as a Spring bean and auto-wirable by interface type.
 *
 * <p>Authorizing decisions: DEC-35 (interface-in-module-root), DEC-44 (IT annotation — NONE),
 * DEC-58 (universal interface mandate), DEC-64 D-14 (five bean enumeration).
 *
 * @since E55S01
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhaseLifecycleInterfacesIT {

    @Autowired PhaseLifecycleOrchestrator phaseLifecycleOrchestrator;

    @Autowired PhaseLifecycleJobRepository phaseLifecycleJobRepository;

    @Autowired WorkerRegistry workerRegistry;

    @Autowired JobDrainService jobDrainService;

    @Autowired CancelFlagRegistry cancelFlagRegistry;

    @Test
    void allFiveInterfacesAreSpringDiscoverable() {
        assertThat(phaseLifecycleOrchestrator)
                .as("PhaseLifecycleOrchestrator must be auto-wirable (DEC-58, DEC-64 D-14)")
                .isNotNull();
        assertThat(phaseLifecycleJobRepository)
                .as("PhaseLifecycleJobRepository must be auto-wirable (DEC-58, DEC-64 D-14)")
                .isNotNull();
        assertThat(workerRegistry)
                .as("WorkerRegistry must be auto-wirable (DEC-58, DEC-64 D-14)")
                .isNotNull();
        assertThat(jobDrainService)
                .as("JobDrainService must be auto-wirable (DEC-58, DEC-64 D-14)")
                .isNotNull();
        assertThat(cancelFlagRegistry)
                .as("CancelFlagRegistry must be auto-wirable (DEC-58, DEC-64 D-14)")
                .isNotNull();
    }
}
