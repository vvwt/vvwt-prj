package de.vvwt.tm.slotopt;

import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.repo.MatchRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Development-time fallback implementation of {@link SlotOptimizationClient} (AC3, AC8).
 *
 * <p>When E04 is not yet delivered, this bean provides a sequential lap/field assignment: matches
 * are sorted deterministically by ID, then assigned indices {@code (idx / fieldCount, idx %
 * fieldCount)} where {@code fieldCount} is configurable via {@code tm.slotopt.fallback.field-count}
 * (default: 3).
 *
 * <p>This is not an optimal schedule — it makes no attempt to avoid pairs playing on adjacent
 * courts or minimize wait time. It is sufficient to unblock E03 development and allow integration
 * tests to exercise the preparation flow.
 *
 * <h2>Auto-disabling on E04 delivery</h2>
 *
 * <p>This bean is annotated {@code @ConditionalOnMissingBean(SlotOptimizationClient.class)}. When
 * E04 provides a real {@link SlotOptimizationClient} implementation, Spring will skip this fallback
 * and use the real one instead. No code changes required on E03's side.
 *
 * @see SlotOptimizationClient
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S12.story.md">Story
 *     E03S12</a>
 */
@Component("fallbackSlotOptimizer")
@ConditionalOnMissingBean(
        value = SlotOptimizationClient.class,
        ignored = FallbackSlotOptimizationClient.class)
public class FallbackSlotOptimizationClient implements SlotOptimizationClient {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackSlotOptimizationClient.class);

    private final MatchRepository matchRepository;
    private final int fieldCount;

    /**
     * Constructs the fallback optimizer.
     *
     * @param matchRepository tenant-scoped match repository (required)
     * @param fieldCount number of courts/fields per lap; injected from {@code
     *     tm.slotopt.fallback.field-count} (default: 3)
     */
    public FallbackSlotOptimizationClient(
            MatchRepository matchRepository,
            @Value("${tm.slotopt.fallback.field-count:3}") int fieldCount) {
        if (matchRepository == null) {
            throw new IllegalArgumentException("matchRepository must not be null");
        }
        if (fieldCount <= 0) {
            throw new IllegalArgumentException("fieldCount must be > 0 (got " + fieldCount + ")");
        }
        this.matchRepository = matchRepository;
        this.fieldCount = fieldCount;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Assigns {@code (lap, field)} = {@code (idx / fieldCount, idx % fieldCount)} to each match.
     * Matches are sorted by UUID for a deterministic assignment order.
     *
     * @param phaseId the phase whose matches receive sequential slot coordinates
     */
    @Override
    public void optimize(UUID phaseId) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "FallbackSlotOptimizationClient: no matches found for phase "
                            + phaseId
                            + ". Cannot optimize empty phase.");
        }

        // Sort deterministically for reproducible slot assignments
        matches.sort(Comparator.comparing(Match::getId));

        for (int idx = 0; idx < matches.size(); idx++) {
            Match match = matches.get(idx);
            match.setLapNumber(idx / fieldCount);
            match.setFieldNumber(idx % fieldCount);
            matchRepository.save(match);
        }

        int lapCount = (matches.size() + fieldCount - 1) / fieldCount;
        LOG.info(
                "FallbackSlotOptimizationClient: phase={}, {} matches optimized into {} laps × {}"
                        + " fields",
                phaseId,
                matches.size(),
                lapCount,
                fieldCount);
    }
}
