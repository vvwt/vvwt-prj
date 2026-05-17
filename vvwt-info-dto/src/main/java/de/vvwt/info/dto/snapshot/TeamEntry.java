// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.dto.snapshot;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a team registered in a tournament, with its UUID identifier for HMAC validation
 * (E38S06 AC6 — HMAC URL validation iterates team UUIDs from {@code tournament.state}).
 *
 * <p>The {@link #teamId} is the stable UUID used by the server to compute {@code
 * HMAC-SHA256(per_tournament_secret, teamId)} and validate URL team tokens.
 *
 * <p>The {@link #name} and {@link #number} are the public display fields (per AC12 — team list
 * contains number + name only).
 *
 * @param teamId stable UUID — used for HMAC computation server-side (never exposed to clients
 *     except in the computed token form)
 * @param name team display name
 * @param number team number within the tournament
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC6,
 *     AC12</a>
 */
public record TeamEntry(
        @JsonProperty("teamId") String teamId,
        @JsonProperty("name") String name,
        @JsonProperty("number") int number) {}
