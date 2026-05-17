// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.vvwt.info.crypto.internal.DefaultSignatureVerifierRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SignatureVerifierRegistry}.
 *
 * <p>DEC-22 Q-1a TDD RED-first (AC1). Validates that the registry dispatches to the correct
 * verifier by {@code algorithm_id} and throws on unknown algorithm (AC6).
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC1,
 *     AC6</a>
 */
class SignatureVerifierRegistryTest {

    @Test
    void resolve_knownAlgorithmId_returnsVerifier() {
        SignatureVerifier ed25519Verifier = mock(SignatureVerifier.class);
        var registry = new DefaultSignatureVerifierRegistry(Map.of("Ed25519", ed25519Verifier));

        SignatureVerifier resolved = registry.resolve("Ed25519");

        assertThat(resolved).isSameAs(ed25519Verifier);
    }

    @Test
    void resolve_unknownAlgorithmId_throwsIllegalArgumentException() {
        var registry =
                new DefaultSignatureVerifierRegistry(
                        Map.of("Ed25519", mock(SignatureVerifier.class)));

        assertThatThrownBy(() -> registry.resolve("ml-dsa-65"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ml-dsa-65");
    }

    @Test
    void resolve_nullAlgorithmId_throwsIllegalArgumentException() {
        var registry =
                new DefaultSignatureVerifierRegistry(
                        Map.of("Ed25519", mock(SignatureVerifier.class)));

        assertThatThrownBy(() -> registry.resolve(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registry_multipleAlgorithms_routesCorrectly() {
        SignatureVerifier ed25519 = mock(SignatureVerifier.class);
        SignatureVerifier other = mock(SignatureVerifier.class);
        var registry =
                new DefaultSignatureVerifierRegistry(Map.of("Ed25519", ed25519, "OTHER", other));

        assertThat(registry.resolve("Ed25519")).isSameAs(ed25519);
        assertThat(registry.resolve("OTHER")).isSameAs(other);
    }
}
