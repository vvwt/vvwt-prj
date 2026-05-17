// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity for the {@code match_outcome} table (DEC-21, DEC-22, E21S05).
 *
 * <p>Reconstruction-in-place counterpart of {@code de.vvwt.tm.domain.MatchOutcome} (READ ONLY
 * reference; not imported). Per AC-TDD-MatchOutcome shape confirmation: the legacy class is a
 * {@code @Table("match_outcome")} entity (NOT an enum) — a 1:1 aggregate of a completed match,
 * storing the final set scores and ball scores across all {@link SetResult} rows.
 *
 * <p>Boundary-API per inventory line 175 — consumed by {@code ScoringRule} implementations in the
 * {@code scoring} context (CascadeRecomputeService). Refined to boundary-API for public-surface
 * consistency per Brief D-4 (AC elevation note in E21S05 story context).
 *
 * <h2>1:1 with Match</h2>
 *
 * <p>{@code matchId} is both the primary key and the FK to {@code match(id)}. Same UUID as the
 * corresponding Match is used — set by the application layer before insert.
 *
 * <h2>Match-state invariant (D-32)</h2>
 *
 * <p>The {@code computedState} column must always equal {@code match.state}. This invariant is
 * maintained by the cascade service (E03S11), not by a database constraint. The helper method
 * {@link #matchesComputedState(Match)} allows the cascade service to verify the invariant before
 * committing a result.
 *
 * <h2>Tenant scope (DEC-5, DEC-17)</h2>
 *
 * <p>{@code tenantId} is NOT NULL — every match outcome belongs to exactly one tenant.
 *
 * @see Match
 * @see MatchState
 * @see <a href="DEC-21">DEC-21 — boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — inventory line 175 (MatchOutcome type confirmation: entity)</a>
 */
@Table("match_outcome")
public class MatchOutcome {

    /**
     * Primary key — also the FK to {@code match(id)} (1:1 relationship). Set by the application
     * layer before insert (same UUID as the corresponding Match).
     */
    @Id private UUID matchId;

    /** Sets won by team 1 (team in the first slot). Default 0. */
    private int team1SetsWon;

    /** Balls (points) won by team 1 across all sets. Default 0. */
    private int team1BallsWon;

    /** Sets won by team 2 (team in the second slot). Default 0. */
    private int team2SetsWon;

    /** Balls (points) won by team 2 across all sets. Default 0. */
    private int team2BallsWon;

    /** Total number of sets played. Default 0. */
    private int setCount;

    /**
     * The {@link MatchState} derived from this outcome, stored as legacy integer code. Must always
     * equal {@code match.state} (D-32 invariant). Use {@link #getComputedMatchState()} and {@link
     * #setComputedMatchState(MatchState)} for type-safe access.
     */
    private int computedState;

    /** Last-updated timestamp — set by the database via DEFAULT CURRENT_TIMESTAMP on insert. */
    private LocalDateTime updatedAt;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** Default constructor required by Spring Data JDBC. */
    public MatchOutcome() {}

    /**
     * Minimal constructor for {@link de.vvwt.tm.scoring.ScoringRule} usage.
     *
     * @param team1SetsWon sets won by team 1 (&ge; 0)
     * @param team2SetsWon sets won by team 2 (&ge; 0)
     * @param setCount total sets played (&ge; 0)
     */
    public MatchOutcome(int team1SetsWon, int team2SetsWon, int setCount) {
        this.team1SetsWon = team1SetsWon;
        this.team2SetsWon = team2SetsWon;
        this.setCount = setCount;
    }

    /**
     * Full constructor for persistence-layer creation (cascade service).
     *
     * @param matchId primary key / FK to {@code match(id)}
     * @param team1SetsWon sets won by team 1 (&ge; 0)
     * @param team1BallsWon balls won by team 1 (&ge; 0)
     * @param team2SetsWon sets won by team 2 (&ge; 0)
     * @param team2BallsWon balls won by team 2 (&ge; 0)
     * @param setCount total sets played (&ge; 0)
     * @param computedState {@link MatchState#getLegacyCode()} of the derived terminal state
     * @param updatedAt last-update timestamp (may be null; DB sets default on insert)
     */
    public MatchOutcome(
            UUID matchId,
            int team1SetsWon,
            int team1BallsWon,
            int team2SetsWon,
            int team2BallsWon,
            int setCount,
            int computedState,
            LocalDateTime updatedAt) {
        this.matchId = matchId;
        this.team1SetsWon = team1SetsWon;
        this.team1BallsWon = team1BallsWon;
        this.team2SetsWon = team2SetsWon;
        this.team2BallsWon = team2BallsWon;
        this.setCount = setCount;
        this.computedState = computedState;
        this.updatedAt = updatedAt;
    }

    // -----------------------------------------------------------------------
    // D-32 Match-state invariant helper
    // -----------------------------------------------------------------------

    /**
     * Verifies the D-32 Match-state invariant: {@code this.computedState == match.state}.
     *
     * @param match the {@link Match} whose state is being compared
     * @return {@code true} if {@code computedState} equals {@code match.state}
     * @throws NullPointerException if {@code match} is {@code null}
     */
    public boolean matchesComputedState(Match match) {
        if (match == null) {
            throw new NullPointerException("match must not be null");
        }
        return this.computedState == match.getState();
    }

    // -----------------------------------------------------------------------
    // Type-safe MatchState convenience methods
    // -----------------------------------------------------------------------

    /**
     * Returns the computed match lifecycle state as a {@link MatchState} enum.
     *
     * @return the computed state
     * @throws IllegalArgumentException if the stored integer code is unknown
     */
    public MatchState getComputedMatchState() {
        return MatchState.fromLegacyCode(this.computedState);
    }

    /**
     * Sets the computed match lifecycle state from a {@link MatchState} enum value.
     *
     * @param matchState the derived terminal state (must not be {@code null})
     * @throws NullPointerException if {@code matchState} is {@code null}
     */
    public void setComputedMatchState(MatchState matchState) {
        if (matchState == null) {
            throw new NullPointerException("matchState must not be null");
        }
        this.computedState = matchState.getLegacyCode();
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public UUID getMatchId() {
        return matchId;
    }

    public void setMatchId(UUID matchId) {
        this.matchId = matchId;
    }

    public int getTeam1SetsWon() {
        return team1SetsWon;
    }

    public void setTeam1SetsWon(int team1SetsWon) {
        this.team1SetsWon = team1SetsWon;
    }

    public int getTeam1BallsWon() {
        return team1BallsWon;
    }

    public void setTeam1BallsWon(int team1BallsWon) {
        this.team1BallsWon = team1BallsWon;
    }

    public int getTeam2SetsWon() {
        return team2SetsWon;
    }

    public void setTeam2SetsWon(int team2SetsWon) {
        this.team2SetsWon = team2SetsWon;
    }

    public int getTeam2BallsWon() {
        return team2BallsWon;
    }

    public void setTeam2BallsWon(int team2BallsWon) {
        this.team2BallsWon = team2BallsWon;
    }

    public int getSetCount() {
        return setCount;
    }

    public void setSetCount(int setCount) {
        this.setCount = setCount;
    }

    public int getComputedState() {
        return computedState;
    }

    public void setComputedState(int computedState) {
        this.computedState = computedState;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "MatchOutcome{matchId="
                + matchId
                + ", team1SetsWon="
                + team1SetsWon
                + ", team2SetsWon="
                + team2SetsWon
                + ", setCount="
                + setCount
                + ", computedState="
                + computedState
                + '}';
    }
}
