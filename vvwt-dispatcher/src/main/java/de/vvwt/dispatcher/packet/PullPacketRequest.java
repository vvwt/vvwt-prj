package de.vvwt.dispatcher.packet;

import java.util.UUID;

/**
 * Request body for {@code POST /pull-packet} (E01S07 AC3).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code workerKeyId} — UUID of the worker's registered key</li>
 *   <li>{@code signature} — Base64-encoded Ed25519 signature of the canonical bytes</li>
 *   <li>{@code signedNonce} — ISO-8601 instant string that was signed (replay-prevention window)</li>
 * </ul>
 *
 * <p>Canonical bytes signed (AC3):
 * {@code "pull-packet"} UTF-8 (11 bytes)
 * + {@code workerKeyId} 16-byte big-endian UUID
 * + {@code signedNonce} UTF-8 bytes of the ISO-8601 string.
 *
 * <p>See Story E01S07 AC3 and DEC-6.
 */
public record PullPacketRequest(UUID workerKeyId, String signature, String signedNonce) {
}
