package de.vvwt.worker.score.legacy;

import java.util.Objects;

/**
 * Legacy reference copy — TEST SCOPE ONLY.
 *
 * <p>Copied verbatim from: {@code
 * vvw-tournaments/vvw-tournaments-services/src/main/java/de/vvwerratal/vvw/tournaments/services/utils/NonVarietyRatingBuilder.java}
 * (legacy project, read-only per DEC-7).
 *
 * <p>This class is placed here exclusively to support the characterization test (E01S02 AC3) that
 * verifies mathematical equivalence between the new {@code VarietyScorer} and the legacy scoring
 * algorithm. It must NEVER be imported from production code.
 *
 * <p>Known issue (documented per E01S02 AC4 / Brief C-7): {@code getRating()} returns {@code int}.
 * At large N or adversarially-chosen sequences the multiplication {@code rating *= phaseCounter}
 * overflows {@code int}. The new scorer fixes this by using {@code double} accumulation.
 *
 * @author vvw (legacy) — copied for characterization-test purposes by E01S02 delivery
 */
public class NonVarietyRatingBuilder {
    private int rating = 1;
    private Boolean lastPhase = null;
    private int phaseCounter = 1;

    public int getRating() {
        return rating * phaseCounter;
    }

    public NonVarietyRatingBuilder register(boolean activePhase) {
        if (Objects.equals(activePhase, lastPhase)) {
            phaseCounter++;
        } else {
            rating *= phaseCounter;
            lastPhase = activePhase;
            phaseCounter = 1;
        }
        return this;
    }
}
