// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.dto.reader;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * First WebSocket frame payload sent after a client connects to the reader stream (E38S06 AC14).
 *
 * <p>The first frame from the server is {@code Envelope<StreamHello>}, carrying the server's
 * recommended poll cadence for clients that fall back to HTTP polling. This allows the client
 * (E38S08) to respect a server-configurable cadence without hardcoding it.
 *
 * <p>Following frames: {@code Envelope<TournamentSnapshot>} (per-team view), then {@code
 * Envelope<DomainEvent>} per delta.
 *
 * @param pollCadenceSeconds server-recommended poll cadence in seconds for HTTP fallback clients
 * @param schemaVersion current wire schema version (mirrors {@link
 *     de.vvwt.info.dto.envelope.Envelope#SCHEMA_VERSION})
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC14</a>
 */
public record StreamHello(
        @JsonProperty("poll_cadence_seconds") int pollCadenceSeconds,
        @JsonProperty("schemaVersion") String schemaVersion) {}
