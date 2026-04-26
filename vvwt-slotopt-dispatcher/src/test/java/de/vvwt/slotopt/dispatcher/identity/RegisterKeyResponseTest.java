package de.vvwt.slotopt.dispatcher.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RegisterKeyResponse}.
 *
 * <p>RED-first per DEC-22 / AC-REGISTER-KEY-REQUEST-RESPONSE.
 *
 * <p>Story: E37S05
 */
class RegisterKeyResponseTest {

    @Test
    void constructorAccessorsRoundtrip() {
        UUID workerId = UUID.randomUUID();
        String role = "worker";
        String algorithm = "Ed25519";
        Instant registeredAt = Instant.parse("2026-04-26T10:00:00Z");

        RegisterKeyResponse response = new RegisterKeyResponse(workerId, role, algorithm,
                registeredAt);

        assertThat(response.workerId()).isEqualTo(workerId);
        assertThat(response.role()).isEqualTo(role);
        assertThat(response.algorithm()).isEqualTo(algorithm);
        assertThat(response.registeredAt()).isEqualTo(registeredAt);
    }
}
