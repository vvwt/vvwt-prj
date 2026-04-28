package de.vvwt.slotopt.standalone.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubmitResultPayload}.
 *
 * <p>TDD Iron Law (DEC-22): authored RED-first before {@link SubmitResultPayload} exists.
 *
 * <p>Same-package test (DEC-36): located in {@code de.vvwt.slotopt.standalone.crypto} — may
 * reference record fields directly.
 *
 * <p>Story: E41S03 AC-RESULT-SIGNER-INTERFACE (SubmitResultPayload record shape per Brief D-10 +
 * O-6 (ii)).
 */
class SubmitResultPayloadTest {

    @Test
    void holdsRequiredFields() {
        UUID packetId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        String algorithm = "Ed25519";
        String resultPayloadJson = "{\"result\":\"ok\"}";

        SubmitResultPayload payload =
                new SubmitResultPayload(packetId, workerId, algorithm, resultPayloadJson);

        assertThat(payload.packetId()).isEqualTo(packetId);
        assertThat(payload.workerId()).isEqualTo(workerId);
        assertThat(payload.algorithm()).isEqualTo(algorithm);
        assertThat(payload.resultPayloadJson()).isEqualTo(resultPayloadJson);
    }

    @Test
    void equalityByValue() {
        UUID packetId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();

        SubmitResultPayload p1 =
                new SubmitResultPayload(packetId, workerId, "Ed25519", "{\"x\":1}");
        SubmitResultPayload p2 =
                new SubmitResultPayload(packetId, workerId, "Ed25519", "{\"x\":1}");

        assertThat(p1).isEqualTo(p2);
        assertThat(p1.hashCode()).isEqualTo(p2.hashCode());
    }

    @Test
    void toStringContainsFieldValues() {
        UUID packetId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workerId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        SubmitResultPayload payload =
                new SubmitResultPayload(packetId, workerId, "Ed25519", "{\"r\":true}");

        String str = payload.toString();
        assertThat(str).contains("Ed25519");
    }
}
