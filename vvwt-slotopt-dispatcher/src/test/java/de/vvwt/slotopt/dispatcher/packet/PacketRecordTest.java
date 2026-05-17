// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.packet;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PacketRecord}.
 *
 * <p>Same package (white-box per DEC-36 same-package rule). Verifies entity field accessors and
 * status string contract.
 *
 * <p>RED-first per DEC-22 / AC-PACKET-RECORD-ENTITY (E37S08).
 *
 * <p>Story: E37S08; AC-PACKET-RECORD-ENTITY; DEC-22, DEC-36
 */
class PacketRecordTest {

    @Test
    void fieldRoundTrip_allRequired() {
        PacketRecord r = new PacketRecord();
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        r.setPacketId(packetId);
        r.setJobId(jobId);
        r.setPacketPayloadJson("{\"rankFrom\":0,\"rankTo\":100}");
        r.setStatus("UNCLAIMED");

        assertThat(r.getPacketId()).isEqualTo(packetId);
        assertThat(r.getJobId()).isEqualTo(jobId);
        assertThat(r.getPacketPayloadJson()).isEqualTo("{\"rankFrom\":0,\"rankTo\":100}");
        assertThat(r.getStatus()).isEqualTo("UNCLAIMED");
        assertThat(r.getClaimedByWorkerId()).isNull();
        assertThat(r.getClaimedAt()).isNull();
        assertThat(r.getTimeoutAt()).isNull();
    }

    @Test
    void fieldRoundTrip_nullable() {
        PacketRecord r = new PacketRecord();
        UUID workerId = UUID.randomUUID();
        Instant now = Instant.now();

        r.setClaimedByWorkerId(workerId);
        r.setClaimedAt(now);
        r.setTimeoutAt(now.plusSeconds(300));

        assertThat(r.getClaimedByWorkerId()).isEqualTo(workerId);
        assertThat(r.getClaimedAt()).isEqualTo(now);
        assertThat(r.getTimeoutAt()).isEqualTo(now.plusSeconds(300));
    }

    @Test
    void statusValues_contractual() {
        // Status values per AC-PACKET-RECORD-ENTITY
        for (String status : new String[] {"UNCLAIMED", "CLAIMED", "RESULT_RECEIVED", "TIMEDOUT"}) {
            PacketRecord r = new PacketRecord();
            r.setStatus(status);
            assertThat(r.getStatus()).isEqualTo(status);
        }
    }

    @Test
    void idFieldAccessible() {
        PacketRecord r = new PacketRecord();
        r.setId(42L);
        assertThat(r.getId()).isEqualTo(42L);
    }
}
