package de.vvwt.tm.domain;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity for the {@code match_outcome} table (E03S04, AC2, D-32).
 *
 * <p>A {@code MatchOutcome} is a 1:1 aggregate of a completed match, storing the final set scores
 * and ball scores across all {@code SetResult} rows. It is populated by cascade step 4 (D-21) and
 * kept synchronized with {@code match.state} by cascade step 6.
 *
 * <h2>Match-state invariant (D-32)</h2>
 *
 * <p>The {@code computedState} column must always equal {@code match.state}. This invariant is
 * maintained by the cascade service (E03S11), not by a database constraint (H2 does not support
 * cross-table CHECK constraints). The helper method {@link #matchesComputedState(Match)} allows the
 * cascade service to verify the invariant before committing a result.
 *
 * <h2>Backward compatibility (E03S08)</h2>
 *
 * <p>This class was introduced as a minimal value class in E03S08 with three fields ({@code
 * team1SetsWon}, {@code team2SetsWon}, {@code setCount}) for use by {@code ScoringRule}
 * implementations. E03S04 augments it with {@code @Table}, {@code @Id}, and the additional
 * DB-mapped fields required by AC2. All existing code that constructs {@code MatchOutcome} using
 * the three-field constructor continues to compile and run correctly — the new persistence-layer
 * fields are set by the repository layer.
 *
 * <h2>Tenant scope (DEC-5, DEC-17)</h2>
 *
 * <p>{@code tenantId} is NOT NULL — every match outcome belongs to exactly one tenant.
 *
 * @see Match
 * @see de.vvwt.tm.domain.rules.ScoringRule
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S04.story.md">Story
 *     E03S04</a>
 */
@Table("match_outcome")
public class MatchOutcome {

    /**
     * Primary key — also the FK to {@code match(id)} (1:1 relationship). Set by the application
     * layer before insert (same UUID as the corresponding Match).
     */
    @Id private UUID matchId;

    /**
     * Tenant scope — NOT NULL per DEC-17. Every match outcome belongs to exactly one tenant. FK
     * references {@code tenants(id)}.
     */
    private UUID tenantId;

    /** Sets won by the team in the first slot (team1). Default 0. */
    private int team1SetsWon;

    /** Balls (points) won by the team in the first slot across all sets. Default 0. */
    private int team1BallsWon;

    /** Sets won by the team in the second slot (team2). Default 0. */
    private int team2SetsWon;

    /** Balls (points) won by the team in the second slot across all sets. Default 0. */
    private int team2BallsWon;

    /** Total number of sets played (team1SetsWon + team2SetsWon for all V1 formats). Default 0. */
    private int setCount;

    /**
     * The {@link MatchState} derived from this outcome, stored as a legacy integer code. Must
     * always equal {@code match.state} (D-32 invariant). Use {@link #getComputedMatchState()} and
     * {@link #setComputedMatchState(MatchState)} for type-safe access.
     */
    private int computedState;

    /**
     * Last-updated timestamp — set by the database via DEFAULT CURRENT_TIMESTAMP on insert; updated
     * by the cascade service on each correction.
     */
    private LocalDateTime updatedAt;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** Default constructor required by Spring Data JDBC. */
    public MatchOutcome() {}

    /**
     * Minimal constructor for {@link de.vvwt.tm.domain.rules.ScoringRule} usage (E03S08 backward
     * compatibility). Creates an instance without persistence-layer fields set.
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
     * Full constructor for persistence-layer creation (cascade service, E03S11).
     *
     * @param matchId primary key / FK to {@code match(id)}
     * @param tenantId tenant scope (NOT NULL)
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
            UUID tenantId,
            int team1SetsWon,
            int team1BallsWon,
            int team2SetsWon,
            int team2BallsWon,
            int setCount,
            int computedState,
            LocalDateTime updatedAt) {
        this.matchId = matchId;
        this.tenantId = tenantId;
        this.team1SetsWon = team1SetsWon;
        this.team1BallsWon = team1BallsWon;
        this.team2SetsWon = team2SetsWon;
        this.team2BallsWon = team2BallsWon;
        this.setCount = setCount;
        this.computedState = computedState;
        this.updatedAt = updatedAt;
    }

    // -----------------------------------------------------------------------
    // D-32 Match-state invariant helper (AC6)
    // -----------------------------------------------------------------------

    /**
     * Verifies the D-32 Match-state invariant: {@code this.computedState == match.state}.
     *
     * <p>The cascade service (E03S11) calls this helper after cascade step 4 and before committing
     * the outcome to confirm that the {@link MatchOutcome} reflects the same lifecycle state as the
     * {@link Match} it is attached to.
     *
     * @param match the {@link Match} whose state is being compared
     * @return {@code true} if {@code computedState} equals {@code match.state}; {@code false}
     *     otherwise (signals a cascade error that should cause the transaction to roll back)
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
     * @throws IllegalArgumentException if the stored integer code is unknown (data corruption
     *     guard)
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

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
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
