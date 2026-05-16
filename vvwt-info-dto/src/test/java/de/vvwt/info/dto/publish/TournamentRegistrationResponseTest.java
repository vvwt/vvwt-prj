// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.dto.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TournamentRegistrationResponse} — serialization/deserialization contract.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S05.story.md">E38S05 AC2</a>
 */
class TournamentRegistrationResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializes_allFields() throws Exception {
        byte[] secret = new byte[32];
        for (int i = 0; i < 32; i++) secret[i] = (byte) i;
        var response = new TournamentRegistrationResponse("tok-abc", secret, "1.0");
        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("tournament_token");
        assertThat(json).contains("per_tournament_secret");
        assertThat(json).contains("schema_version");
        assertThat(json).contains("tok-abc");
        assertThat(json).contains("1.0");
    }

    @Test
    void deserializes_fromJson() throws Exception {
        byte[] secret = new byte[32];
        String b64Secret = Base64.getEncoder().encodeToString(secret);
        String json =
                """
                {"tournament_token":"tok-xyz","per_tournament_secret":"%s","schema_version":"1.0"}"""
                        .formatted(b64Secret);
        var response = mapper.readValue(json, TournamentRegistrationResponse.class);
        assertThat(response.tournamentToken()).isEqualTo("tok-xyz");
        assertThat(response.perTournamentSecret()).isEqualTo(secret);
        assertThat(response.schemaVersion()).isEqualTo("1.0");
    }
}
