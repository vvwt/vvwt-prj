// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit test for {@link TournamentDeltaRecord} (DEC-22 Iron Law, AC1).
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC1</a>
 */
class TournamentDeltaRecordTest {

    @Test
    void record_fields_accessible() {
        var now = LocalDateTime.now();
        var key = new TournamentDeltaRecord.Key("t-1", 1L);
        var record = new TournamentDeltaRecord(key, "TEAM_SCORE_UPDATED", "{\"score\":3}", now);

        assertThat(record.id()).isEqualTo(key);
        assertThat(record.id().tournamentId()).isEqualTo("t-1");
        assertThat(record.id().seq()).isEqualTo(1L);
        assertThat(record.eventType()).isEqualTo("TEAM_SCORE_UPDATED");
        assertThat(record.eventPayload()).isEqualTo("{\"score\":3}");
        assertThat(record.appliedAt()).isEqualTo(now);
    }

    @Test
    void composite_key_equality() {
        var k1 = new TournamentDeltaRecord.Key("t-1", 1L);
        var k2 = new TournamentDeltaRecord.Key("t-1", 1L);
        assertThat(k1).isEqualTo(k2);
    }
}
