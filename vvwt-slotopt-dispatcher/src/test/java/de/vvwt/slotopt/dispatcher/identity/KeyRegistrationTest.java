// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KeyRegistration} entity.
 *
 * <p>RED-first per DEC-22 / AC-KEY-REGISTRATION-ENTITY: written before production class exists.
 * Verifies the entity is a POJO (not a record) per DEC-35.
 *
 * <p>Story: E37S05
 */
class KeyRegistrationTest {

    @Test
    void entityIsNotARecord() {
        // DEC-35: entities remain Spring Data JDBC POJOs, NOT records
        assertThat(KeyRegistration.class.isRecord()).isFalse();
    }

    @Test
    void fieldsAreAccessibleViaGetters() {
        KeyRegistration entity = new KeyRegistration();
        UUID workerId = UUID.randomUUID();
        byte[] keyBytes = new byte[] {1, 2, 3, 4};
        Instant now = Instant.now();

        entity.setId(1L);
        entity.setWorkerId(workerId);
        entity.setAlgorithm("Ed25519");
        entity.setPublicKeyBytes(keyBytes);
        entity.setRole("worker");
        entity.setRegisteredAt(now);

        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getWorkerId()).isEqualTo(workerId);
        assertThat(entity.getAlgorithm()).isEqualTo("Ed25519");
        assertThat(entity.getPublicKeyBytes()).isEqualTo(keyBytes);
        assertThat(entity.getRole()).isEqualTo("worker");
        assertThat(entity.getRegisteredAt()).isEqualTo(now);
    }
}
