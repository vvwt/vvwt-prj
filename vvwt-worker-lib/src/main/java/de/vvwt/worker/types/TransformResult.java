package de.vvwt.worker.types;

import java.util.Arrays;

/**
 * The combined output of {@link StructuralFingerprint#transform(RawPhaseDef)}.
 *
 * <p>Holds both the 32-byte SHA-256 fingerprint of the canonical form and the
 * canonical form itself, so callers do not need to recompute either.
 *
 * @param fingerprint 32-byte SHA-256 digest of the serialized {@link CanonicalPhaseDef}
 * @param canonical   the canonicalized form of the input {@link RawPhaseDef}
 */
public record TransformResult(byte[] fingerprint, CanonicalPhaseDef canonical) {

    /** Compact canonical constructor — validates fingerprint length. */
    public TransformResult {
        if (fingerprint == null) {
            throw new IllegalArgumentException("fingerprint must not be null");
        }
        if (fingerprint.length != 32) {
            throw new IllegalArgumentException(
                    "fingerprint must be 32 bytes (SHA-256) but was: " + fingerprint.length);
        }
        // defensive copy — byte arrays are mutable
        fingerprint = Arrays.copyOf(fingerprint, fingerprint.length);
        if (canonical == null) {
            throw new IllegalArgumentException("canonical must not be null");
        }
    }

    /**
     * Returns a defensive copy of the fingerprint bytes.
     * The backing array of the record component is already a private copy,
     * but record accessors return the field directly, so we override to copy again.
     */
    @Override
    public byte[] fingerprint() {
        return Arrays.copyOf(fingerprint, fingerprint.length);
    }
}
