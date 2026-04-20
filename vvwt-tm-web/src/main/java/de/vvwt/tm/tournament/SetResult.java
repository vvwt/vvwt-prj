package de.vvwt.tm.tournament;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Domain entity for the {@code set_result} table (DEC-21, DEC-22, E21S05).
 *
 * <p>Reconstruction-in-place counterpart of {@code de.vvwt.tm.domain.SetResult} (READ ONLY
 * reference; not imported). Boundary-API per inventory line 182 — consumed by {@code
 * display/DisplayOverviewService}. Elevated to boundary-API per Brief D-4 aggregate-oriented
 * decomposition.
 *
 * <p>A {@code SetResult} represents the outcome of a single set within a match. It is the smallest
 * mutation unit of the scoring system.
 *
 * <h2>Composite primary key</h2>
 *
 * <p>The composite PK {@code (match_id, set_index)} is the natural key used by the cascade service
 * for SELECT-before-INSERT/UPDATE correction detection. Spring Data JDBC does not support composite
 * primary keys natively with {@code @Id}. This entity is mapped as a plain {@link Table}-annotated
 * POJO without a Spring Data {@code @Id} field. Persistence uses {@link SetResultRepository} via
 * {@code JdbcTemplate}.
 *
 * <h2>Tenant scope (DEC-5, DEC-17)</h2>
 *
 * <p>{@code tenantId} is NOT NULL — every set result belongs to exactly one tenant.
 *
 * <h2>Denormalized phase_id</h2>
 *
 * <p>{@code phaseId} is denormalized: the authoritative phase is reachable via {@code
 * match.phase_id}. Kept here for query performance (cascade service's auto-lap-advance step).
 *
 * @see SetState
 * @see SetResultInput
 * @see <a href="DEC-21">DEC-21 — boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — inventory line 182</a>
 */
@Table("set_result")
public class SetResult {

    /** FK to {@code match(id)} — part of the composite PK {@code (match_id, set_index)}. */
    private UUID matchId;

    /** 0-based index of this set within its match — part of the composite PK. */
    private int setIndex;

    /** Tenant scope — NOT NULL per DEC-17. FK references {@code tenants(id)}. */
    private UUID tenantId;

    /** FK to {@code phase(id)} — denormalized for query performance. NOT NULL. */
    private UUID phaseId;

    /**
     * Points scored by team 1 in this set. NOT NULL. Must be {@code >= 0} (enforced by schema CHECK
     * constraint and by this layer).
     */
    private int team1Points;

    /**
     * Points scored by team 2 in this set. NOT NULL. Must be {@code >= 0} (enforced by schema CHECK
     * constraint and by this layer).
     */
    private int team2Points;

    /**
     * Set lifecycle state stored as legacy integer code from {@link SetState}. Use {@link
     * #getSetState()} and {@link #setSetState(SetState)} for type-safe access.
     */
    private int setState;

    /** Last-modification timestamp cache (legacy parity). Not a replacement for audit_log. */
    private LocalDateTime changeTime;

    /** Row creation timestamp — set by the database via DEFAULT CURRENT_TIMESTAMP. */
    private LocalDateTime createdAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Default constructor required by Spring Data JDBC / reflection-based mappers. */
    public SetResult() {}

    /**
     * Full constructor for programmatic creation (e.g., by the cascade service).
     *
     * @param matchId FK to match — part of composite PK (NOT NULL)
     * @param setIndex 0-based set index within the match — part of composite PK
     * @param tenantId tenant scope (NOT NULL, DEC-17)
     * @param phaseId denormalized phase FK (NOT NULL)
     * @param team1Points points scored by team 1 (must be >= 0)
     * @param team2Points points scored by team 2 (must be >= 0)
     * @param setState legacy int state code (use {@link SetState#getLegacyCode()})
     * @param changeTime last-modification cache (may be null; DB sets default)
     * @param createdAt creation timestamp (may be null; DB sets default)
     * @throws NullPointerException if matchId, tenantId, or phaseId is null
     * @throws IllegalArgumentException if team1Points or team2Points is negative
     */
    public SetResult(
            UUID matchId,
            int setIndex,
            UUID tenantId,
            UUID phaseId,
            int team1Points,
            int team2Points,
            int setState,
            LocalDateTime changeTime,
            LocalDateTime createdAt) {
        if (matchId == null) {
            throw new NullPointerException("matchId must not be null");
        }
        if (tenantId == null) {
            throw new NullPointerException("tenantId must not be null");
        }
        if (phaseId == null) {
            throw new NullPointerException("phaseId must not be null");
        }
        if (team1Points < 0) {
            throw new IllegalArgumentException("team1Points must be >= 0, got: " + team1Points);
        }
        if (team2Points < 0) {
            throw new IllegalArgumentException("team2Points must be >= 0, got: " + team2Points);
        }
        this.matchId = matchId;
        this.setIndex = setIndex;
        this.tenantId = tenantId;
        this.phaseId = phaseId;
        this.team1Points = team1Points;
        this.team2Points = team2Points;
        this.setState = setState;
        this.changeTime = changeTime;
        this.createdAt = createdAt;
    }

    // -------------------------------------------------------------------------
    // Type-safe SetState convenience methods
    // -------------------------------------------------------------------------

    /**
     * Returns the set lifecycle state as the {@link SetState} enum.
     *
     * @return the current state
     * @throws IllegalArgumentException if the stored integer code is unknown
     */
    public SetState getSetState() {
        return SetState.fromLegacyCode(this.setState);
    }

    /**
     * Sets the set lifecycle state from a {@link SetState} enum value.
     *
     * @param setStateValue the new state (must not be {@code null})
     * @throws NullPointerException if {@code setStateValue} is {@code null}
     */
    public void setSetState(SetState setStateValue) {
        if (setStateValue == null) {
            throw new NullPointerException("setStateValue must not be null");
        }
        this.setState = setStateValue.getLegacyCode();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID getMatchId() {
        return matchId;
    }

    public void setMatchId(UUID matchId) {
        this.matchId = matchId;
    }

    public int getSetIndex() {
        return setIndex;
    }

    public void setSetIndex(int setIndex) {
        this.setIndex = setIndex;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getPhaseId() {
        return phaseId;
    }

    public void setPhaseId(UUID phaseId) {
        this.phaseId = phaseId;
    }

    public int getTeam1Points() {
        return team1Points;
    }

    public void setTeam1Points(int team1Points) {
        this.team1Points = team1Points;
    }

    public int getTeam2Points() {
        return team2Points;
    }

    public void setTeam2Points(int team2Points) {
        this.team2Points = team2Points;
    }

    public int getSetStateCode() {
        return setState;
    }

    public void setSetStateCode(int setState) {
        this.setState = setState;
    }

    public LocalDateTime getChangeTime() {
        return changeTime;
    }

    public void setChangeTime(LocalDateTime changeTime) {
        this.changeTime = changeTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
