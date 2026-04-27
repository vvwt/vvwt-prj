package de.vvwt.slotopt.dispatcher.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E40S01 AC-CROSS-PACKAGE-INTERFACE-CONTRACT-TEST — DEC-36 cross-package contract test.
 *
 * <p>This test class is in package {@code de.vvwt.slotopt.dispatcher.crypto} (the public interface
 * package root), while implementations live in {@code de.vvwt.slotopt.dispatcher.crypto.internal}.
 * Per DEC-36, tests in a DIFFERENT package from the subject MUST reference only the public
 * interface type ({@link SignatureVerifier}), never the concrete class ({@code
 * DefaultEd25519Verifier}).
 *
 * <p>Purpose: verifies that the three new DEC-43 D1 metadata methods ({@code displayName()}, {@code
 * deprecationDate()}, {@code parameters()}) are part of the {@link SignatureVerifier} interface
 * contract — i.e., consumers in E40S02 and E40S03 can rely on the interface alone.
 *
 * <p>RED-first per DEC-22 / DEC-41: all tests in this class fail until the three new methods are
 * declared on {@link SignatureVerifier}.
 */
class SignatureVerifierContractTest {

    // ----------------------------------------------------------------
    // AC-CROSS-PACKAGE-INTERFACE-CONTRACT-TEST:
    // Mock implements SignatureVerifier interface with custom metadata.
    // Callers type all references as SignatureVerifier — never DefaultEd25519Verifier.
    // ----------------------------------------------------------------

    /**
     * A mock implementing {@link SignatureVerifier} can return a custom {@code displayName()}; a
     * consumer holding only the interface type can invoke it. Demonstrates that {@code
     * displayName()} is part of the interface contract, not an implementation detail.
     */
    @Test
    void mockVerifier_customDisplayName_isCallableViaInterface() {
        SignatureVerifier verifier = mock(SignatureVerifier.class);
        when(verifier.displayName()).thenReturn("ML-DSA-65");

        String name = verifier.displayName();

        assertThat(name).isEqualTo("ML-DSA-65");
    }

    /**
     * A mock implementing {@link SignatureVerifier} can return a custom {@code deprecationDate()};
     * a consumer holding only the interface type can invoke it. Demonstrates that {@code
     * deprecationDate()} is part of the interface contract.
     */
    @Test
    void mockVerifier_customDeprecationDate_isCallableViaInterface() {
        SignatureVerifier verifier = mock(SignatureVerifier.class);
        LocalDate futureDate = LocalDate.of(2030, 12, 31);
        when(verifier.deprecationDate()).thenReturn(futureDate);

        LocalDate result = verifier.deprecationDate();

        assertThat(result).isEqualTo(futureDate);
        assertThat(result).isNotNull();
    }

    /**
     * A mock implementing {@link SignatureVerifier} can return a custom {@code parameters()} map; a
     * consumer holding only the interface type can invoke it. Demonstrates that {@code
     * parameters()} is part of the interface contract.
     */
    @Test
    void mockVerifier_customParameters_isCallableViaInterface() {
        SignatureVerifier verifier = mock(SignatureVerifier.class);
        Map<String, Object> params = Map.of("parameter_set", "ML-DSA-65");
        when(verifier.parameters()).thenReturn(params);

        Map<String, Object> result = verifier.parameters();

        assertThat(result).isEqualTo(params);
        assertThat(result).containsKey("parameter_set");
    }
}
