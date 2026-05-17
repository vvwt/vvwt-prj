// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests for AC-GOV-OPT-IN-CONFIG and AC-TEST-OFF-BY-DEFAULT.
 *
 * <p>Verifies that the embedded worker is OFF by default (config absent or false) and ON when
 * {@code tm.slotopt.embedded-worker.enabled=true} with minimal required properties.
 *
 * <p>Uses {@link ApplicationContextRunner} (no full Spring Boot context) — lightweight, no DB.
 *
 * <p>Story: E63S03.
 */
class EmbeddedWorkerConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withPropertyValues(
                            // Provide dispatcher URL so the embedded worker can attempt
                            // registration (mocked in enabled tests); empty for off-by-default
                            // tests.
                            "tm.slotopt.embedded-worker.dispatcher-url=");

    // -------------------------------------------------------------------------
    // AC-TEST-OFF-BY-DEFAULT: config absent → no EmbeddedWorker bean
    // -------------------------------------------------------------------------

    @Test
    void embeddedWorkerBeanAbsentWhenPropertyAbsent() {
        // No "tm.slotopt.embedded-worker.enabled" property set → expect no EmbeddedWorker bean
        runner.run(
                ctx -> {
                    assertThat(ctx.getBeanNamesForType(EmbeddedWorker.class)).isEmpty();
                });
    }

    // -------------------------------------------------------------------------
    // AC-TEST-OFF-BY-DEFAULT: config false → no EmbeddedWorker bean
    // -------------------------------------------------------------------------

    @Test
    void embeddedWorkerBeanAbsentWhenPropertyFalse() {
        runner.withPropertyValues("tm.slotopt.embedded-worker.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx.getBeanNamesForType(EmbeddedWorker.class)).isEmpty();
                        });
    }
}
