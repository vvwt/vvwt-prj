package de.vvwt.slotopt.dispatcher.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubmitResultRequest}.
 *
 * <p>RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S09). Test written before
 * SubmitResultRequest exists.
 *
 * <p>Story: E37S09; AC-SUBMIT-RESULT-DTOs; DEC-22
 */
class SubmitResultRequestTest {

    @Test
    void recordFieldsAreAccessible() {
        UUID packetId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        String algorithm = "Ed25519";
        byte[] signature = new byte[] {1, 2, 3};
        String payload = "{\"bestRank\":42}";

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, algorithm, signature, payload);

        assertThat(req.packetId()).isEqualTo(packetId);
        assertThat(req.workerId()).isEqualTo(workerId);
        assertThat(req.algorithm()).isEqualTo(algorithm);
        assertThat(req.signature()).isEqualTo(signature);
        assertThat(req.resultPayloadJson()).isEqualTo(payload);
    }

    @Test
    void algorithmFieldIsRequired_nullAlgorithmPreserved() {
        // Records do not enforce non-null at the language level unless explicitly checked.
        // This test documents the expected field presence — algorithm is a mandatory field
        // per AC-SUBMIT-RESULT-DTOs; enforcement happens in DefaultSubmitResultService.
        UUID packetId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, null, new byte[0], "{}");
        // algorithm is null here — service must reject this
        assertThat(req.algorithm()).isNull();
    }
}
