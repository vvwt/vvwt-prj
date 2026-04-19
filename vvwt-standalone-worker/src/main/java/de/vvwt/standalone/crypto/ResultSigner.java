package de.vvwt.standalone.crypto;

import de.vvwt.worker.identity.WorkerKeyManager;
import de.vvwt.worker.types.PacketResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.UUID;

/**
 * Constructs the normative 72-byte canonical byte layout for a packet result and signs it using the
 * worker's Ed25519 private key.
 *
 * <h2>Canonical layout (AC4 of E01S05)</h2>
 *
 * <pre>
 * Offset  Length  Field
 *   0       16    packetId bytes (most-significant 8 bytes || least-significant 8 bytes, big-endian)
 *  16       16    jobId bytes (same UUID encoding)
 *  32        8    bestRank  (big-endian long)
 *  40        8    bestScoreBits = Double.doubleToLongBits(bestScore) (big-endian long)
 *  48        8    permutationsScored (big-endian long)
 *  56       16    workerKeyId bytes (same UUID encoding)
 * </pre>
 *
 * Total: 72 bytes. Ed25519 signs these bytes DIRECTLY per RFC 8032 (no pre-hashing).
 *
 * <p>{@code Double.doubleToLongBits} (not {@code doubleToRawLongBits}) is used to normalize NaN
 * representations, ensuring consistent byte output across platforms.
 *
 * <p>Implements Story E01S05 AC4 and DEC-6.
 */
public final class ResultSigner {

    /** Total size of the canonical byte payload in bytes. */
    public static final int CANONICAL_BYTES_LENGTH = 72;

    private final WorkerKeyManager keyManager;

    /**
     * Constructs a {@code ResultSigner}.
     *
     * @param keyManager the worker's key manager (must already hold a loaded keypair)
     */
    public ResultSigner(WorkerKeyManager keyManager) {
        if (keyManager == null) {
            throw new IllegalArgumentException("keyManager must not be null");
        }
        this.keyManager = keyManager;
    }

    /**
     * Builds the 72-byte canonical payload and returns the Base64-encoded Ed25519 signature.
     *
     * @param packetId UUID of the packet being submitted
     * @param jobId UUID of the owning job
     * @param result packet solve result (bestRank, bestScore, permutationsScored)
     * @param workerKeyId UUID of the worker's registered key
     * @return standard Base64-encoded (no wrapping) Ed25519 signature over the 72-byte payload
     */
    public String signResult(UUID packetId, UUID jobId, PacketResult result, UUID workerKeyId) {
        if (packetId == null) {
            throw new IllegalArgumentException("packetId must not be null");
        }
        if (jobId == null) {
            throw new IllegalArgumentException("jobId must not be null");
        }
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        if (workerKeyId == null) {
            throw new IllegalArgumentException("workerKeyId must not be null");
        }

        byte[] canonical = buildCanonicalBytes(packetId, jobId, result, workerKeyId);
        byte[] signature = keyManager.signResult(canonical);
        return Base64.getEncoder().encodeToString(signature);
    }

    /** Builds the 72-byte canonical representation. Package-visible for unit testing. */
    static byte[] buildCanonicalBytes(
            UUID packetId, UUID jobId, PacketResult result, UUID workerKeyId) {
        ByteBuffer buf = ByteBuffer.allocate(CANONICAL_BYTES_LENGTH).order(ByteOrder.BIG_ENDIAN);

        // packetId: 16 bytes
        putUuid(buf, packetId);

        // jobId: 16 bytes
        putUuid(buf, jobId);

        // bestRank: 8 bytes big-endian long
        buf.putLong(result.bestRank());

        // bestScoreBits: 8 bytes — Double.doubleToLongBits (normalizes NaN)
        buf.putLong(Double.doubleToLongBits(result.bestScore()));

        // permutationsScored: 8 bytes big-endian long
        buf.putLong(result.permutationsScored());

        // workerKeyId: 16 bytes
        putUuid(buf, workerKeyId);

        assert buf.remaining() == 0
                : "Buffer should be exactly filled: remaining=" + buf.remaining();

        return buf.array();
    }

    /**
     * Writes a UUID as 16 big-endian bytes: most-significant 8 bytes, then least-significant 8
     * bytes. This matches the standard UUID binary encoding (RFC 4122 §4.1.2).
     */
    private static void putUuid(ByteBuffer buf, UUID uuid) {
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
    }

    /**
     * Signs the nonce for a {@code /pull-packet} request.
     *
     * <p>Canonical bytes (E01S07 AC3): {@code "pull-packet"} UTF-8 (11 bytes) + {@code workerKeyId}
     * 16-byte big-endian UUID + {@code signedNonce} UTF-8 bytes of the ISO-8601 string.
     *
     * @param workerKeyId the registered worker key UUID
     * @param signedNonce ISO-8601 instant string
     * @return standard Base64-encoded Ed25519 signature
     */
    public String signPullNonce(UUID workerKeyId, String signedNonce) {
        if (workerKeyId == null) {
            throw new IllegalArgumentException("workerKeyId must not be null");
        }
        if (signedNonce == null || signedNonce.isBlank()) {
            throw new IllegalArgumentException("signedNonce must not be null or blank");
        }

        byte[] prefix = "pull-packet".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] nonceBytes = signedNonce.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer buf =
                ByteBuffer.allocate(prefix.length + 16 + nonceBytes.length)
                        .order(ByteOrder.BIG_ENDIAN);
        buf.put(prefix);
        putUuid(buf, workerKeyId);
        buf.put(nonceBytes);

        byte[] signature = keyManager.signResult(buf.array());
        return Base64.getEncoder().encodeToString(signature);
    }
}
