// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.audit;

/**
 * Enumerates the cryptographic signature outcome for a request, stored in {@code
 * audit_log.signature_outcome}.
 *
 * <p>Split per Brief D-X5: {@code signature_outcome} records the cryptographic result; {@code
 * rejection_reason} records the application-level rejection cause (if any). The two columns are
 * independent — an {@code INVALID} signature may have a specific {@code KEY_MISMATCH} reason, while
 * a valid signature may still be rejected for other reasons.
 *
 * @see RejectionReason
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03</a>
 */
public enum SignatureOutcome {

    /** Request carried a cryptographically valid signature. */
    VALID,

    /** Request carried an invalid signature (e.g., tampered payload, wrong key). */
    INVALID,

    /**
     * No signature was evaluated — not applicable for this request type (e.g., reader endpoints).
     */
    NA
}
