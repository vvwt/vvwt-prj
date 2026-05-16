// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public port for tenant-scoped {@link Tournament} persistence (DEC-35, E31S01).
 *
 * <p>Hand-authored interface port per DEC-35 §2 — {@code TournamentRepository} uses raw {@link
 * org.springframework.jdbc.core.JdbcTemplate} including the {@link #findByIdForUpdate(UUID)}
 * pessimistic-lock variant that cannot be expressed as a Spring Data query method. The canonical
 * implementation is {@link de.vvwt.tm.tournament.internal.DefaultTournamentRepository}.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultTournamentRepository
 * @see <a href="DEC-35">DEC-35 — package layout: interfaces in public package</a>
 * @see <a href="DEC-37">DEC-37 — cascade serialization via per-tournament pessimistic DB
 *     row-lock</a>
 * @see <a href="E31S01">E31S01 — interface extraction + findByIdForUpdate</a>
 */
public interface TournamentRepository {

    /**
     * Persists a tournament (upsert). Tenant scoping is enforced.
     *
     * @param tournament the tournament to save (id must be set by caller)
     * @return the saved tournament
     */
    Tournament save(Tournament tournament);

    /**
     * Returns the tournament with the given id, scoped to the current tenant.
     *
     * @param id the tournament UUID
     * @return Optional.of(tournament) if found, Optional.empty() otherwise
     */
    Optional<Tournament> findById(UUID id);

    /**
     * Returns all tournaments for the current tenant.
     *
     * @return list of tournaments; never null
     */
    List<Tournament> findAll();

    /**
     * Deletes the tournament with the given id, scoped to the current tenant.
     *
     * @param id the tournament UUID
     */
    void deleteById(UUID id);

    /**
     * Returns the tournament with the given id for update (acquires a pessimistic DB row-lock via
     * {@code SELECT … FOR UPDATE}), scoped to the current tenant.
     *
     * <p>This is the lock-acquisition entry point for cascade serialization per DEC-37 Clause B.
     * Callers (e.g., {@code DefaultScoringService.registerMatchResult} in E31S03) invoke this at
     * the start of the cascade to serialise concurrent result submissions on the same tournament
     * aggregate root.
     *
     * <p>The method is a structural clone of {@link #findById(UUID)} with a {@code FOR UPDATE} SQL
     * suffix — same return type, same parameter list, same exception contract. No separate test is
     * required for this method per DEC-22 §Decision Q-1b (lock-behaviour verification belongs at
     * the call site via E31S03's {@code CascadeLockIT}).
     *
     * @param id the tournament UUID
     * @return the tournament, locked for the duration of the current transaction
     * @throws IllegalArgumentException if no tournament with the given id exists for the current
     *     tenant
     * @see <a href="DEC-37">DEC-37 — per-tournament pessimistic DB row-lock</a>
     * @see <a href="E31S03">E31S03 — CascadeLockIT RED-first verification</a>
     */
    Tournament findByIdForUpdate(UUID id);
}
