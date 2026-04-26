package de.vvwt.slotopt.worker.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * TDD-first unit tests for {@link DefaultWorkerCapabilityProvider}.
 *
 * <p>Same-package white-box tests per DEC-36. Written RED-first per DEC-22 Iron Law.
 *
 * <p>See E37S03, DEC-22, DEC-35, DEC-36.
 */
class DefaultWorkerCapabilityProviderTest {

    // -------------------------------------------------------------------------
    // AC-WORKERCAPABILITYPROVIDER-NEW — V1 implementation returns Set.of("Ed25519")
    // -------------------------------------------------------------------------

    @Test
    void supportedAlgorithmsReturnsEd25519() {
        DefaultWorkerCapabilityProvider provider = new DefaultWorkerCapabilityProvider();

        Set<String> algorithms = provider.supportedAlgorithms();

        assertThat(algorithms)
                .as("V1 DefaultWorkerCapabilityProvider must return exactly {'Ed25519'}")
                .containsExactly("Ed25519");
    }

    @Test
    void supportedAlgorithmsReturnsNonNull() {
        DefaultWorkerCapabilityProvider provider = new DefaultWorkerCapabilityProvider();

        assertThat(provider.supportedAlgorithms())
                .as("supportedAlgorithms() must never return null")
                .isNotNull();
    }

    @Test
    void supportedAlgorithmsReturnsUnmodifiableSet() {
        DefaultWorkerCapabilityProvider provider = new DefaultWorkerCapabilityProvider();
        Set<String> algorithms = provider.supportedAlgorithms();

        assertThatThrownBy(() -> algorithms.add("SomeOtherAlgorithm"))
                .as("Returned set must be unmodifiable")
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
