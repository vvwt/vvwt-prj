package de.vvwt.info.persistence;

/**
 * Thrown by the service layer when a {@code tournament.state} blob exceeds the maximum allowed size
 * (16 MB = 16,777,216 bytes per AC11).
 *
 * <p>This exception is thrown BEFORE the DAO write attempt — the service layer validates blob size
 * and rejects oversized payloads with this typed exception. The DAO IT in {@code BlobRoundTripIT}
 * verifies the guard is invoked by the service layer.
 *
 * <p>The 16 MB bound is a Phase-1 convention: large enough to hold a full tournament snapshot with
 * delta history, small enough to prevent pathological payloads from degrading the DB.
 *
 * @see <a href="../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC11</a>
 */
public class OversizeBlobException extends RuntimeException {

    /** Maximum allowed {@code tournament.state} blob size in bytes: 16 MB. */
    public static final int MAX_BLOB_BYTES = 16_777_216;

    /**
     * Constructs an {@code OversizeBlobException} with a message indicating the actual and maximum
     * sizes.
     *
     * @param actualBytes the size of the blob that was rejected
     */
    public OversizeBlobException(int actualBytes) {
        super(
                "tournament.state blob exceeds maximum allowed size: "
                        + actualBytes
                        + " bytes > "
                        + MAX_BLOB_BYTES
                        + " bytes (AC11)");
    }
}
