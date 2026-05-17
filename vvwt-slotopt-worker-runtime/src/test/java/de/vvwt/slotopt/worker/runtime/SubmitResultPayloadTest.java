// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SubmitResultPayload}.
 *
 * <p>Moved from {@code vvwt-slotopt-standalone-worker} to {@code vvwt-slotopt-worker-runtime} in
 * E63S01 (AC-MOD-RUNTIME-LIBRARY-MODULE).
 *
 * <p>Story: E41S03 AC-RESULT-SIGNER-INTERFACE; E63S01 AC-MOD-RUNTIME-LIBRARY-MODULE.
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
        assertThat(payload.signingAlgorithm()).isEqualTo(algorithm);
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
