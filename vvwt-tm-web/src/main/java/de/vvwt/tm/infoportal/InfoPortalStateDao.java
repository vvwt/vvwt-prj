// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

import java.time.Instant;
import java.util.Optional;

/**
 * JDBC DAO interface for the {@code info_portal_state} table (AC12).
 *
 * <p>Per-tenant DataSource is provided by the TM per-tenant routing infrastructure (DEC-20
 * DB-per-Tenant).
 *
 * <p>Key contract (AC12):
 *
 * <ul>
 *   <li>Seq generation: atomic {@code UPDATE ... SET last_published_seq = last_published_seq + 1
 *       RETURNING last_published_seq} — TM assigns seq BEFORE posting to info-server.
 *   <li>On 409 FULL_RESYNC: seq is NOT reset to 1; snapshot carries current {@code
 *       last_published_seq}; server overwrites its {@code last_applied_seq} to that value.
 *   <li>DEC-26 three rules enforced via the DEC-26+DEC-46 DAO IT test support in tests.
 *   <li>DEC-46 scope extension: three rules apply to this table per DEC-46 clause #1.
 * </ul>
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09
 *     AC12</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-26.md">DEC-26</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-46.md">DEC-46</a>
 * @since E57S01 (DEC-58/DEC-72 interface extraction)
 */
public interface InfoPortalStateDao {

    /**
     * Upsert a registration row. If the row already exists (same PK), it is updated with the new
     * token and secret. {@code last_published_seq} is preserved on update (not reset to 0).
     *
     * @param locationId location identifier
     * @param tournamentId tournament identifier
     * @param tournamentToken opaque bearer token from info-server
     * @param perTournamentSecret 32-byte HMAC secret from info-server
     */
    void upsertRegistration(
            String locationId,
            String tournamentId,
            String tournamentToken,
            byte[] perTournamentSecret);

    /**
     * Atomically increments {@code last_published_seq} by 1 and returns the new value (AC12).
     *
     * @return the new (incremented) sequence number
     * @throws IllegalStateException if the tournament row is not found
     */
    long incrementAndGetSeq(String locationId, String tournamentId);

    /**
     * Returns the current {@code last_published_seq} without incrementing. Used for snapshot-post
     * payloads (seq NOT reset on FULL_RESYNC per AC12).
     */
    long findCurrentSeq(String locationId, String tournamentId);

    /** Updates {@code last_published_at} to the given instant after a successful publish. */
    void updateLastPublishedAt(String locationId, String tournamentId, Instant at);

    /** Finds the state record for a specific tournament. Returns empty if not registered. */
    Optional<InfoPortalStateRecord> findByTournament(String locationId, String tournamentId);
}
