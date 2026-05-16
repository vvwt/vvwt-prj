// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

import de.vvwt.tm.slotopt.internal.LapPermutationOptimizer;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * In-process exhaustive slot-optimization client (E04S03).
 *
 * <p>Implements {@link SlotOptimizationClient} using an exhaustive lap-permutation search.
 * Delegates to {@link LapPermutationOptimizer} for the correct DEC-61-B + DEC-63-C + E54S12
 * algorithm (E54S13 extract-and-delegate refactor).
 *
 * <h2>Why delegation (E54S13)</h2>
 *
 * <p>Prior to E54S13, this class had an inline brute-force loop with 4 correctness bugs:
 *
 * <ol>
 *   <li>{@code lapCount = rowCount / fieldCount} — stale pre-DEC-61 formula (bug-class 1)
 *   <li>Hardcoded {@code new VarietyScorer()} — ignores DEC-63 Clause C scorer config (bug-class 2)
 *   <li>{@code activeMatrix} from {@code canonical.rows()} — index-space mismatch (bug-class 3)
 *   <li>{@code rowSeq[i] = pi[i/fc]*fc + i%fc} — stale expansion (bug-class 4)
 * </ol>
 *
 * <p>Bug-classes 1 + 4 mutually compensate for SYMMETRIC inputs only. E54S13 eliminates all 4 bugs
 * structurally by replacing the inline loop with delegation to {@link LapPermutationOptimizer}.
 *
 * <h2>N = lapCount (DEC-49 D-3, DEC-61 Clause B)</h2>
 *
 * <p>After E54S02 Mapper refactor, {@code canonical.rowCount() = lapCount}. The threshold check and
 * the {@link LapPermutationOptimizer#optimize} call both use the correct post-DEC-61 lapCount.
 *
 * <h2>Client-side routing concerns (retained per AC-ERROR-LAPCOUNT-EXCEEDS-THRESHOLD-PRESERVED)
 * </h2>
 *
 * <p>{@code lapCount > exhaustiveMaxN}: throws {@link UnsupportedOperationException} — routing
 * concern for this direct path; not in LapPermutationOptimizer which has no threshold awareness.
 *
 * <h2>Scorer config (DEC-63 Clause C)</h2>
 *
 * <p>Injects {@code @Value("${tm.slotopt.scorer:mean}")} and threads it through to {@link
 * LapPermutationOptimizer#optimize} (non-Spring utility receives scorerConfig from caller).
 *
 * <h2>Bean wiring</h2>
 *
 * <p>This is a {@link Service} bean of type {@link SlotOptimizationClient}. As of E27S01, {@link
 * de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient} is the {@code @Primary} bean. This
 * class is retained for direct invocations (e.g., integration tests, legacy paths). Deletion
 * requires an audit beyond this Story's scope (E54S13 Out-of-Scope).
 *
 * <h2>Tenant scoping</h2>
 *
 * <p>All repository calls delegate to tenant-scoped repositories (DEC-5, E03S05). TenantContext
 * must be active before calling {@link #optimize(UUID)}.
 *
 * @see SlotOptimizationClient
 * @see LapPermutationOptimizer
 * @see de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient
 * @see SlotResultApplicator
 * @see <a href="../../../../../../../../docs/governance/stories/E04S03.story.md">Story E04S03</a>
 * @see <a href="E54S13">E54S13 — extract-and-delegate refactor</a>
 */
@Service
public class DirectSlotOptimizationClient implements SlotOptimizationClient {

    private static final Logger LOG = LoggerFactory.getLogger(DirectSlotOptimizationClient.class);

    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final PhaseToRawPhaseDefMapper mapper;
    private final SlotResultApplicator applicator;
    private final int exhaustiveMaxN;
    private final String scorerConfig;

    /**
     * Constructs the client.
     *
     * @param phaseRepository tenant-scoped repository for Phase entities
     * @param matchRepository tenant-scoped repository for Match entities
     * @param mapper forward mapper for Phase → RawPhaseDef
     * @param applicator result applicator for applying the best rank to match entities
     * @param exhaustiveMaxN maximum lapCount for exhaustive search; configured via {@code
     *     tm.slotopt.exhaustive-max-n}
     * @param scorerConfig scorer selection; sourced from {@code tm.slotopt.scorer} (default {@code
     *     "mean"}); valid values: {@code "mean"} (VarietyScorer) or {@code "balanced"}
     *     (BalancedVarietyScorer); invalid values fall back to {@code "mean"} with WARN log (DEC-63
     *     Clause C)
     */
    public DirectSlotOptimizationClient(
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            PhaseToRawPhaseDefMapper mapper,
            SlotResultApplicator applicator,
            @Value("${tm.slotopt.exhaustive-max-n:10}") int exhaustiveMaxN,
            @Value("${tm.slotopt.scorer:mean}") String scorerConfig) {
        if (phaseRepository == null) {
            throw new IllegalArgumentException("phaseRepository must not be null");
        }
        if (matchRepository == null) {
            throw new IllegalArgumentException("matchRepository must not be null");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        if (applicator == null) {
            throw new IllegalArgumentException("applicator must not be null");
        }
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.mapper = mapper;
        this.applicator = applicator;
        this.exhaustiveMaxN = exhaustiveMaxN;
        this.scorerConfig = scorerConfig;
    }

    /**
     * {@inheritDoc}
     *
     * <p>For lapCount &le; {@code tm.slotopt.exhaustive-max-n} (default: 10): delegates to {@link
     * LapPermutationOptimizer#optimize} for the correct DEC-61-B + DEC-63-C + E54S12 algorithm.
     *
     * <p>For lapCount &gt; {@code exhaustiveMaxN}: throws {@link UnsupportedOperationException}
     * (routing concern — stays in client per AC-ERROR-LAPCOUNT-EXCEEDS-THRESHOLD-PRESERVED).
     *
     * @param phaseId the phase whose matches should receive slot assignments; must not be null
     * @throws IllegalArgumentException if {@code phaseId} is null or the phase does not exist
     * @throws IllegalStateException if no matches exist for the phase
     * @throws UnsupportedOperationException if lapCount &gt; {@code exhaustiveMaxN}
     */
    @Override
    public void optimize(UUID phaseId) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }

        // Verify phase exists (AC-ERROR-DELEGATION-PRESERVES-EXCEPTIONS)
        phaseRepository
                .findById(phaseId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "DirectSlotOptimizationClient: phase not found: "
                                                + phaseId));

        // Verify matches exist early for a clearer error
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "DirectSlotOptimizationClient: no matches found for phase "
                            + phaseId
                            + ". Cannot optimize empty phase.");
        }

        // Forward-map to RawPhaseDef + canonical form
        MappingResult mapping = mapper.map(phaseId);
        int fieldCount = mapper.getFieldCount();

        // DEC-61 Clause B: lapCount = canonical.rowCount() (post-E54S02 Mapper refactor).
        // NOT rowCount / fieldCount (stale pre-DEC-61 formula — bug-class 1 eliminated).
        int lapCount = mapping.canonical().rowCount();

        // lapCount > exhaustiveMaxN — routing concern
        // (AC-ERROR-LAPCOUNT-EXCEEDS-THRESHOLD-PRESERVED).
        // LapPermutationOptimizer has no threshold awareness; this guard stays in the client.
        if (lapCount > exhaustiveMaxN) {
            throw new UnsupportedOperationException(
                    "Exhaustive optimization not feasible for lapCount="
                            + lapCount
                            + " (> "
                            + exhaustiveMaxN
                            + "). Route to Leg 2/3.");
        }

        LOG.info(
                "DirectSlotOptimizationClient: phase={}, lapCount={}, fieldCount={} → delegating"
                        + " to LapPermutationOptimizer (E54S13)",
                phaseId,
                lapCount,
                fieldCount);

        // Delegate to LapPermutationOptimizer — correct DEC-61-B + DEC-63-C + E54S12 algorithm.
        // All 4 bug-classes eliminated by delegation (E54S13).
        LapPermutationOptimizer.optimize(
                phaseId, mapping, fieldCount, scorerConfig, applicator, null, Optional.empty());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Computes N! for N in [0, 20]. All values fit in {@code long}.
     *
     * @param n the value whose factorial to compute
     * @return n!
     * @throws IllegalArgumentException if n &lt; 0
     */
    static long factorial(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0, got: " + n);
        }
        long result = 1L;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
}
