// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import java.util.List;
import java.util.UUID;

/**
 * Holds the parsed context from a successful dispatcher registration response per DEC-43 D1.
 *
 * <p>This record is the output of {@link DefaultSlotOptimizationDispatcherClient#register()} and is
 * consumed downstream by the DEC-43 D3 deprecation-warning check
 * (AC-REGISTER-RESPONSE-PARSED-DEC43-D1).
 *
 * <p>The {@code algorithms} list represents the server's announced algorithm list from the
 * registration response. Per the AC, this list is parsed from the registration response body
 * directly — NOT from a separate {@code GET /api/algorithms} endpoint (DEC-43 D1 verbatim:
 * "server's registration response includes a list of currently-supported signature algorithms").
 *
 * <p>At V1, the dispatcher's {@code RegisterKeyResponse} does not yet carry the algorithm list (the
 * E40S01-S03 retrofit added the {@code GET /api/algorithms} endpoint but did not amend the
 * register-key response). The TM client handles this gracefully: if the {@code algorithms} field is
 * absent from the registration response, an empty list is used (defensive; never fires V1 event
 * anyway since V1 has null deprecation_date on Ed25519 per DEC-43 D4).
 *
 * @param workerId the UUID of this TM instance as registered with the dispatcher
 * @param algorithms the algorithm list from the registration response; may be empty if the
 *     dispatcher does not yet include the list in its register-key response
 * @see DefaultSlotOptimizationDispatcherClient
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D1</a>
 */
public record DispatcherRegistrationContext(
        UUID workerId, List<DispatcherAlgorithmEntry> algorithms) {

    /** Compact constructor — defensive copy of algorithms list. */
    public DispatcherRegistrationContext {
        if (workerId == null) {
            throw new IllegalArgumentException("workerId must not be null");
        }
        algorithms = algorithms == null ? List.of() : List.copyOf(algorithms);
    }
}
