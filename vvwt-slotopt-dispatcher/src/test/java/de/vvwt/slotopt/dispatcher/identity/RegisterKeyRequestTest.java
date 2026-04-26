package de.vvwt.slotopt.dispatcher.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RegisterKeyRequest}.
 *
 * <p>RED-first per DEC-22 / AC-REGISTER-KEY-REQUEST-RESPONSE: written before production class
 * exists.
 *
 * <p>Story: E37S05
 */
class RegisterKeyRequestTest {

    @Test
    void constructorAccessorsRoundtrip() {
        UUID workerId = UUID.randomUUID();
        String role = "worker";
        String algorithm = "Ed25519";
        byte[] keyBytes = new byte[] {1, 2, 3};

        RegisterKeyRequest request = new RegisterKeyRequest(workerId, role, algorithm, keyBytes);

        assertThat(request.workerId()).isEqualTo(workerId);
        assertThat(request.role()).isEqualTo(role);
        assertThat(request.algorithm()).isEqualTo(algorithm);
        assertThat(request.publicKeyBytes()).isEqualTo(keyBytes);
    }

    @Test
    void nullAlgorithmIsPermittedAtRecordLevel() {
        // Validation is done in the service, not in the record
        UUID workerId = UUID.randomUUID();
        RegisterKeyRequest request = new RegisterKeyRequest(workerId, "worker", null, new byte[32]);
        assertThat(request.algorithm()).isNull();
    }
}
