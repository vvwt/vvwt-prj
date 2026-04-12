package de.vvwt.tm.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Spring Data JDBC entity for the {@code team_avatar_rating} table (E03S04, AC3, D-33).
 *
 * <p>A {@code TeamAvatarRating} is the standings row for a team's avatar in its phase.
 * It is 1:1 with {@link TeamAvatar}: one rating row per avatar, updated by cascade
 * steps 7–8 (D-21) each time a match result is registered.
 *
 * <h2>Sort order for group table derivation (D-33)</h2>
 * <p>The {@link #compareTo(TeamAvatarRating)} method implements the canonical sort order
 * used by the group table view:
 * <ol>
 *   <li>Higher {@code points} ranks first (DESC)</li>
 *   <li>Tie broken by higher {@code setQuotient} (DESC)</li>
 *   <li>Tie broken by higher {@code ballQuotient} (DESC)</li>
 *   <li>Rows with {@code isWithoutAssessment = true} always rank last (D-26)</li>
 * </ol>
 *
 * <h2>Sentinel values for quotients</h2>
 * <p>When a team has no losses, division by zero would produce infinity. The sentinel
 * convention is to use {@link Double#MAX_VALUE} as the quotient in that case (legacy parity).
 * This ensures an undefeated team always outranks a team with any losses when points and
 * raw set/ball counts are tied.
 *
 * <h2>is_without_assessment flag</h2>
 * <p>Copied from {@code team.without_assessment} at avatar creation time. V1 does NOT
 * auto-refresh this flag if the team flag changes mid-tournament (D-26, story notes).
 *
 * <h2>Tenant scope (DEC-5, DEC-17)</h2>
 * <p>{@code tenantId} is NOT NULL — every rating belongs to exactly one tenant.
 *
 * @see TeamAvatar
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S04.story.md">Story E03S04</a>
 */
@Table("team_avatar_rating")
public class TeamAvatarRating implements Comparable<TeamAvatarRating> {

    /**
     * Primary key — also the FK to {@code team_avatar(id)} (1:1 relationship).
     * Set by the application layer before insert (same UUID as the corresponding TeamAvatar).
     */
    @Id
    private UUID avatarId;

    /**
     * Tenant scope — NOT NULL per DEC-17. Every rating belongs to exactly one tenant.
     * FK references {@code tenants(id)}.
     */
    private UUID tenantId;

    /** Total matches played by this avatar. Default 0. */
    private int matchCount;

    /** Total sets played (wins + losses). Default 0. */
    private int setCount;

    /** Points accumulated via the applicable {@code ScoringRule}. Default 0. */
    private int points;

    /** Sets won. Default 0. */
    private int setsWon;

    /** Sets lost. Default 0. */
    private int setsLost;

    /** Balls (individual points) won across all sets. Default 0. */
    private int ballsWon;

    /** Balls (individual points) lost across all sets. Default 0. */
    private int ballsLost;

    /**
     * Set quotient: {@code setsWon / setsLost}.
     * {@link Double#MAX_VALUE} when {@code setsLost == 0} (sentinel for undefeated in sets).
     * Default 0.
     */
    private double setQuotient;

    /**
     * Ball quotient: {@code ballsWon / ballsLost}.
     * {@link Double#MAX_VALUE} when {@code ballsLost == 0} (sentinel for undefeated in balls).
     * Default 0.
     */
    private double ballQuotient;

    /**
     * If {@code true}, this avatar is excluded from normal standings and always ranks last
     * in group tables (D-26). Copied from {@code team.without_assessment} at avatar creation.
     * Default {@code false}.
     */
    private boolean isWithoutAssessment;

    /** Last-updated timestamp — set by the database on insert; updated by cascade service. */
    private LocalDateTime updatedAt;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** Default constructor required by Spring Data JDBC. */
    public TeamAvatarRating() {
    }

    /**
     * Full constructor for programmatic creation.
     *
     * @param avatarId            primary key / FK to {@code team_avatar(id)}
     * @param tenantId            tenant scope (NOT NULL)
     * @param matchCount          matches played (&ge; 0)
     * @param setCount            sets played (&ge; 0)
     * @param points              accumulated points (&ge; 0)
     * @param setsWon             sets won (&ge; 0)
     * @param setsLost            sets lost (&ge; 0)
     * @param ballsWon            balls won (&ge; 0)
     * @param ballsLost           balls lost (&ge; 0)
     * @param setQuotient         setsWon/setsLost ({@code Double.MAX_VALUE} sentinel when no losses)
     * @param ballQuotient        ballsWon/ballsLost ({@code Double.MAX_VALUE} sentinel when no losses)
     * @param isWithoutAssessment if {@code true}, avatar ranks last regardless of scores (D-26)
     * @param updatedAt           last-update timestamp (may be null; DB sets default on insert)
     */
    public TeamAvatarRating(UUID avatarId, UUID tenantId,
                             int matchCount, int setCount, int points,
                             int setsWon, int setsLost,
                             int ballsWon, int ballsLost,
                             double setQuotient, double ballQuotient,
                             boolean isWithoutAssessment,
                             LocalDateTime updatedAt) {
        this.avatarId = avatarId;
        this.tenantId = tenantId;
        this.matchCount = matchCount;
        this.setCount = setCount;
        this.points = points;
        this.setsWon = setsWon;
        this.setsLost = setsLost;
        this.ballsWon = ballsWon;
        this.ballsLost = ballsLost;
        this.setQuotient = setQuotient;
        this.ballQuotient = ballQuotient;
        this.isWithoutAssessment = isWithoutAssessment;
        this.updatedAt = updatedAt;
    }

    // -----------------------------------------------------------------------
    // D-33 Sort order (AC6)
    // -----------------------------------------------------------------------

    /**
     * Implements the D-33 canonical sort order for group table derivation.
     *
     * <p>Natural ordering (highest-ranked first):
     * <ol>
     *   <li>{@code isWithoutAssessment = true} rows always sort last (force to end)</li>
     *   <li>Higher {@code points} ranks first (DESC)</li>
     *   <li>Tie: higher {@code setQuotient} ranks first (DESC)</li>
     *   <li>Tie: higher {@code ballQuotient} ranks first (DESC)</li>
     * </ol>
     *
     * <p>This method returns a negative value if {@code this} should rank ahead of {@code other}.
     * To build a sorted list where the best team is first, sort in natural order (ascending
     * compareTo) — no reverse needed.
     *
     * @param other the other {@link TeamAvatarRating} to compare against
     * @return negative if this ranks ahead, positive if other ranks ahead, 0 if equal
     * @throws NullPointerException if {@code other} is {@code null}
     */
    @Override
    public int compareTo(TeamAvatarRating other) {
        if (other == null) {
            throw new NullPointerException("other must not be null");
        }
        // D-26: is_without_assessment rows are forced last
        if (this.isWithoutAssessment && !other.isWithoutAssessment) {
            return 1;  // this ranks behind
        }
        if (!this.isWithoutAssessment && other.isWithoutAssessment) {
            return -1; // this ranks ahead
        }
        // Both are the same assessment category — apply D-33 sort criteria
        // 1. Points DESC
        int cmp = Integer.compare(other.points, this.points);
        if (cmp != 0) {
            return cmp;
        }
        // 2. Set quotient DESC
        cmp = Double.compare(other.setQuotient, this.setQuotient);
        if (cmp != 0) {
            return cmp;
        }
        // 3. Ball quotient DESC
        return Double.compare(other.ballQuotient, this.ballQuotient);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public UUID getAvatarId() { return avatarId; }
    public void setAvatarId(UUID avatarId) { this.avatarId = avatarId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public int getMatchCount() { return matchCount; }
    public void setMatchCount(int matchCount) { this.matchCount = matchCount; }

    public int getSetCount() { return setCount; }
    public void setSetCount(int setCount) { this.setCount = setCount; }

    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = points; }

    public int getSetsWon() { return setsWon; }
    public void setSetsWon(int setsWon) { this.setsWon = setsWon; }

    public int getSetsLost() { return setsLost; }
    public void setSetsLost(int setsLost) { this.setsLost = setsLost; }

    public int getBallsWon() { return ballsWon; }
    public void setBallsWon(int ballsWon) { this.ballsWon = ballsWon; }

    public int getBallsLost() { return ballsLost; }
    public void setBallsLost(int ballsLost) { this.ballsLost = ballsLost; }

    public double getSetQuotient() { return setQuotient; }
    public void setSetQuotient(double setQuotient) { this.setQuotient = setQuotient; }

    public double getBallQuotient() { return ballQuotient; }
    public void setBallQuotient(double ballQuotient) { this.ballQuotient = ballQuotient; }

    public boolean isWithoutAssessment() { return isWithoutAssessment; }
    public void setWithoutAssessment(boolean withoutAssessment) { isWithoutAssessment = withoutAssessment; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "TeamAvatarRating{avatarId=" + avatarId
                + ", points=" + points
                + ", setsWon=" + setsWon
                + ", setsLost=" + setsLost
                + ", setQuotient=" + setQuotient
                + ", ballQuotient=" + ballQuotient
                + ", isWithoutAssessment=" + isWithoutAssessment + '}';
    }
}
