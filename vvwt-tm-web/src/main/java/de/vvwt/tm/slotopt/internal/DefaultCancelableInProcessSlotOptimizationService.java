// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.CancelableInProcessSlotOptimizationService;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.OptimizationResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.slotopt.SlotResultApplicator;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Leg 3 implementation: cancelable in-process slot optimization using cooperative cancellation via
 * a {@link CancellationToken} checked between lap permutations (E27S02, DEC-49 D-3).
 *
 * <p>Delegates to {@link LapPermutationOptimizer} for the correct DEC-61-B + DEC-63-C + E54S12
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
 * <p>Bug-classes 1 + 4 mutually compensate for SYMMETRIC inputs but cause {@link
 * ArrayIndexOutOfBoundsException} for asymmetric inputs (e.g., 12T/2G/3F where stale
 * lapCount=10/3=3, then pi[3] is accessed out of bounds). E54S13 eliminates all 4 bugs structurally
 * via delegation to {@link LapPermutationOptimizer}.
 *
 * <h2>Client-side pre-loop cancellation check (retained per
 * AC-ERROR-CANCELABLE-EARLY-CANCEL-PRESERVED)</h2>
 *
 * <p>If the token is pre-cancelled BEFORE optimize() begins the main loop, this client handles it
 * directly (apply rank=0 L2 baseline + return cancelled). This is a client-side concern that does
 * NOT belong in {@link LapPermutationOptimizer} (which receives control after the pre-check). Per
 * AC-TEST-CANCELABLE-BEST-SO-FAR-ON-L2-DEFAULT-RED (E51S11).
 *
 * <h2>Scorer config (DEC-63 Clause C)</h2>
 *
 * <p>Injects {@code @Value("${tm.slotopt.scorer:mean}")} and threads it through to {@link
 * LapPermutationOptimizer#optimize} (non-Spring utility receives scorerConfig from caller).
 *
 * <h2>Per DEC-35</h2>
 *
 * <p>Implementation in {@code de.vvwt.tm.slotopt.internal}; public interface {@link
 * CancelableInProcessSlotOptimizationService} in module root.
 *
 * @see CancelableInProcessSlotOptimizationService
 * @see LapPermutationOptimizer
 * @see SlotOptimizationJobRegistry
 * @see JobHandle
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-3,
 *     D-11a</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 * @see <a href="E54S13">E54S13 — extract-and-delegate refactor</a>
 */
@Service
public class DefaultCancelableInProcessSlotOptimizationService
        implements CancelableInProcessSlotOptimizationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultCancelableInProcessSlotOptimizationService.class);

    private final PhaseToRawPhaseDefMapper mapper;
    private final SlotResultApplicator applicator;
    private final SlotOptimizationJobRegistry registry;
    private final String scorerConfig;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param mapper the phase-to-raw-phase-def mapper
     * @param applicator the result applicator (applies lap-permutation rank to match entities)
     * @param registry the job registry for best-so-far tracking
     * @param scorerConfig scorer selection; sourced from {@code tm.slotopt.scorer} (default {@code
     *     "mean"}); valid values: {@code "mean"} (VarietyScorer) or {@code "balanced"}
     *     (BalancedVarietyScorer); invalid values fall back to {@code "mean"} with WARN log (DEC-63
     *     Clause C)
     */
    @Autowired
    public DefaultCancelableInProcessSlotOptimizationService(
            PhaseToRawPhaseDefMapper mapper,
            SlotResultApplicator applicator,
            SlotOptimizationJobRegistry registry,
            @Value("${tm.slotopt.scorer:mean}") String scorerConfig) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        if (applicator == null) {
            throw new IllegalArgumentException("applicator must not be null");
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.mapper = mapper;
        this.applicator = applicator;
        this.registry = registry;
        this.scorerConfig = scorerConfig;
    }

    /** {@inheritDoc} */
    @Override
    public OptimizationResult optimize(UUID phaseId, UUID tournamentId, CancellationToken token) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        if (token == null) {
            throw new IllegalArgumentException("token must not be null");
        }

        MappingResult mapping = mapper.map(phaseId);
        int fieldCount = mapper.getFieldCount();
        Optional<JobHandle> handleOpt = registry.getHandle(tournamentId);

        // AC-ERROR-CANCELABLE-EARLY-CANCEL-PRESERVED: pre-loop cancellation check stays in client.
        // If the token is pre-cancelled BEFORE any computation begins, apply rank=0 (L2 baseline)
        // and return immediately. This is a client-side routing concern; LapPermutationOptimizer
        // begins iteration after this check passes.
        // AC-TEST-CANCELABLE-BEST-SO-FAR-ON-L2-DEFAULT-RED (E51S11).
        if (token.isCancelled()) {
            LOG.info(
                    "CancelableInProcessSlotOptimizationService: phase={} cancelled before"
                            + " computation; applying rank=0 (L2 baseline)",
                    phaseId);
            applicator.applyResult(0L, fieldCount, mapping);
            return OptimizationResult.cancelled(0L, Double.MAX_VALUE);
        }

        // Delegate to LapPermutationOptimizer — correct DEC-61-B + DEC-63-C + E54S12 algorithm.
        // All 4 bug-classes eliminated by delegation (E54S13).
        // The optimizer handles: cooperative cancellation (token), best-so-far (handleOpt),
        // lapCount derivation (canonical.rowCount()), activeMatrix (denseIdsByRawRow),
        // scorer selection (ScorerFactory.createScorerUnified(scorerConfig)).
        return LapPermutationOptimizer.optimize(
                phaseId, mapping, fieldCount, scorerConfig, applicator, token, handleOpt);
    }
}
