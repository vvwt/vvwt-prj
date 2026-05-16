// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.tournament;

import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity record for the {@code tournament_delta} table.
 *
 * <p>Represents one event in a tournament's event log. The composite PK {@code (tournament_id,
 * seq)} enforces uniqueness of sequence numbers within a tournament at the DB level (AC13a).
 *
 * <p>Gap-free monotonicity of {@code seq} within a tournament is a service-layer invariant
 * (publisher service compares incoming {@code seq} against {@code tournament.last_applied_seq} and
 * rejects if {@code seq != last_applied_seq + 1}) — this is E38S05 scope, not DAO scope.
 *
 * @param id composite PK — {@code (tournament_id, seq)}
 * @param eventType event type identifier (e.g., {@code "TEAM_SCORE_UPDATED"})
 * @param eventPayload JSON-encoded event payload
 * @param appliedAt UTC timestamp when the delta was applied
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC13</a>
 */
@Table("tournament_delta")
public record TournamentDeltaRecord(
        @Id Key id,
        @Column("event_type") String eventType,
        @Column("event_payload") String eventPayload,
        @Column("applied_at") LocalDateTime appliedAt) {

    /**
     * Composite primary key for {@code tournament_delta}: {@code (tournament_id, seq)}.
     *
     * @param tournamentId FK → tournament.tournament_id
     * @param seq monotonically increasing sequence number within the tournament
     */
    public record Key(@Column("tournament_id") String tournamentId, @Column("seq") long seq) {}
}
