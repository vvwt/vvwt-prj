// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * Public port for tournament lifecycle status transitions (DEC-35, E48S03).
 *
 * <p>Drives the tournament through its status lifecycle per the documented state machine:
 *
 * <ul>
 *   <li>DRAFT → PLANNED via {@link #markPlanned(UUID)}
 *   <li>PLANNED → ACTIVE via {@link #activate(UUID)}
 *   <li>ACTIVE → COMPLETED via {@link #complete(UUID)}
 *   <li>PLANNED or ACTIVE → CANCELLED via {@link #cancel(UUID)}
 * </ul>
 *
 * <p>All methods acquire a per-tournament pessimistic DB row-lock as their first read (DEC-37
 * Clause B). This serialises concurrent lifecycle transitions on the same tournament aggregate root
 * — preventing lost updates when two browser tabs invoke the same transition simultaneously.
 *
 * <p>Match-Cancel-Lockdown (cancelling matches when a tournament is cancelled) is NOT in scope for
 * this service — see E48S04.
 *
 * <p>The sole implementation is {@link
 * de.vvwt.tm.tournament.internal.DefaultTournamentLifecycleService} in the {@code
 * tournament.internal} package per DEC-35.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultTournamentLifecycleService
 * @see <a href="DEC-35">DEC-35 — package layout: interface in public package</a>
 * @see <a href="DEC-37">DEC-37 — cascade serialization via per-tournament pessimistic DB
 *     row-lock</a>
 * @see <a href="E48S03">E48S03 — Tournament lifecycle transitions</a>
 */
public interface TournamentLifecycleService {

    /**
     * Transitions the tournament from {@code DRAFT} to {@code PLANNED}.
     *
     * <p>Acquires a per-tournament DB row-lock (DEC-37 Clause B) as the first read.
     *
     * @param tournamentId the tournament UUID
     * @return the updated tournament with {@code status = "PLANNED"}
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the tournament's current status
     *     is not {@code DRAFT}
     * @throws IllegalArgumentException if no tournament with the given id exists
     */
    Tournament markPlanned(UUID tournamentId);

    /**
     * Transitions the tournament from {@code PLANNED} to {@code ACTIVE}.
     *
     * <p>Acquires a per-tournament DB row-lock (DEC-37 Clause B) as the first read.
     *
     * @param tournamentId the tournament UUID
     * @return the updated tournament with {@code status = "ACTIVE"}
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the tournament's current status
     *     is not {@code PLANNED}
     * @throws IllegalArgumentException if no tournament with the given id exists
     */
    Tournament activate(UUID tournamentId);

    /**
     * Transitions the tournament from {@code ACTIVE} to {@code COMPLETED}.
     *
     * <p>Acquires a per-tournament DB row-lock (DEC-37 Clause B) as the first read.
     *
     * @param tournamentId the tournament UUID
     * @return the updated tournament with {@code status = "COMPLETED"}
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the tournament's current status
     *     is not {@code ACTIVE}
     * @throws IllegalArgumentException if no tournament with the given id exists
     */
    Tournament complete(UUID tournamentId);

    /**
     * Transitions the tournament from {@code PLANNED} or {@code ACTIVE} to {@code CANCELLED}.
     *
     * <p>Acquires a per-tournament DB row-lock (DEC-37 Clause B) as the first read.
     *
     * <p>This method only updates {@code tournament.status}. Match-Bulk-Cancel is out of scope for
     * this service — see E48S04.
     *
     * @param tournamentId the tournament UUID
     * @return the updated tournament with {@code status = "CANCELLED"}
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the tournament's current status
     *     is not {@code PLANNED} or {@code ACTIVE}
     * @throws IllegalArgumentException if no tournament with the given id exists
     */
    Tournament cancel(UUID tournamentId);
}
