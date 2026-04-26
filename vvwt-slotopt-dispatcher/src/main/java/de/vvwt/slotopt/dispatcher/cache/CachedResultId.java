package de.vvwt.slotopt.dispatcher.cache;

import java.util.Arrays;
import java.util.Objects;

/**
 * Composite primary key value type for {@link CachedResultEntity}.
 *
 * <p>The cache key is purely structural per DEC-9: {@code structuralFingerprint} is the 32-byte
 * SHA-256 digest of the canonicalized phase definition (via {@code
 * de.vvwt.slotopt.worker.types.StructuralFingerprint}). {@code gameMode} discriminates results for
 * the same structural topology under different optimization objectives.
 *
 * <p>This value type is assembled/disassembled at the service layer. The persistence model uses a
 * flat entity ({@link CachedResultEntity}) with {@code @Id} on {@code byte[] structuralFingerprint}
 * and {@code isNew() = true} to force INSERT-only semantics; {@code gameMode} is a plain
 * {@code @Column} field. No custom Spring Data converters are required since {@code byte[]} and
 * {@code String} are natively-supported JDBC types.
 *
 * <p>Equality is byte-level on {@code structuralFingerprint} and string-level on {@code gameMode}.
 *
 * <p>Story: E37S10; AC-CACHED-RESULT-REPOSITORY; DEC-9, DEC-35
 */
public final class CachedResultId {

    private final byte[] structuralFingerprint;
    private final String gameMode;

    /**
     * Constructs a composite cache key.
     *
     * @param structuralFingerprint the 32-byte SHA-256 structural fingerprint; must not be {@code
     *     null}
     * @param gameMode the game-mode discriminator; must not be {@code null}
     */
    public CachedResultId(byte[] structuralFingerprint, String gameMode) {
        this.structuralFingerprint =
                Objects.requireNonNull(structuralFingerprint, "structuralFingerprint");
        this.gameMode = Objects.requireNonNull(gameMode, "gameMode");
    }

    /**
     * Returns the 32-byte structural fingerprint component of this composite key.
     *
     * @return the fingerprint bytes; never {@code null}
     */
    public byte[] structuralFingerprint() {
        return structuralFingerprint;
    }

    /**
     * Returns the game-mode discriminator component of this composite key.
     *
     * @return the game mode; never {@code null}
     */
    public String gameMode() {
        return gameMode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CachedResultId other)) return false;
        return Arrays.equals(structuralFingerprint, other.structuralFingerprint)
                && gameMode.equals(other.gameMode);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(structuralFingerprint);
        result = 31 * result + gameMode.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "CachedResultId{fingerprint=<"
                + structuralFingerprint.length
                + " bytes>, gameMode='"
                + gameMode
                + "'}";
    }
}
