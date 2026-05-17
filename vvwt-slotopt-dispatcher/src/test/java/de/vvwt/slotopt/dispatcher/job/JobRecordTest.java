// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JobRecord}.
 *
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S07). Written before production
 * class.
 *
 * <p>Story: E37S07; AC-JOB-RECORD-ENTITY
 */
class JobRecordTest {

    @Test
    void defaultConstructorAndSettersRoundtrip() {
        JobRecord record = new JobRecord();
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();

        record.setJobId(jobId);
        record.setSubmittedAt(now);
        record.setJobDefJson("{\"n\":3}");
        record.setStatus("RECEIVED");

        assertThat(record.getJobId()).isEqualTo(jobId);
        assertThat(record.getSubmittedAt()).isEqualTo(now);
        assertThat(record.getJobDefJson()).isEqualTo("{\"n\":3}");
        assertThat(record.getStatus()).isEqualTo("RECEIVED");
    }

    @Test
    void idIsNullBeforePersistence() {
        JobRecord record = new JobRecord();
        assertThat(record.getId()).isNull();
    }
}
