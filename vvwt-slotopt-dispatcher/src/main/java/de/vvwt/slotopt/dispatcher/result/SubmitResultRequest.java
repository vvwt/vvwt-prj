package de.vvwt.slotopt.dispatcher.result;

import java.util.UUID;

/**
 * Request DTO for the {@code POST /api/submit-result} endpoint.
 *
 * <p>Java record — compact, immutable. The {@code algorithm} field is REQUIRED per
 * AC-SUBMIT-RESULT-DTOs: backward-compat (D-2) applies only to {@code pull-packet} {@code
 * supportedAlgorithms[]}, NOT to this field. The {@code resultPayloadJson} is the signed payload
 * whose JCS-canonical bytes are verified against the worker's registered public key.
 *
 * <p>Spec: E37S02 spec section (b) Endpoint 4 + section (d); AC-SUBMIT-RESULT-DTOs (E37S09); DEC-43
 * § D2 (algorithm binding per-registration).
 */
public record SubmitResultRequest(
        UUID packetId,
        UUID workerId,
        String algorithm,
        byte[] signature,
        String resultPayloadJson) {}
