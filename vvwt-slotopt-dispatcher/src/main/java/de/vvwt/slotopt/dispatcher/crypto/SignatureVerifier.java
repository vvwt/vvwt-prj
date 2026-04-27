package de.vvwt.slotopt.dispatcher.crypto;

import java.time.LocalDate;
import java.util.Map;

/**
 * SPI for verifying digital signatures in the slot-optimization dispatcher.
 *
 * <p>This interface is the algorithm-agility extension point defined by DEC-43 / E37S04
 * AC-SIGNATURE-VERIFIER-INTERFACE. V1 ships a single implementation ({@link
 * de.vvwt.slotopt.dispatcher.crypto.internal.DefaultEd25519Verifier}) which handles Ed25519 (RFC
 * 8032). Future implementations (e.g., ML-DSA-65) register as additional Spring {@code @Component}
 * beans — no existing code changes required (open/closed principle per DEC-43 § Future PQC
 * migration is a code-only path).
 *
 * <h2>Package layout (DEC-35 / DEC-43)</h2>
 *
 * <p>This interface lives in the {@code crypto} package root (public surface). Implementations live
 * in {@code crypto.internal}. All consumers type their fields as {@code SignatureVerifier} (or
 * {@code SignatureVerifierRegistry}), never as {@code DefaultEd25519Verifier}.
 *
 * <h2>Error contract</h2>
 *
 * <p>A technically-valid signature that is simply incorrect (wrong bytes) is signalled by returning
 * {@code false}, NOT by throwing {@link InvalidSignatureException}. The exception is reserved for
 * structural failures: malformed key length, internal JCE errors, or unsupported key encoding.
 *
 * <h2>DEC-43 D1 metadata contract (E40S01)</h2>
 *
 * <p>Three metadata methods ({@link #displayName()}, {@link #deprecationDate()}, {@link
 * #parameters()}) were added by E40S01 to support the algorithm-announcement endpoint (E40S02) and
 * deprecation enforcement (E40S03). All implementations MUST provide explicit overrides — no {@code
 * default} methods (per Brief T-4 explicit-override choice, enforced at compile time for every new
 * {@code SignatureVerifier} impl). None of the three methods may declare a {@code throws} clause or
 * throw unchecked exceptions from their getter bodies.
 *
 * <p>Spec: E37S04 AC-SIGNATURE-VERIFIER-INTERFACE; E40S01 AC-INTERFACE-EXTENSION-ADDITIVE; DEC-43 §
 * D1 (SPI shape).
 */
public interface SignatureVerifier {

    /**
     * Returns the server-canonical algorithm identifier for this verifier.
     *
     * <p>Identifier uses the JCE canonical name form (e.g., {@code "Ed25519"}, {@code
     * "ML-DSA-65"}). The identifier is stable across server restarts and is transmitted in the
     * {@code supportedAlgorithms[]} list in registration responses (DEC-43 § D1).
     *
     * @return the algorithm identifier; never {@code null}
     */
    String algorithmId();

    /**
     * Returns the minimum accepted public key length in bytes for this algorithm.
     *
     * <p>For Ed25519 this is {@code 32} (RFC 8032). A public key shorter than this value causes
     * {@link #verify} to throw {@link InvalidSignatureException}.
     *
     * @return the minimum public key length in bytes
     */
    int minPublicKeyBytes();

    /**
     * Returns the maximum accepted public key length in bytes for this algorithm.
     *
     * <p>For Ed25519 this is {@code 32} (RFC 8032). A public key longer than this value causes
     * {@link #verify} to throw {@link InvalidSignatureException}.
     *
     * @return the maximum public key length in bytes
     */
    int maxPublicKeyBytes();

    /**
     * Verifies the given signature over the given message using the given public key.
     *
     * <p>Ed25519 signs the message bytes DIRECTLY — NO pre-hashing by the caller (per E37S02 spec
     * §(a) shared concerns, RFC 8032).
     *
     * @param publicKey the raw public key bytes; must have length in [{@link #minPublicKeyBytes()},
     *     {@link #maxPublicKeyBytes()}]
     * @param message the message bytes that were signed; must not be {@code null}
     * @param signature the signature bytes; must not be {@code null}
     * @return {@code true} if the signature is valid for the given key and message; {@code false}
     *     if the signature is well-formed but incorrect
     * @throws InvalidSignatureException if the public key length is outside the valid range, or if
     *     an internal JCE error prevents verification
     */
    boolean verify(byte[] publicKey, byte[] message, byte[] signature)
            throws InvalidSignatureException;

    // ----------------------------------------------------------------
    // E40S01 — DEC-43 D1 metadata methods (additive, abstract, no default)
    // AC-INTERFACE-EXTENSION-ADDITIVE + AC-NO-DEFAULT-METHOD-IN-INTERFACE
    // ----------------------------------------------------------------

    /**
     * Returns the human-readable display name for this algorithm, suitable for operator/admin UIs.
     *
     * <p>Example: {@code "Ed25519"}, {@code "ML-DSA-65 (NIST PQC, 2024)"}. The value corresponds to
     * the {@code display_name} field in the DEC-43 D1 wire schema. Per DEC-43 D1: {@code
     * display_name} is required (required=YES) — implementations MUST NOT return {@code null}.
     *
     * <p>No {@code throws} clause — implementations must not throw checked or unchecked exceptions
     * from this getter (AC-INTERFACE-METHODS-NO-THROWS-AND-NULL-CONTRACT).
     *
     * @return the human-readable algorithm name; never {@code null}
     */
    String displayName();

    /**
     * Returns the deprecation date for this algorithm, or {@code null} if the algorithm is not
     * deprecated.
     *
     * <p>When non-null, the algorithm is deprecated for new registrations after UTC end-of-day on
     * this date per DEC-43 D3 (as amended by DEC-48). When {@code null}, the algorithm is supported
     * indefinitely. Corresponds to the {@code deprecation_date} field in the DEC-43 D1 wire schema
     * (required=NO).
     *
     * <p>No {@code throws} clause — implementations must not throw checked or unchecked exceptions
     * from this getter (AC-INTERFACE-METHODS-NO-THROWS-AND-NULL-CONTRACT).
     *
     * @return the deprecation date as a {@link LocalDate} (ISO-8601 calendar date), or {@code null}
     *     if the algorithm is not deprecated
     */
    LocalDate deprecationDate();

    /**
     * Returns algorithm-specific parameters, or {@code null} if the algorithm has no parameter
     * variants.
     *
     * <p>For parameterized algorithms (e.g., a future ML-DSA-65), this map carries named parameter
     * entries such as {@code Map.of("parameter_set", "ML-DSA-65")}. For parameterless algorithms
     * (e.g., Ed25519 V1), returns {@code null}. Corresponds to the {@code parameters} field in the
     * DEC-43 D1 wire schema (required=NO).
     *
     * <p>No {@code throws} clause — implementations must not throw checked or unchecked exceptions
     * from this getter (AC-INTERFACE-METHODS-NO-THROWS-AND-NULL-CONTRACT).
     *
     * @return algorithm-specific parameters as a {@link Map}, or {@code null} if the algorithm is
     *     parameterless
     */
    Map<String, Object> parameters();
}
