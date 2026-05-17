// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for per-packet result retention operations.
 *
 * <p>Per DEC-35/DEC-58/DEC-72: this interface lives in the public {@code result} package. The
 * implementation ({@link de.vvwt.slotopt.dispatcher.result.internal.DefaultPacketResultService})
 * lives in {@code result.internal}. All consumers reference this interface exclusively (DEC-36).
 *
 * <p>Story: E60S02; AC-GOV-QUERYABLE-SUBSTRATE; AC-TEST-RESULT-RETAINED-ON-ACCEPT;
 * AC-ERR-RETENTION-ATOMIC-WITH-ACCEPT; DEC-35, DEC-58, DEC-72
 */
public interface PacketResultService {

    /**
     * Retains the accepted result for a packet in the queryable persistence store.
     *
     * <p>Must be called atomically with the {@code RESULT_RECEIVED} status transition on the owning
     * {@link de.vvwt.slotopt.dispatcher.packet.PacketRecord} (AC-ERR-RETENTION-ATOMIC-WITH-ACCEPT):
     * both the status update and the retention write share the same {@code @Transactional}
     * boundary.
     *
     * <p>Extracts {@code bestRank} and {@code bestScore} from {@code resultPayloadJson}. If either
     * field cannot be extracted, throws {@link IllegalArgumentException} — the malformed payload is
     * rejected and NOT retained (AC-ERR-RETENTION-REJECTS-MALFORMED-RESULT).
     *
     * <p>Idempotent for duplicate submissions: if a result for {@code packetId} already exists, the
     * call is a no-op and the existing result is NOT overwritten
     * (AC-ERR-DUPLICATE-RESULT-NO-CORRUPTION).
     *
     * @param packetId the UUID of the packet whose result is accepted
     * @param jobId the UUID of the owning job (for bulk-by-job queries per E60S03)
     * @param resultPayloadJson the JSON payload containing {@code bestRank} and {@code bestScore}
     * @throws IllegalArgumentException if {@code resultPayloadJson} does not contain both {@code
     *     bestRank} and {@code bestScore}
     */
    void retainResult(UUID packetId, UUID jobId, String resultPayloadJson);

    /**
     * Returns all retained packet results for the given job.
     *
     * <p>This is the E60S03 aggregation substrate access path — all packet results for one job must
     * be retrievable as a set for cross-packet aggregation.
     *
     * @param jobId the UUID of the owning job
     * @return all retained {@link PacketResult} records for the job; empty list if none retained
     */
    List<PacketResult> findResultsByJobId(UUID jobId);
}
