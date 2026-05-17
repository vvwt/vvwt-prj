// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.tournament;

import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JDBC repository for {@link TournamentDeltaRecord}.
 *
 * <p>Provides access to the {@code tournament_delta} event log table. The composite PK {@code
 * (tournament_id, seq)} enforces uniqueness of sequence numbers within a tournament at the DB level
 * (AC13a). Gap-free monotonicity of {@code seq} is a service-layer invariant enforced by the
 * publisher service (E38S05 scope).
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC13</a>
 */
public interface TournamentDeltaDao
        extends CrudRepository<TournamentDeltaRecord, TournamentDeltaRecord.Key> {

    /**
     * Returns all delta records for a tournament with {@code seq > sinceSeq}, ordered by {@code
     * seq} ascending. Used by the reader endpoint to stream incremental updates.
     *
     * @param tournamentId the tournament to query
     * @param sinceSeq lower bound (exclusive) — return deltas with seq strictly greater than this
     * @return ordered list of delta records since {@code sinceSeq}
     */
    @Query(
            "SELECT * FROM tournament_delta "
                    + "WHERE tournament_id = :tournamentId AND seq > :sinceSeq "
                    + "ORDER BY seq ASC")
    List<TournamentDeltaRecord> findDeltasSince(
            @Param("tournamentId") String tournamentId, @Param("sinceSeq") long sinceSeq);

    /**
     * Inserts a new delta record via a raw INSERT statement (AC3, E38S05).
     *
     * <p>Spring Data JDBC's {@code save()} cannot determine insert vs. update for composite-key
     * entities without {@link org.springframework.data.domain.Persistable}. This explicit INSERT
     * bypasses that limitation and is safe because delta records are append-only.
     *
     * @param tournamentId FK → tournament.tournament_id
     * @param seq monotonically increasing sequence number (composite PK second component)
     * @param eventType event type identifier
     * @param eventPayload JSON-encoded event payload
     * @param appliedAt UTC timestamp of the delta application
     */
    @Modifying
    @Query(
            "INSERT INTO tournament_delta (tournament_id, seq, event_type, event_payload,"
                    + " applied_at) VALUES (:tournamentId, :seq, :eventType, :eventPayload,"
                    + " :appliedAt)")
    void insertDelta(
            @Param("tournamentId") String tournamentId,
            @Param("seq") long seq,
            @Param("eventType") String eventType,
            @Param("eventPayload") String eventPayload,
            @Param("appliedAt") java.time.LocalDateTime appliedAt);

    /**
     * Deletes all delta records for the given tournament (AC6 supersede + AC5 snapshot resync).
     *
     * <p>Used by: (a) atomic supersede — prior deltas purged when a new tournament registration
     * supersedes an existing one; (b) snapshot resync — prior deltas cleared when a full snapshot
     * is applied.
     *
     * @param tournamentId the tournament whose deltas are to be deleted
     */
    @Modifying
    @Query("DELETE FROM tournament_delta WHERE tournament_id = :tournamentId")
    void deleteAllByTournamentId(@Param("tournamentId") String tournamentId);
}
