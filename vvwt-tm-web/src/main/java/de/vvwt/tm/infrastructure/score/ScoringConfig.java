package de.vvwt.tm.infrastructure.score;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the scoring tablet module (E06S07).
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li>{@code tm.scoring.tiebreak-swap-threshold} — point score at which teams swap sides during
 *       the deciding (tie-break) set (AC5). Default: 8 (standard volleyball convention for a
 *       15-point deciding set). Override via {@code -Dtm.scoring.tiebreak-swap-threshold} or in
 *       an environment-specific {@code application-*.yml}.</li>
 * </ul>
 *
 * @see ScoreEntryService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S07.story.md">Story E06S07</a>
 */
@Component
@ConfigurationProperties(prefix = "tm.scoring")
public class ScoringConfig {

    /**
     * AC5: Score threshold at which teams automatically swap sides in the deciding set.
     * Default is 8 (standard volleyball for a 15-point tie-break set).
     */
    private int tiebreakSwapThreshold = 8;

    public int getTiebreakSwapThreshold() {
        return tiebreakSwapThreshold;
    }

    public void setTiebreakSwapThreshold(int tiebreakSwapThreshold) {
        this.tiebreakSwapThreshold = tiebreakSwapThreshold;
    }
}
