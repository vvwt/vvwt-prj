package de.vvwt.tm.slotopt;

import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.repo.MatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Fallback (sequential) implementation of {@link SlotOptimizationClient} (E03S12, AC3, AC8).
 *
 * <p>Provides a sequential lap/field assignment: matches are sorted deterministically by UUID,
 * then assigned indices {@code (idx / fieldCount, idx % fieldCount)} where {@code fieldCount}
 * is configurable via {@code tm.slotopt.fallback.field-count} (default: 3).
 *
 * <p>This is not an optimal schedule — it makes no attempt to minimize team idle time.
 * It is used as the fallback by {@link DirectSlotOptimizationClient} when the timeout-based
 * search produces no results, or when the thread pool fails to submit tasks (E04S04, AC5, AC11).
 *
 * <h2>Bean priority (E04S04 amendment)</h2>
 * <p>Previously this bean used {@code @ConditionalOnMissingBean(SlotOptimizationClient.class)}
 * to auto-disable when E04 was delivered. E04S04 requires the fallback to be always present as
 * a named bean (qualifier: {@code "fallbackSlotOptimizer"}) so that
 * {@link DirectSlotOptimizationClient} can inject it for the timeout fallback path.
 * The previous conditional has been removed; {@link DirectSlotOptimizationClient} is now
 * {@code @Primary} and is injected by default when {@link SlotOptimizationClient} is
 * requested without a qualifier.
 *
 * @see SlotOptimizationClient
 * @see DirectSlotOptimizationClient
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S12.story.md">Story E03S12</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E04S04.story.md">Story E04S04</a>
 */
@Component("fallbackSlotOptimizer")
public class FallbackSlotOptimizationClient implements SlotOptimizationClient {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackSlotOptimizationClient.class);

    private final MatchRepository matchRepository;
    private final int fieldCount;

    /**
     * Constructs the fallback optimizer.
     *
     * @param matchRepository   tenant-scoped match repository (required)
     * @param fieldCount        number of courts/fields per lap; injected from
     *                          {@code tm.slotopt.fallback.field-count} (default: 3)
     */
    public FallbackSlotOptimizationClient(
            MatchRepository matchRepository,
            @Value("${tm.slotopt.fallback.field-count:3}") int fieldCount) {
        if (matchRepository == null) {
            throw new IllegalArgumentException("matchRepository must not be null");
        }
        if (fieldCount <= 0) {
            throw new IllegalArgumentException(
                    "fieldCount must be > 0 (got " + fieldCount + ")");
        }
        this.matchRepository = matchRepository;
        this.fieldCount = fieldCount;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Assigns {@code (lap, field)} = {@code (idx / fieldCount, idx % fieldCount)} to each
     * match. Matches are sorted by UUID for a deterministic assignment order.
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
                    "FallbackSlotOptimizationClient: no matches found for phase " + phaseId
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
        LOG.info("FallbackSlotOptimizationClient: phase={}, {} matches optimized into {} laps × {} fields",
                phaseId, matches.size(), lapCount, fieldCount);
    }
}
