package de.vvwt.info.crypto;

import java.util.Map;

/**
 * Resolves the appropriate {@link SignatureVerifier} by {@code algorithm_id} (AC6 / DEC-43 D4).
 *
 * <p>The registry is algorithm-agnostic — it dispatches to whichever verifier was registered for
 * the given {@code algorithm_id}. Phase-1 contains exactly {@code Ed25519}, backed by {@link
 * de.vvwt.info.crypto.internal.Ed25519SignatureVerifier}. Future ML-DSA / SLH-DSA verifiers are
 * registered under their respective {@code algorithm_id} values without changing this class or the
 * {@link SignatureVerifier} interface.
 *
 * <p>Lives at the public {@code de.vvwt.info.crypto} surface (NOT in {@code .internal}) because it
 * is consumed by the registration service (E38S04) and the publish/tournament services (E38S05,
 * E38S09).
 *
 * @see SignatureVerifier
 * @see de.vvwt.info.crypto.internal.Ed25519SignatureVerifier
 * @see <a href="../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC6</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D4</a>
 */
public class SignatureVerifierRegistry {

    private final Map<String, SignatureVerifier> verifiers;

    /**
     * Constructs the registry with a fixed mapping from {@code algorithm_id} to verifier.
     *
     * @param verifiers immutable map from algorithm_id → verifier (e.g., {@code "Ed25519" →
     *     Ed25519SignatureVerifier})
     */
    public SignatureVerifierRegistry(Map<String, SignatureVerifier> verifiers) {
        this.verifiers = Map.copyOf(verifiers);
    }

    /**
     * Resolves the verifier for the given {@code algorithm_id}.
     *
     * @param algorithmId the algorithm identifier (e.g., {@code "Ed25519"})
     * @return the registered verifier
     * @throws IllegalArgumentException if {@code algorithmId} is null or not registered
     */
    public SignatureVerifier resolve(String algorithmId) {
        if (algorithmId == null) {
            throw new IllegalArgumentException("algorithmId must not be null");
        }
        SignatureVerifier verifier = verifiers.get(algorithmId);
        if (verifier == null) {
            throw new IllegalArgumentException(
                    "No SignatureVerifier registered for algorithm_id: "
                            + algorithmId
                            + ". Known algorithms: "
                            + verifiers.keySet());
        }
        return verifier;
    }
}
