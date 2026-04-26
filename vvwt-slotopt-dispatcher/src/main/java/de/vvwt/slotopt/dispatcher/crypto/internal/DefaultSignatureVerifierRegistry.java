package de.vvwt.slotopt.dispatcher.crypto.internal;

import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifierRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Default {@link SignatureVerifierRegistry} implementation that auto-discovers all Spring {@link
 * SignatureVerifier} beans via constructor injection.
 *
 * <p>Spring collects all {@code @Component} beans implementing {@link SignatureVerifier} into the
 * {@code List<SignatureVerifier>} constructor parameter. The list is indexed by {@link
 * SignatureVerifier#algorithmId()} for O(1) lookup.
 *
 * <p>Package layout: this implementation lives in {@code crypto.internal} (DEC-35). All consumer
 * code types its dependency as {@link SignatureVerifierRegistry} (the public interface), never as
 * this class.
 *
 * <p>Spec: E37S04 AC-SIGNATURE-VERIFIER-REGISTRY; DEC-35 (package layout), DEC-43 §D1 (server
 * algorithm advertisement).
 */
@Component
public class DefaultSignatureVerifierRegistry implements SignatureVerifierRegistry {

    private final Map<String, SignatureVerifier> verifiersByAlgorithmId;

    /**
     * Constructs the registry by indexing the provided verifiers by their algorithm ID.
     *
     * @param verifiers all {@link SignatureVerifier} beans registered in the Spring context; Spring
     *     injects this list automatically via component scan
     */
    public DefaultSignatureVerifierRegistry(List<SignatureVerifier> verifiers) {
        this.verifiersByAlgorithmId =
                verifiers.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        SignatureVerifier::algorithmId, Function.identity()));
    }

    @Override
    public Optional<SignatureVerifier> lookup(String algorithmId) {
        return Optional.ofNullable(verifiersByAlgorithmId.get(algorithmId));
    }

    @Override
    public Set<String> supportedAlgorithms() {
        return verifiersByAlgorithmId.keySet();
    }
}
