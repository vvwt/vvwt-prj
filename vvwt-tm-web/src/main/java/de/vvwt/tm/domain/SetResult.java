package de.vvwt.tm.domain;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Domain entity for the {@code set_result} table.
 *
 * <p>A {@code SetResult} represents the outcome of a single set within a match. It is the smallest
 * mutation unit of the scoring system. A best-of-5 match has up to 5 {@code SetResult} rows keyed
 * by the composite natural key {@code (match_id, set_index)} where {@code set_index} runs 0-based
 * (0..4 for best-of-5).
 *
 * <h2>Composite primary key (D-36)</h2>
 *
 * <p>The composite PK {@code (match_id, set_index)} is the natural key used by the cascade service
 * (E03S11, D-21 step 2) for SELECT-before-INSERT/UPDATE correction detection: if a row exists at
 * {@code (match_id, set_index)} it is a score correction; if absent, it is a first-time entry. Old
 * values are NULL for first entries; the cascade writes the old values to {@code audit_log}
 * (E03S04) for corrections.
 *
 * <h2>Spring Data JDBC note</h2>
 *
 * <p>Spring Data JDBC does not support composite primary keys natively with {@code @Id}. This
 * entity is mapped as a plain {@link Table}-annotated POJO without a Spring Data repository
 * {@code @Id}. The persistence layer (E03S05) accesses this table via {@code JdbcTemplate} or a
 * custom SQL-based repository, which is appropriate given the composite PK requirement. This
 * decision is documented in the E03S03 impl-report.
 *
 * <h2>Tenant scope (DEC-5, DEC-17)</h2>
 *
 * <p>{@code tenantId} is NOT NULL — every set result belongs to exactly one tenant. FK references
 * {@code tenants(id)}.
 *
 * <h2>Denormalized phase_id</h2>
 *
 * <p>{@code phaseId} is denormalized: the authoritative phase is reachable via {@code
 * match.phase_id}. It is kept here for query performance — the cascade service's auto-lap-advance
 * step 10 (D-21) needs a fast "all open sets in phase X" query.
 *
 * <h2>change_time</h2>
 *
 * <p>{@code changeTime} is a legacy-parity "last modified" cache. It is NOT a replacement for the
 * {@code audit_log} table (added by E03S04), which is the authoritative history.
 *
 * @see SetState
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S03.story.md">Story
 *     E03S03</a>
 */
@Table("set_result")
public class SetResult {

    /**
     * FK to {@code match(id)} — part of the composite PK {@code (match_id, set_index)}. NOT NULL.
     * Every set result belongs to exactly one match.
     */
    private UUID matchId;

    /**
     * 0-based index of this set within its match — part of the composite PK. A best-of-5 match uses
     * set_index values 0, 1, 2, 3, 4. The UI renders "Satz 1, 2, 3, …" by adding 1 at display time.
     * NOT NULL.
     */
    private int setIndex;

    /**
     * Tenant scope — NOT NULL per DEC-17. Every set result belongs to exactly one tenant. FK
     * references {@code tenants(id)}.
     */
    private UUID tenantId;

    /**
     * FK to {@code phase(id)} — denormalized for query performance. The authoritative phase is
     * {@code match.phase_id}. Kept here to support the cascade service's "all sets in a phase's
     * current lap" query (D-21 step 10). NOT NULL.
     */
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
     * Set lifecycle state stored as the legacy integer code from {@link SetState}. Valid codes: 0
     * (OPEN), 1 (WINNER1), 2 (WINNER2), 3 (STANDOFF), -1 (CANCELED). Schema CHECK constraint
     * enforces valid values. Use {@link #getSetState()} and {@link #setSetState(SetState)} for
     * type-safe access.
     */
    private int setState;

    /**
     * Last-modification timestamp cache (legacy parity). NOT a replacement for {@code audit_log} —
     * that is E03S04. Set by the database via DEFAULT CURRENT_TIMESTAMP; updated on every score
     * correction.
     */
    private LocalDateTime changeTime;

    /** Row creation timestamp — set by the database via DEFAULT CURRENT_TIMESTAMP. NOT NULL. */
    private LocalDateTime createdAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Default constructor required by Spring Data JDBC / reflection-based mappers. */
    public SetResult() {}

    /**
     * Full constructor for programmatic creation (e.g., by the cascade service E03S11).
     *
     * @param matchId FK to match — part of composite PK (NOT NULL)
     * @param setIndex 0-based set index within the match — part of composite PK (NOT NULL)
     * @param tenantId tenant scope (NOT NULL, DEC-17)
     * @param phaseId denormalized phase FK (NOT NULL)
     * @param team1Points points scored by team 1 (must be >= 0)
     * @param team2Points points scored by team 2 (must be >= 0)
     * @param setState legacy int state code (use {@link SetState#getLegacyCode()})
     * @param changeTime last-modification cache (may be null; DB sets default)
     * @param createdAt creation timestamp (may be null; DB sets default)
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
     * @throws IllegalArgumentException if the stored integer code is unknown (data corruption
     *     guard)
     */
    public SetState getSetState() {
        return SetState.fromLegacyCode(this.setState);
    }

    /**
     * Sets the set lifecycle state from a {@link SetState} enum value. Stores the corresponding
     * legacy integer code in {@link #setState}.
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
