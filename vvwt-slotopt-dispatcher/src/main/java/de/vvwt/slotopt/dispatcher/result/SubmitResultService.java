// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

/**
 * Service interface for submit-result operations.
 *
 * <p>Per DEC-35: this interface lives in the public {@code result} package. The implementation
 * ({@link de.vvwt.slotopt.dispatcher.result.internal.DefaultSubmitResultService}) lives in {@code
 * result.internal}.
 *
 * <p>All consumers (controllers, tests) type their dependency as {@code SubmitResultService}, never
 * as the implementation class (DEC-36).
 *
 * <p>Story: E37S09; AC-SUBMIT-RESULT-SERVICE; DEC-35, DEC-36
 */
public interface SubmitResultService {

    /**
     * Processes a signed result submission from a worker.
     *
     * <p>Behavior per AC-SUBMIT-RESULT-SERVICE:
     *
     * <ol>
     *   <li>Lookup {@code KeyRegistration} by {@code workerId} — unknown → throws {@link
     *       UnknownWorkerException}.
     *   <li>Verify {@code algorithm} matches registered algorithm — mismatch → throws {@link
     *       AlgorithmMismatchException} with explicit message (AC-ALGORITHM-MISMATCH-REJECTED).
     *   <li>Lookup {@code SignatureVerifierRegistry.lookup(algorithm)} — empty → throws {@link
     *       IllegalStateException} (server misconfiguration → 500).
     *   <li>Canonicalize {@code resultPayloadJson} via {@code JcsCanonicalizer}.
     *   <li>Verify signature — false → throws {@link
     *       de.vvwt.slotopt.dispatcher.crypto.InvalidSignatureException}.
     *   <li>Lookup packet status — unknown → throws {@link PacketNotFoundException}.
     *       RESULT_RECEIVED → persist {@link LateResult}, return {@code accepted=false}; CLAIMED →
     *       mark RESULT_RECEIVED, persist {@link ResultAuditEntry}, return {@code accepted=true}.
     * </ol>
     *
     * @param request the submit-result request
     * @param sourceIp the source IP of the request (for audit)
     * @return the response indicating accepted or superseded
     */
    SubmitResultResponse submit(SubmitResultRequest request, String sourceIp);
}
