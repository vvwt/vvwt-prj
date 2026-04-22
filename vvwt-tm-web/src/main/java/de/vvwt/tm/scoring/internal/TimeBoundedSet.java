package de.vvwt.tm.scoring.internal;

import de.vvwt.tm.scoring.SetValidationRule;
import de.vvwt.tm.scoring.ValidationResult;
import de.vvwt.tm.tournament.MatchFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Time-bounded set-validation rule (AC4, H-8).
 *
 * <p>Beta tournament variant: a set can be closed at any point score as long as the winning team
 * leads by at least 1 point (no tie at set end). Sets are ended by a referee whistle based on a
 * fixed time duration rather than a point target.
 *
 * <p>The {@code setIndex} and {@code format} parameters are accepted but ignored — time-based sets
 * are format-independent. {@code format} must still not be {@code null} (interface contract
 * compliance; AC11).
 *
 * <p>The Spring bean name {@code "timeBounded"} is the identifier stored in {@code
 * Tournament.set_validation_rule_id} and used for registry lookup. This value is persisted via
 * Flyway V2__e03_core_schema.sql and must not be changed (AC-BEAN-NAME-PRESERVED / E22S04).
 *
 * <p>This class is stateless and thread-safe.
 *
 * <p>Reconstructed from {@code de.vvwt.tm.domain.rules.TimeBoundedSet} per DEC-22
 * Reconstruction-in-Place; legacy type remains in place during coexistence window ending at E22S11
 * cutover. Dual-bean-boot conflict resolved by the {@code @ComponentScan} on {@link
 * de.vvwt.tm.TournamentManagerApplication} exclusion (AC-COMPONENT-SCAN-EXCLUSION / E22S04).
 *
 * @see SetValidationRule
 * @see ValidationResult
 */
@Component("timeBounded")
class TimeBoundedSet implements SetValidationRule {

    private static final Logger log = LoggerFactory.getLogger(TimeBoundedSet.class);

    private static final String BEAN_ID = "timeBounded";

    /**
     * {@inheritDoc}
     *
     * <p>Logic: the set is closed if {@code team1Pts != team2Pts} (at least 1-point lead). The
     * leader wins. If scores are equal the set cannot be closed.
     *
     * @throws IllegalArgumentException if {@code format} is {@code null} or if either point count
     *     is negative
     */
    @Override
    public ValidationResult isSetClosed(
            int team1Pts, int team2Pts, int setIndex, MatchFormat format) {
        validateInputs(team1Pts, team2Pts, format);

        ValidationResult result;

        if (team1Pts > team2Pts) {
            result = ValidationResult.winner1();
        } else if (team2Pts > team1Pts) {
            result = ValidationResult.winner2();
        } else {
            result = ValidationResult.open("tied");
        }

        log.debug(
                "{} decision for ({},{}) set={} format={}: closed={} reason='{}'",
                BEAN_ID,
                team1Pts,
                team2Pts,
                setIndex,
                format,
                result.isClosed(),
                result.getReason());

        return result;
    }

    /** {@inheritDoc} */
    @Override
    public String getBeanId() {
        return BEAN_ID;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static void validateInputs(int team1Pts, int team2Pts, MatchFormat format) {
        if (format == null) {
            throw new IllegalArgumentException("format must not be null");
        }
        if (team1Pts < 0) {
            throw new IllegalArgumentException("team1Pts must be >= 0, got: " + team1Pts);
        }
        if (team2Pts < 0) {
            throw new IllegalArgumentException("team2Pts must be >= 0, got: " + team2Pts);
        }
    }
}
