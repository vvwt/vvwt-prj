// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThatCode;

import de.vvwt.slotopt.worker.runtime.DispatcherException;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EmbeddedOutagePolicy}.
 *
 * <p>Verifies the embedded outage policy returns normally on outage (survive-and-resume) instead of
 * throwing (which would be the standalone worker's behaviour).
 *
 * <p>Story: E63S03 AC-TEST-OUTAGE-SURVIVE-AND-RESUME, AC-GOV-EMBEDDED-LIFECYCLE-NOT-CLI-LIFECYCLE.
 */
class EmbeddedOutagePolicyTest {

    private final EmbeddedOutagePolicy policy = new EmbeddedOutagePolicy();

    @Test
    void onOutageDoesNotThrow() {
        DispatcherException cause = new DispatcherException(503, "Service Unavailable", null);
        // Embedded outage policy MUST return normally — not throw — so the loop can back off+resume
        assertThatCode(() -> policy.onOutage(cause)).doesNotThrowAnyException();
    }

    @Test
    void onOutageWithHttpZeroDoesNotThrow() {
        DispatcherException cause = new DispatcherException(0, "connection refused", null);
        assertThatCode(() -> policy.onOutage(cause)).doesNotThrowAnyException();
    }
}
