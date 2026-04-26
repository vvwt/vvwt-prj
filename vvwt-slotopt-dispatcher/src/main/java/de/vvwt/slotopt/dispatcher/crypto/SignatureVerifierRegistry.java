package de.vvwt.slotopt.dispatcher.crypto;

import java.util.Optional;
import java.util.Set;

/**
 * Registry of available {@link SignatureVerifier} implementations.
 *
 * <p>The registry is populated at application start-up by Spring auto-discovering all beans that
 * implement {@link SignatureVerifier}. V1 contains exactly one: {@code DefaultEd25519Verifier}.
 * Future PQC verifiers are added as additional {@code @Component} beans without modifying this
 * interface or its default implementation.
 *
 * <p>Package layout: this interface is in the {@code crypto} package root (public surface). The
 * sole implementation ({@link
 * de.vvwt.slotopt.dispatcher.crypto.internal.DefaultSignatureVerifierRegistry}) lives in {@code
 * crypto.internal} (DEC-35 / DEC-43).
 *
 * <p>Spec: E37S04 AC-SIGNATURE-VERIFIER-REGISTRY; DEC-43 § D1 (server algorithm advertisement);
 * DEC-35 (package layout).
 */
public interface SignatureVerifierRegistry {

    /**
     * Looks up the {@link SignatureVerifier} for the given algorithm identifier.
     *
     * @param algorithmId the server-canonical algorithm identifier (e.g., {@code "Ed25519"}); exact
     *     case match is required
     * @return an {@link Optional} containing the verifier if the algorithm is supported, or {@link
     *     Optional#empty()} if not
     */
    Optional<SignatureVerifier> lookup(String algorithmId);

    /**
     * Returns the set of algorithm identifiers currently supported by this registry.
     *
     * <p>This set is used to populate the {@code supportedAlgorithms[]} list in registration
     * responses (DEC-43 § D1). V1 returns {@code {"Ed25519"}} only.
     *
     * @return an unmodifiable, non-null set of algorithm identifier strings; may be empty if no
     *     verifiers are registered
     */
    Set<String> supportedAlgorithms();
}
