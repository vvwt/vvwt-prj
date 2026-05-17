package de.vvwt.slotopt.worker.runtime;

import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/submit-result} from the worker's perspective.
 *
 * <p>Worker-side DTO mirroring the dispatcher's {@code SubmitResultRequest} wire shape (per Brief
 * D-10, O-6 (ii)). The {@code algorithm} field is REQUIRED per DEC-43 D2 (algorithm binding is
 * per-registration; the worker echoes the algorithm it registered with).
 *
 * <p>Story: E41S05 AC-SUBMIT-RESULT-WITH-ALGORITHM (moved to E63S01 shared library).
 */
public record SubmitResultRequest(
        UUID packetId,
        UUID workerId,
        String algorithm,
        byte[] signature,
        String resultPayloadJson) {}
