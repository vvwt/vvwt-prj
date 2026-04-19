package de.vvwt.dispatcher.cache;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Composite primary key for {@link CachedResultEntity}.
 *
 * <p>The three-column PK {@code (fingerprint, score_fn_version, canonicalization_version)} captures
 * all three dimensions of cache correctness per AC6 of E01S09:
 *
 * <ul>
 *   <li>{@code fingerprint} — identifies the structural input
 *   <li>{@code scoreFnVersion} — invalidates on scorer changes
 *   <li>{@code canonicalizationVersion} — invalidates if the 5-step fingerprint rule changes
 * </ul>
 */
@Embeddable
public class CachedResultId implements Serializable {

    @Column(name = "fingerprint", nullable = false)
    private byte[] fingerprint;

    @Column(name = "score_fn_version", nullable = false)
    private int scoreFnVersion;

    @Column(name = "canonicalization_version", nullable = false)
    private int canonicalizationVersion;

    /** JPA no-arg constructor. */
    protected CachedResultId() {}

    /**
     * Creates a composite key.
     *
     * @param fingerprint 32-byte SHA-256 fingerprint; must not be {@code null}
     * @param scoreFnVersion scorer algorithm version
     * @param canonicalizationVersion canonicalization algorithm version
     */
    public CachedResultId(byte[] fingerprint, int scoreFnVersion, int canonicalizationVersion) {
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        if (fingerprint.length != 32) {
            throw new IllegalArgumentException(
                    "fingerprint must be 32 bytes but was: " + fingerprint.length);
        }
        this.fingerprint = Arrays.copyOf(fingerprint, fingerprint.length);
        this.scoreFnVersion = scoreFnVersion;
        this.canonicalizationVersion = canonicalizationVersion;
    }

    public byte[] getFingerprint() {
        return Arrays.copyOf(fingerprint, fingerprint.length);
    }

    public int getScoreFnVersion() {
        return scoreFnVersion;
    }

    public int getCanonicalizationVersion() {
        return canonicalizationVersion;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CachedResultId that)) return false;
        return scoreFnVersion == that.scoreFnVersion
                && canonicalizationVersion == that.canonicalizationVersion
                && Arrays.equals(fingerprint, that.fingerprint);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(scoreFnVersion, canonicalizationVersion);
        result = 31 * result + Arrays.hashCode(fingerprint);
        return result;
    }
}
