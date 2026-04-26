package de.vvwt.slotopt.dispatcher.crypto.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifierRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for {@link DefaultSignatureVerifierRegistry}.
 *
 * <p>RED-first per DEC-22 / AC-SIGNATURE-VERIFIER-REGISTRY. Uses {@link SpringBootTest} so that
 * Spring auto-discovers all {@link SignatureVerifier} beans (just {@code DefaultEd25519Verifier} in
 * V1).
 *
 * <p>Cross-package test — types declared as public interfaces ({@link SignatureVerifierRegistry},
 * {@link SignatureVerifier}) per DEC-36.
 *
 * <p>Spec: E37S04 AC-SIGNATURE-VERIFIER-REGISTRY; DEC-35 (package layout), DEC-36 (cross-package
 * test typing), DEC-43 §D4 (V1 = Ed25519 only).
 */
@SpringBootTest
class DefaultSignatureVerifierRegistryTest {

    @Autowired private SignatureVerifierRegistry registry;

    @Test
    void lookup_knownAlgorithm_returnsVerifier() {
        var result = registry.lookup("Ed25519");
        assertThat(result).isPresent();
        assertThat(result.get().algorithmId()).isEqualTo("Ed25519");
    }

    @Test
    void lookup_unknownAlgorithm_returnsEmpty() {
        var result = registry.lookup("ML-DSA-65");
        assertThat(result).isEmpty();
    }

    @Test
    void supportedAlgorithms_containsEd25519() {
        assertThat(registry.supportedAlgorithms()).containsExactly("Ed25519");
    }

    @Test
    void registry_isBeanOfPublicInterface() {
        // DEC-36: the injected bean must be typed as the public interface.
        assertThat(registry).isInstanceOf(SignatureVerifierRegistry.class);
    }

    @Test
    void lookup_caseInsensitive_isFalse_exactMatchRequired() {
        // Algorithm IDs use exact case (e.g., "Ed25519" not "ed25519").
        var result = registry.lookup("ed25519");
        assertThat(result).isEmpty();
    }
}
