package de.vvwt.slotopt.standalone.crypto;

import java.util.UUID;

/**
 * Payload record carrying the fields needed to compute canonical bytes for a {@code POST
 * /api/submit-result} signing operation.
 *
 * <p>Per Brief D-10 + O-6 (ii): the canonical bytes signed by the worker are the UTF-8 encoding of
 * {@code resultPayloadJson} — the same string the dispatcher receives as {@code resultPayloadJson}
 * in {@link de.vvwt.slotopt.dispatcher.result.SubmitResultRequest}. The dispatcher's {@code
 * DefaultSubmitResultService} JCS-canonicalizes this JSON and verifies the signature. Therefore,
 * the caller (E41S05 runtime loop) is responsible for providing a JCS-canonical (RFC 8785
 * key-sorted, no-whitespace) JSON string in {@code resultPayloadJson}.
 *
 * <p>The {@code algorithm} field matches the worker's registered algorithm per DEC-43 D2 and maps
 * to the {@code algorithm} field on {@code SubmitResultRequest}.
 *
 * <p>Story: E41S03 AC-RESULT-SIGNER-INTERFACE; AC-CANONICAL-BYTES-FROM-DELIVERED-DTO.
 *
 * @param packetId the packet UUID from the pull-packet response
 * @param workerId the worker's registered UUID (from bootstrap / registration)
 * @param algorithm the signing algorithm identifier (V1: {@code "Ed25519"} per DEC-43 D4)
 * @param resultPayloadJson the JCS-canonical JSON string to be signed and submitted as {@code
 *     resultPayloadJson} in the {@code SubmitResultRequest}
 */
public record SubmitResultPayload(
        UUID packetId, UUID workerId, String algorithm, String resultPayloadJson) {}
