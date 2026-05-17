package de.vvwt.slotopt.worker.runtime;

import java.util.UUID;

/**
 * Immutable value object carrying the data to be signed before submission to the dispatcher.
 *
 * <p>The canonical bytes to sign are {@code resultPayloadJson().getBytes(UTF-8)} — the same string
 * the dispatcher receives and JCS-canonicalizes before signature verification (per Brief D-10 + O-6
 * (ii)).
 *
 * <p>Story: E41S03 AC-CANONICAL-BYTES-FROM-DELIVERED-DTO (moved to E63S01 shared runtime library).
 */
public record SubmitResultPayload(
        UUID packetId, UUID workerId, String signingAlgorithm, String resultPayloadJson) {}
