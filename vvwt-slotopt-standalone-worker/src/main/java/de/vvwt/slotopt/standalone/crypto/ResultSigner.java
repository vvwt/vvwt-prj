package de.vvwt.slotopt.standalone.crypto;

/**
 * Signs an optimization result payload before submission to the dispatcher's {@code POST
 * /api/submit-result} endpoint.
 *
 * <p>The canonical bytes signed are the UTF-8 encoding of {@link
 * SubmitResultPayload#resultPayloadJson()} — the same JSON string the dispatcher receives as {@code
 * resultPayloadJson} and JCS-canonicalizes before signature verification (per {@code
 * DefaultSubmitResultService.submit()} Step 4, Brief D-10 + O-6 (ii)).
 *
 * <p>The canonical implementation is {@link
 * de.vvwt.slotopt.standalone.crypto.internal.DefaultResultSigner}.
 *
 * <p>DEC-35-by-analogy: public interface in the {@code crypto} root package; implementation in
 * {@code crypto.internal}. Cross-module consumers use this interface exclusively.
 *
 * <p>Story: E41S03 AC-RESULT-SIGNER-INTERFACE.
 */
@FunctionalInterface
public interface ResultSigner {

    /**
     * Signs the canonical bytes derived from {@code payload} using the worker's private key.
     *
     * <p>The canonical bytes are the UTF-8 encoding of {@link
     * SubmitResultPayload#resultPayloadJson()}.
     *
     * @param payload the result payload to sign; must not be {@code null}
     * @return the raw signature bytes (64 bytes for Ed25519 per RFC 8032 §5.1)
     * @throws SigningException if signing fails due to a key-management or JCE error
     * @throws NullPointerException if {@code payload} is {@code null}
     */
    byte[] signResult(SubmitResultPayload payload);
}
