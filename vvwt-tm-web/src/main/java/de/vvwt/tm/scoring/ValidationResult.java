package de.vvwt.tm.scoring;

import de.vvwt.tm.tournament.MatchState;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result returned by a {@link SetValidationRule} evaluation.
 *
 * <p>Carries three fields:
 *
 * <ul>
 *   <li>{@code closed} — whether the set may be closed at this point
 *   <li>{@code reason} — human-readable explanation when {@code closed = false} (e.g., "no
 *       2-point lead", "target not reached", "tied"). Empty string when {@code closed = true}.
 *   <li>{@code winnerHint} — {@link MatchState#FINISHED_WINNER1}, {@link
 *       MatchState#FINISHED_WINNER2}, or {@link MatchState#FINISHED_STANDOFF} when {@code closed =
 *       true}; {@link Optional#empty()} otherwise.
 * </ul>
 *
 * <p>Use the static factory methods to construct instances.
 *
 * <p>Reconstructed from {@code de.vvwt.tm.domain.rules.ValidationResult} per DEC-22
 * Reconstruction-in-Place; legacy type remains in place during coexistence window ending at
 * E22S11 cutover.
 */
public final class ValidationResult {

    private final boolean closed;
    private final String reason;
    private final Optional<MatchState> winnerHint;

    // -----------------------------------------------------------------------
    // Constructor (private — use factory methods)
    // -----------------------------------------------------------------------

    private ValidationResult(boolean closed, String reason, Optional<MatchState> winnerHint) {
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        if (winnerHint == null) {
            throw new IllegalArgumentException("winnerHint must not be null");
        }
        this.closed = closed;
        this.reason = reason;
        this.winnerHint = winnerHint;
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Set is closed; team 1 won.
     *
     * @return a closed result with {@code winnerHint = FINISHED_WINNER1}
     */
    public static ValidationResult winner1() {
        return new ValidationResult(true, "", Optional.of(MatchState.FINISHED_WINNER1));
    }

    /**
     * Set is closed; team 2 won.
     *
     * @return a closed result with {@code winnerHint = FINISHED_WINNER2}
     */
    public static ValidationResult winner2() {
        return new ValidationResult(true, "", Optional.of(MatchState.FINISHED_WINNER2));
    }

    /**
     * Set cannot be closed yet; explains why.
     *
     * @param reason human-readable rejection explanation (must not be null or blank)
     * @return an open result with empty {@code winnerHint}
     * @throws IllegalArgumentException if {@code reason} is null or blank
     */
    public static ValidationResult open(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "reason for open result must not be null or blank");
        }
        return new ValidationResult(false, reason, Optional.empty());
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the set can be closed at the given point scores.
     *
     * @return {@code true} if closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Returns the human-readable explanation for an open result. Empty string when {@code closed =
     * true}.
     *
     * @return rejection reason or empty string
     */
    public String getReason() {
        return reason;
    }

    /**
     * Returns the winner hint when {@code closed = true}. {@link Optional#empty()} when {@code
     * closed = false}.
     *
     * @return winner hint or empty
     */
    public Optional<MatchState> getWinnerHint() {
        return winnerHint;
    }

    // -----------------------------------------------------------------------
    // Object overrides
    // -----------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ValidationResult other)) {
            return false;
        }
        return closed == other.closed
                && Objects.equals(reason, other.reason)
                && Objects.equals(winnerHint, other.winnerHint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(closed, reason, winnerHint);
    }

    @Override
    public String toString() {
        return "ValidationResult{closed="
                + closed
                + ", reason='"
                + reason
                + "'"
                + ", winnerHint="
                + winnerHint
                + "}";
    }
}
