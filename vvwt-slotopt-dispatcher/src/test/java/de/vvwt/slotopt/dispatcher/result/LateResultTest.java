// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LateResult}.
 *
 * <p>RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S09).
 *
 * <p>Story: E37S09; AC-LATE-RESULT-ENTITY; DEC-22, DEC-35
 */
class LateResultTest {

    @Test
    void entityFieldsRoundTrip() {
        LateResult lr = new LateResult();
        UUID packetId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        byte[] signature = new byte[] {10, 20};
        Instant now = Instant.now();

        lr.setPacketId(packetId);
        lr.setWorkerId(workerId);
        lr.setAlgorithm("Ed25519");
        lr.setSignature(signature);
        lr.setResultPayloadJson("{\"bestRank\":1}");
        lr.setReceivedAt(now);

        assertThat(lr.getPacketId()).isEqualTo(packetId);
        assertThat(lr.getWorkerId()).isEqualTo(workerId);
        assertThat(lr.getAlgorithm()).isEqualTo("Ed25519");
        assertThat(lr.getSignature()).isEqualTo(signature);
        assertThat(lr.getResultPayloadJson()).isEqualTo("{\"bestRank\":1}");
        assertThat(lr.getReceivedAt()).isEqualTo(now);
    }
}
