// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import java.time.Instant;
import java.util.UUID;

/**
 * Service interface for recording result submission audit entries.
 *
 * <p>Per DEC-35: this interface lives in the public {@code result} package. The implementation
 * ({@link de.vvwt.slotopt.dispatcher.result.internal.DefaultResultAuditService}) lives in {@code
 * result.internal}.
 *
 * <p>Called on every submit-result call outcome (DEC-6 § Impact: audit logs retain source IP,
 * timestamp, and signature outcome).
 *
 * <p>Story: E37S09; AC-RESULT-AUDIT; DEC-6, DEC-35
 */
public interface ResultAuditService {

    /**
     * Records a result submission audit entry.
     *
     * @param packetId the UUID of the packet referenced by the call
     * @param workerId the UUID of the submitting worker
     * @param algorithm the algorithm claimed by the worker (e.g., {@code "Ed25519"})
     * @param sourceIp the source IP of the request
     * @param receivedAt the timestamp when the call was received
     * @param outcome one of {@code "ACCEPTED"}, {@code "SUPERSEDED"}, {@code "SIGNATURE_INVALID"}
     */
    void record(
            UUID packetId,
            UUID workerId,
            String algorithm,
            String sourceIp,
            Instant receivedAt,
            String outcome);
}
