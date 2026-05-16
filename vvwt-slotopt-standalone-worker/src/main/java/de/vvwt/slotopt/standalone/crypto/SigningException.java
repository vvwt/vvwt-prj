// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.crypto;

/**
 * Thrown when {@link ResultSigner} fails to sign a result payload.
 *
 * <p>Wraps any error that occurs during signing — typically an {@link IllegalStateException} from
 * the worker-lib's {@link de.vvwt.slotopt.worker.identity.WorkerKeyManager#signResult} (which
 * itself wraps JCE errors such as {@link java.security.NoSuchAlgorithmException}, {@link
 * java.security.InvalidKeyException}, or {@link java.security.SignatureException}).
 *
 * <p>Story: E41S03 AC-SIGNING-EXCEPTION.
 *
 * @see ResultSigner
 */
public class SigningException extends RuntimeException {

    /**
     * Constructs a {@code SigningException} with a message and cause.
     *
     * @param message describes the signing failure
     * @param cause the underlying exception from the worker-lib or JCE layer; may be {@code null}
     */
    public SigningException(String message, Throwable cause) {
        super(message, cause);
    }
}
