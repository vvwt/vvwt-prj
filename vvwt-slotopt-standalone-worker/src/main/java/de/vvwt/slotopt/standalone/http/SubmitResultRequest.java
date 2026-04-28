package de.vvwt.slotopt.standalone.http;

import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/submit-result} from the standalone worker's perspective.
 *
 * <p>Worker-side DTO mirroring the dispatcher's {@code SubmitResultRequest} wire shape (per Brief
 * D-10, O-6 (ii); implementer reads {@code
 * vvwt-slotopt-dispatcher/.../result/SubmitResultRequest.java} for exact field shape).
 *
 * <p>The {@code algorithm} field is REQUIRED per DEC-43 D2 (algorithm binding is per-registration;
 * the worker echoes the algorithm it registered with).
 *
 * <p>Story: E41S05 AC-SUBMIT-RESULT-WITH-ALGORITHM.
 */
public record SubmitResultRequest(
        UUID packetId,
        UUID workerId,
        String algorithm,
        byte[] signature,
        String resultPayloadJson) {}
