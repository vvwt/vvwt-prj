// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.dto.publish;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request payload for the tournament-registration endpoint (AC2 / Brief D-X2 c).
 *
 * <p>Sent inside an {@code Envelope<TournamentRegistrationRequest>} body by the TM publisher. The
 * server uses {@code teamUuids} to record which teams are participating, enabling HMAC-derived
 * team-token validation per D-X3 c1 in E38S06.
 *
 * @param teamUuids UUIDs of participating teams; may be empty for zero-team pre-registration but
 *     must not be null.
 * @see <a href="../../../../../../../../docs/governance/stories/E38S05.story.md">E38S05 AC2</a>
 */
public record TournamentRegistrationRequest(
        @NotNull @JsonProperty("team_uuids") List<UUID> teamUuids) {}
