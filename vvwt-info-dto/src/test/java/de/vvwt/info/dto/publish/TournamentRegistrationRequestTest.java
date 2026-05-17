// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.dto.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TournamentRegistrationRequest} — serialization/deserialization contract.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S05.story.md">E38S05 AC2</a>
 */
class TournamentRegistrationRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializes_teamUuids_asJsonArray() throws Exception {
        UUID team1 = UUID.randomUUID();
        UUID team2 = UUID.randomUUID();
        var request = new TournamentRegistrationRequest(List.of(team1, team2));
        String json = mapper.writeValueAsString(request);
        assertThat(json).contains("team_uuids");
        assertThat(json).contains(team1.toString());
        assertThat(json).contains(team2.toString());
    }

    @Test
    void deserializes_fromJson() throws Exception {
        UUID team1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String json =
                """
                {"team_uuids":["11111111-1111-1111-1111-111111111111"]}""";
        var request = mapper.readValue(json, TournamentRegistrationRequest.class);
        assertThat(request.teamUuids()).containsExactly(team1);
    }

    @Test
    void emptyTeamList_isAllowed() {
        var request = new TournamentRegistrationRequest(List.of());
        assertThat(request.teamUuids()).isEmpty();
    }
}
