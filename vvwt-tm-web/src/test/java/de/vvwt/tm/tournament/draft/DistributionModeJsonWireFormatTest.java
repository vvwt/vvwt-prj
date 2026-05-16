// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Wire-format preservation tests for the {@code distributionMode} field in {@link DraftSection}.
 *
 * <p>E58S02 (DEC-73 D-2): {@code distributionMode} migrated from {@code DistributionMode} enum to
 * plain {@code String}. This test replaces the old {@code DistributionMode} enum wire-format tests.
 *
 * <p>Verifies that JSON round-trip preserves the wire-format strings ({@code "sequential"}, {@code
 * "round_robin"}) and that the default ({@code null}) resolves to {@code "sequential"}.
 *
 * @see DraftSection
 * @see <a href="E58S02">E58S02 — DistributionMode enum removed; String registry key (DEC-73
 *     D-2)</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 */
class DistributionModeJsonWireFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Round-trip: DraftSection JSON → getDistributionMode() → JSON
    // -------------------------------------------------------------------------

    @Test
    void wireFormat_sequential_roundTrips() throws Exception {
        String json =
                "{\"sectionNumber\":1,\"sortType\":\"team_number\",\"groupCount\":2,"
                        + "\"gameMode\":\"roundRobin\",\"lapBreakTimeMinutes\":0,"
                        + "\"sectionBreakTimeMinutes\":0,\"lapTimeMinutes\":15,\"setQuantity\":1,"
                        + "\"breaks\":[],\"distributionMode\":\"sequential\"}";

        DraftSection section = mapper.readValue(json, DraftSection.class);
        assertThat(section.getDistributionMode()).isEqualTo("sequential");

        String serialized = mapper.writeValueAsString(section);
        assertThat(serialized).contains("\"distributionMode\":\"sequential\"");
    }

    @Test
    void wireFormat_roundRobin_roundTrips() throws Exception {
        String json =
                "{\"sectionNumber\":1,\"sortType\":\"team_number\",\"groupCount\":2,"
                        + "\"gameMode\":\"roundRobin\",\"lapBreakTimeMinutes\":0,"
                        + "\"sectionBreakTimeMinutes\":0,\"lapTimeMinutes\":15,\"setQuantity\":1,"
                        + "\"breaks\":[],\"distributionMode\":\"round_robin\"}";

        DraftSection section = mapper.readValue(json, DraftSection.class);
        assertThat(section.getDistributionMode()).isEqualTo("round_robin");

        String serialized = mapper.writeValueAsString(section);
        assertThat(serialized).contains("\"distributionMode\":\"round_robin\"");
    }

    @Test
    void wireFormat_absent_defaultsToSequential() throws Exception {
        String jsonWithoutField =
                "{\"sectionNumber\":1,\"sortType\":\"team_number\",\"groupCount\":2,"
                        + "\"gameMode\":\"roundRobin\",\"lapBreakTimeMinutes\":0,"
                        + "\"sectionBreakTimeMinutes\":0,\"lapTimeMinutes\":15,\"setQuantity\":1,"
                        + "\"breaks\":[]}";

        DraftSection section = mapper.readValue(jsonWithoutField, DraftSection.class);
        assertThat(section.getDistributionMode())
                .as("absent distributionMode must default to 'sequential'")
                .isEqualTo("sequential");
    }
}
