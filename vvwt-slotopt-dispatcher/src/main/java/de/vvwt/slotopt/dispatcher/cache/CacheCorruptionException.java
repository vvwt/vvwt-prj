// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.cache;

import java.util.HexFormat;

/**
 * Exception raised when a cache hit's stored payload fails canonical re-verification.
 *
 * <p>Indicates schema drift or data corruption in the {@code cached_result} table for a given
 * structural fingerprint. The caller should log the incident and treat the cache entry as a miss
 * (do NOT surface the corrupt payload to the client).
 *
 * <p>Per AC-CACHE-CORRUPTION-EXCEPTION: constructor takes {@code (byte[] fingerprint, String
 * reason)}.
 *
 * <p>Story: E37S10; AC-CACHE-CORRUPTION-EXCEPTION; DEC-22
 */
public class CacheCorruptionException extends RuntimeException {

    private final byte[] fingerprint;

    /**
     * Constructs a new {@code CacheCorruptionException}.
     *
     * @param fingerprint the 32-byte structural fingerprint of the corrupt cache entry
     * @param reason a human-readable description of the corruption detected
     */
    public CacheCorruptionException(byte[] fingerprint, String reason) {
        super(
                "Cache corruption detected for fingerprint "
                        + HexFormat.of().formatHex(fingerprint)
                        + ": "
                        + reason);
        this.fingerprint = fingerprint;
    }

    /**
     * Returns the structural fingerprint of the cache entry where corruption was detected.
     *
     * @return the 32-byte fingerprint; never {@code null}
     */
    public byte[] getFingerprint() {
        return fingerprint;
    }
}
