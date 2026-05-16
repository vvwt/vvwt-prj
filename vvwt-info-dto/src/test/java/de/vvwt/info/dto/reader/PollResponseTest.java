// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.dto.reader;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.event.DomainEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit tests for {@link PollResponse} (E38S06 AC3).
 *
 * <p>Tests written BEFORE the production record exists per DEC-22 Iron Law.
 */
class PollResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void record_hasExpectedFields_emptyDeltas() {
        var resp = new PollResponse(List.of(), 42L);
        assertThat(resp.deltas()).isEmpty();
        assertThat(resp.currentSeq()).isEqualTo(42L);
    }

    @Test
    void record_hasExpectedFields_withDeltas() {
        DomainEvent event = new DomainEvent.ScoreUpdated("match-1", 2, 1);
        var resp = new PollResponse(List.of(event), 99L);
        assertThat(resp.deltas()).hasSize(1);
        assertThat(resp.currentSeq()).isEqualTo(99L);
    }

    @Test
    void serializes_to_json_with_expected_property_names() throws Exception {
        var resp = new PollResponse(List.of(), 7L);
        String json = mapper.writeValueAsString(resp);
        assertThat(json).contains("\"deltas\"");
        assertThat(json).contains("\"current_seq\":7");
    }
}
