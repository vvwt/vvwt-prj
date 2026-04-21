package de.vvwt.tm.tournament;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * TeamAvatarRating entity — reconstruction-in-place target (DEC-21/DEC-22).
 *
 * <p>Maps to the {@code team_avatar_rating} table (V5 migration). Lives at {@code
 * de.vvwt.tm.tournament.TeamAvatarRating} — the Modulith public API package per DEC-21 §Module
 * layout. Does NOT import the legacy {@code de.vvwt.tm.domain.TeamAvatarRating}.
 *
 * <h2>PK field — avatarId (not id)</h2>
 *
 * <p>The primary key for this table is {@code avatar_id}, not a separate {@code id} column. This is
 * a 1:1 relationship with {@link TeamAvatar}: one rating row per avatar slot. The {@code @Id}
 * annotation is placed on {@code avatarId}.
 *
 * <h2>Quotient sentinel values</h2>
 *
 * <p>When {@code setsLost == 0}, {@code setQuotient} stores {@code Double.MAX_VALUE} as a sentinel.
 * When {@code ballsLost == 0}, {@code ballQuotient} stores {@code Double.MAX_VALUE}. This ensures
 * undefeated teams sort correctly in group tables.
 *
 * <p>Inventory line 457 ({@code TeamAvatarRating}).
 *
 * @see TeamAvatar
 * @see TeamAvatarRatingRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith adoption</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 457)</a>
 */
public class TeamAvatarRating implements Comparable<TeamAvatarRating> {

    /** PK: same as the referenced TeamAvatar's UUID — 1:1 relationship. */
    private UUID avatarId;

    private UUID tenantId;

    private int matchCount;

    private int setCount;

    private int points;

    private int setsWon;

    private int setsLost;

    private int ballsWon;

    private int ballsLost;

    /**
     * Ratio of sets won to sets lost. Stores {@code Double.MAX_VALUE} when {@code setsLost == 0}
     * (sentinel for undefeated).
     */
    private double setQuotient;

    /**
     * Ratio of balls won to balls lost. Stores {@code Double.MAX_VALUE} when {@code ballsLost == 0}
     * (sentinel for perfect ball differential).
     */
    private double ballQuotient;

    /**
     * Copied from {@code team.without_assessment} at avatar creation time (D-26). If {@code true},
     * this avatar sorts last in group table regardless of other scores.
     */
    private boolean withoutAssessment;

    private LocalDateTime updatedAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Default no-arg constructor for Spring Data / JdbcTemplate row mapping. */
    public TeamAvatarRating() {}

    /**
     * Full constructor for explicit creation in service / repository code.
     *
     * @param avatarId PK — same as the referenced TeamAvatar UUID
     * @param tenantId owning tenant
     * @param matchCount number of matches played
     * @param setCount total sets played
     * @param points score points accumulated
     * @param setsWon sets won
     * @param setsLost sets lost
     * @param ballsWon balls (points within sets) won
     * @param ballsLost balls (points within sets) lost
     * @param setQuotient setsWon/setsLost ratio (Double.MAX_VALUE sentinel when setsLost=0)
     * @param ballQuotient ballsWon/ballsLost ratio (Double.MAX_VALUE sentinel when ballsLost=0)
     * @param withoutAssessment if true, sorts last in standings
     * @param updatedAt last update timestamp
     */
    public TeamAvatarRating(
            UUID avatarId,
            UUID tenantId,
            int matchCount,
            int setCount,
            int points,
            int setsWon,
            int setsLost,
            int ballsWon,
            int ballsLost,
            double setQuotient,
            double ballQuotient,
            boolean withoutAssessment,
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
        this.withoutAssessment = withoutAssessment;
        this.updatedAt = updatedAt;
    }

    // -------------------------------------------------------------------------
    // Getters and setters
    // -------------------------------------------------------------------------

    public UUID getAvatarId() {
        return avatarId;
    }

    public void setAvatarId(UUID avatarId) {
        this.avatarId = avatarId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public int getMatchCount() {
        return matchCount;
    }

    public void setMatchCount(int matchCount) {
        this.matchCount = matchCount;
    }

    public int getSetCount() {
        return setCount;
    }

    public void setSetCount(int setCount) {
        this.setCount = setCount;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }

    public int getSetsWon() {
        return setsWon;
    }

    public void setSetsWon(int setsWon) {
        this.setsWon = setsWon;
    }

    public int getSetsLost() {
        return setsLost;
    }

    public void setSetsLost(int setsLost) {
        this.setsLost = setsLost;
    }

    public int getBallsWon() {
        return ballsWon;
    }

    public void setBallsWon(int ballsWon) {
        this.ballsWon = ballsWon;
    }

    public int getBallsLost() {
        return ballsLost;
    }

    public void setBallsLost(int ballsLost) {
        this.ballsLost = ballsLost;
    }

    public double getSetQuotient() {
        return setQuotient;
    }

    public void setSetQuotient(double setQuotient) {
        this.setQuotient = setQuotient;
    }

    public double getBallQuotient() {
        return ballQuotient;
    }

    public void setBallQuotient(double ballQuotient) {
        this.ballQuotient = ballQuotient;
    }

    public boolean isWithoutAssessment() {
        return withoutAssessment;
    }

    public void setWithoutAssessment(boolean withoutAssessment) {
        this.withoutAssessment = withoutAssessment;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    // -------------------------------------------------------------------------
    // Comparable — D-33 canonical group-table sort order
    // -------------------------------------------------------------------------

    /**
     * Canonical sort order for group standings (D-33):
     *
     * <ol>
     *   <li>Higher {@code points} ranks first (DESC)
     *   <li>Tie broken by higher {@code setQuotient} (DESC)
     *   <li>Tie broken by higher {@code ballQuotient} (DESC)
     *   <li>Rows with {@code isWithoutAssessment = true} always rank last (D-26)
     * </ol>
     *
     * <p>Added in E21S13 cutover — mirrors the legacy {@code domain.TeamAvatarRating#compareTo}
     * behaviour (pure behaviour preservation, no semantic change per DEC-22 refactor phase).
     *
     * @param other the other {@link TeamAvatarRating} to compare against
     * @return negative if this ranks ahead, positive if other ranks ahead, 0 if equal
     */
    @Override
    public int compareTo(TeamAvatarRating other) {
        if (other == null) {
            throw new NullPointerException("other must not be null");
        }
        // D-26: is_without_assessment rows are forced last
        if (this.withoutAssessment && !other.withoutAssessment) {
            return 1; // this ranks behind
        }
        if (!this.withoutAssessment && other.withoutAssessment) {
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
}
