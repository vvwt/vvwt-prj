// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import de.vvwt.slotopt.worker.runtime.DispatcherException;
import de.vvwt.slotopt.worker.runtime.OutagePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded-worker outage policy: log the outage and return normally.
 *
 * <p>Unlike the standalone worker's {@code StandaloneOutagePolicy} (which throws and terminates the
 * loop), the embedded worker <em>survives</em> a dispatcher outage. When the dispatcher becomes
 * unreachable, this policy logs a WARN and returns normally, allowing {@link DefaultEmbeddedWorker}
 * to back off and retry (AC-TEST-OUTAGE-SURVIVE-AND-RESUME,
 * AC-GOV-EMBEDDED-LIFECYCLE-NOT-CLI-LIFECYCLE, AC-ERR-OUTAGE-BACKOFF-NO-BUSY-LOOP).
 *
 * <p>This class is package-private; it is wired by {@link EmbeddedWorkerConfiguration}.
 *
 * <p>Story: E63S03.
 */
class EmbeddedOutagePolicy implements OutagePolicy {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedOutagePolicy.class);

    /** {@inheritDoc} — Logs the outage at WARN level and returns normally (no throw). */
    @Override
    public void onOutage(DispatcherException cause) {
        LOG.warn(
                "Embedded worker: dispatcher outage detected (HTTP {}) — backing off and resuming."
                        + " cause={}",
                cause.getHttpStatus(),
                cause.getMessage());
        // Return normally — the embedded worker loop applies backoff and retries.
    }
}
