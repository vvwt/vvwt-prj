package de.vvwt.tm.domain;

/**
 * Value class representing the aggregated result of a completed match.
 *
 * <p>Introduced in E03S08 as a plain value class so that {@code ScoringRule} implementations
 * can be written and tested independently. When E03S04 delivers the persistence layer, this class
 * will be augmented with {@code @Table}, {@code @Id}, and additional DB-mapped fields
 * ({@code team1BallsWon}, {@code team2BallsWon}, {@code computedState}, {@code updatedAt}).
 *
 * <p>The three fields present here are the minimal set consumed by all V1 scoring rules:
 * <ul>
 *   <li>{@code team1SetsWon} — sets won by the team in the first slot</li>
 *   <li>{@code team2SetsWon} — sets won by the team in the second slot</li>
 *   <li>{@code setCount} — total number of sets played (equals team1SetsWon + team2SetsWon
 *       for all V1 formats)</li>
 * </ul>
 *
 * <p>Instances are immutable. Use the provided constructor; mutation is reserved for the
 * persistence layer (which will supply setters in E03S04).
 *
 * @see de.vvwt.tm.domain.rules.ScoringRule
 */
public class MatchOutcome {

    private int team1SetsWon;
    private int team2SetsWon;
    private int setCount;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** Default constructor for Spring Data JDBC (E03S04). */
    public MatchOutcome() {
    }

    /**
     * Full constructor for programmatic creation (used by tests and cascade service E03S11).
     *
     * @param team1SetsWon sets won by team 1 (&ge; 0)
     * @param team2SetsWon sets won by team 2 (&ge; 0)
     * @param setCount     total sets played; must equal team1SetsWon + team2SetsWon (&ge; 0)
     */
    public MatchOutcome(int team1SetsWon, int team2SetsWon, int setCount) {
        this.team1SetsWon = team1SetsWon;
        this.team2SetsWon = team2SetsWon;
        this.setCount = setCount;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public int getTeam1SetsWon() { return team1SetsWon; }
    public void setTeam1SetsWon(int team1SetsWon) { this.team1SetsWon = team1SetsWon; }

    public int getTeam2SetsWon() { return team2SetsWon; }
    public void setTeam2SetsWon(int team2SetsWon) { this.team2SetsWon = team2SetsWon; }

    public int getSetCount() { return setCount; }
    public void setSetCount(int setCount) { this.setCount = setCount; }

    @Override
    public String toString() {
        return "MatchOutcome{team1SetsWon=" + team1SetsWon
                + ", team2SetsWon=" + team2SetsWon
                + ", setCount=" + setCount + '}';
    }
}
