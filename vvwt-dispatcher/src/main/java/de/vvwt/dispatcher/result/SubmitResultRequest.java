package de.vvwt.dispatcher.result;

import java.util.UUID;

/**
 * Request body for {@code POST /submit-result} (E01S08 AC1).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code packetId} — UUID of the packet being submitted</li>
 *   <li>{@code jobId} — UUID of the owning job (for signature canonicalization)</li>
 *   <li>{@code bestRank} — best permutation rank found (0-based Lehmer rank)</li>
 *   <li>{@code bestScore} — variety score as a {@link String} produced by
 *       {@code Double.toString()} — round-trippable per JLS (AC1). The dispatcher
 *       parses it via {@code Double.parseDouble}.</li>
 *   <li>{@code permutationsScored} — total permutations scored in this packet</li>
 *   <li>{@code wallClockNanos} — wall-clock time taken to process the packet</li>
 *   <li>{@code workerKeyId} — the registered key ID of the submitting worker</li>
 *   <li>{@code signature} — Base64-encoded Ed25519 signature over the 72-byte canonical
 *       byte layout defined in AC2</li>
 * </ul>
 *
 * <p>See Story E01S08 AC1, AC2.
 */
public record SubmitResultRequest(
        UUID packetId,
        UUID jobId,
        Long bestRank,
        String bestScore,
        Long permutationsScored,
        Long wallClockNanos,
        UUID workerKeyId,
        String signature) {
}
