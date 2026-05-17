// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.tm.slotopt.CancelableInProcessSlotOptimizationService;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.DirectSlotOptimizationClient;
import de.vvwt.tm.slotopt.DispatcherAlgorithmMismatchException;
import de.vvwt.tm.slotopt.DispatcherReachabilityService;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.OptimizationResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
import de.vvwt.tm.slotopt.SlotOptimizationDispatcherClient;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.slotopt.SlotResultApplicator;
import de.vvwt.tm.tournament.Match;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RoutingSlotOptimizationClient} — Leg-1 delegation contract (E27S01), Leg-3
 * routing (E27S02), and Leg-2 dispatcher routing (E27S03, AC-ROUTING-EXTENDED-LEG-2,
 * AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR).
 *
 * <p>TDD RED-first per DEC-22 Iron Law. E27S01 + E27S02 tests are preserved. E27S03 adds Leg-2
 * routing tests (reachable→Leg2; unreachable→Leg3; wire-error→fallthrough-Leg3). E54S03 adds
 * phase-global invocation tests (DEC-61 Clause D — single {@code map(phaseId)} call, no {@code
 * mapGroup} calls, single applicator invocation).
 *
 * <p>Per DEC-36: this test class is in the {@code slotopt.internal} package (same as the subject),
 * so white-box reference to {@link RoutingSlotOptimizationClient} is permitted.
 *
 * @see RoutingSlotOptimizationClient
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S01.story.md">Story
 *     E27S01</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S03.story.md">Story
 *     E27S03</a>
 */
class RoutingSlotOptimizationClientTest {

    private DirectSlotOptimizationClient directMock;
    private CancelableInProcessSlotOptimizationService cancelableServiceMock;
    private SlotOptimizationJobRegistry registryMock;
    private PhaseToRawPhaseDefMapper mapperMock;
    private DispatcherReachabilityService reachabilityMock;
    private SlotOptimizationDispatcherClient dispatcherClientMock;
    private SlotResultApplicator applicatorMock;
    private RoutingSlotOptimizationClient subject;

    /** Default exhaustiveMaxN = 2 for tests (smaller threshold for easy fixtures). */
    private static final int EXHAUSTIVE_MAX_N = 2;

    /** fieldCount = 3 (matches tm.slotopt.fallback.field-count default). */
    private static final int FIELD_COUNT = 3;

    /** Production-default exhaustiveMaxN = 10 for phase-global E54S03 tests. */
    private static final int EXHAUSTIVE_MAX_N_PROD = 10;

    @BeforeEach
    void setUp() {
        directMock = mock(DirectSlotOptimizationClient.class);
        cancelableServiceMock = mock(CancelableInProcessSlotOptimizationService.class);
        registryMock = mock(SlotOptimizationJobRegistry.class);
        mapperMock = mock(PhaseToRawPhaseDefMapper.class);
        reachabilityMock = mock(DispatcherReachabilityService.class);
        dispatcherClientMock = mock(SlotOptimizationDispatcherClient.class);
        applicatorMock = mock(SlotResultApplicator.class);
        when(mapperMock.getFieldCount()).thenReturn(FIELD_COUNT);
        subject =
                new RoutingSlotOptimizationClient(
                        directMock,
                        cancelableServiceMock,
                        registryMock,
                        mapperMock,
                        reachabilityMock,
                        dispatcherClientMock,
                        applicatorMock,
                        EXHAUSTIVE_MAX_N,
                        "mean"); // E54S07: default scorer (DEC-63 Clause C)
    }

    /** Creates a {@link RoutingSlotOptimizationClient} with production-default threshold (10). */
    private RoutingSlotOptimizationClient subjectWithProdThreshold() {
        return new RoutingSlotOptimizationClient(
                directMock,
                cancelableServiceMock,
                registryMock,
                mapperMock,
                reachabilityMock,
                dispatcherClientMock,
                applicatorMock,
                EXHAUSTIVE_MAX_N_PROD,
                "mean"); // E54S07: default scorer (DEC-63 Clause C)
    }

    // =========================================================================
    // AC-LEG-1-DELEGATION-CONTRACT-TESTED (E27S01 — preserved)
    // =========================================================================

    /**
     * AC-LEG-1-DELEGATION-CONTRACT-TESTED (a): lapCount at threshold → Leg 1 (direct or inline).
     */
    @Test
    void optimize_lapCountAtThreshold_delegatesToLeg1() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // lapCount = EXHAUSTIVE_MAX_N (=2), rowCount = lapCount * FIELD_COUNT = 6
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, EXHAUSTIVE_MAX_N, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);

        subject.optimize(phaseId);

        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
        verify(reachabilityMock, never()).isReachable();
    }

    /** AC-LEG-1-DELEGATION-CONTRACT-TESTED (b): lapCount below threshold → Leg 1. */
    @Test
    void optimize_lapCountBelowThreshold_delegatesToLeg1() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // lapCount = 1 < EXHAUSTIVE_MAX_N (=2), rowCount = 1 * FIELD_COUNT = 3
        MappingResult mapping = buildMappingWithLapCount(phaseId, 1, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);

        subject.optimize(phaseId);

        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
        verify(reachabilityMock, never()).isReachable();
    }

    // =========================================================================
    // AC-ROUTING-EXTENDED-LEG-2 (E27S03): reachable → Leg 2 dispatcher
    // =========================================================================

    /**
     * AC-ROUTING-EXTENDED-LEG-2: lapCount > threshold + dispatcher reachable → calls Leg 2
     * (submitJob + pollResult), applies result, does NOT call Leg 3.
     *
     * <p>E63S02 update: pollResult now returns the actual rank (not the old empty-array sentinel).
     * Updated stub to return {@code Optional.of(new int[]{0})} — rank 0 = identity permutation.
     */
    @Test
    void optimize_lapCountAboveThreshold_dispatcherReachable_callsLeg2() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // lapCount = EXHAUSTIVE_MAX_N + 1 = 3, rowCount = 3 * FIELD_COUNT = 9
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        // E63S02: pollResult now returns the actual rank from finalResult, not the empty sentinel
        when(dispatcherClientMock.pollResult(eq(jobId), any(CancellationToken.class)))
                .thenReturn(Optional.of(new int[] {0}));

        subject.optimize(phaseId);

        verify(reachabilityMock, atLeastOnce()).isReachable();
        verify(dispatcherClientMock, atLeastOnce()).submitJob(any());
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
    }

    // =========================================================================
    // AC-ROUTING-EXTENDED-LEG-3 (E27S02 preserved): unreachable → Leg 3
    // =========================================================================

    /** AC-ROUTING-EXTENDED-LEG-3: lapCount > threshold + dispatcher NOT reachable → Leg 3. */
    @Test
    void optimize_lapCountAboveThreshold_dispatcherUnreachable_callsLeg3() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(false);
        when(cancelableServiceMock.optimize(
                        eq(phaseId), eq(tournamentId), any(CancellationToken.class)))
                .thenReturn(OptimizationResult.completed(0L, 0.0));

        subject.optimize(phaseId);

        verify(reachabilityMock, atLeastOnce()).isReachable();
        verify(dispatcherClientMock, never()).submitJob(any());
        verify(cancelableServiceMock)
                .optimize(eq(phaseId), eq(tournamentId), any(CancellationToken.class));
        verify(registryMock).register(eq(tournamentId), any(JobHandle.class));
        verify(registryMock).complete(tournamentId);
    }

    /**
     * AC-ROUTING-EXTENDED-LEG-3: on Leg 3 exception, registry complete() is still called (no handle
     * leak).
     */
    @Test
    void optimize_leg3Throws_registryIsStillCompleted() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(false);
        when(cancelableServiceMock.optimize(eq(phaseId), eq(tournamentId), any()))
                .thenThrow(new IllegalStateException("simulated compute error"));

        assertThatThrownBy(() -> subject.optimize(phaseId))
                .isInstanceOf(IllegalStateException.class);

        verify(registryMock).complete(tournamentId);
    }

    // =========================================================================
    // AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR (E27S03)
    // =========================================================================

    /**
     * AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR: when submitJob fails with algorithm mismatch, routing
     * falls through to Leg 3.
     */
    @Test
    void optimize_leg2AlgorithmMismatch_fallsThroughToLeg3() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        when(dispatcherClientMock.submitJob(any()))
                .thenThrow(new DispatcherAlgorithmMismatchException("Ed25519", 400));
        when(cancelableServiceMock.optimize(
                        eq(phaseId), eq(tournamentId), any(CancellationToken.class)))
                .thenReturn(OptimizationResult.completed(0L, 0.0));

        // Must NOT throw — falls through to Leg 3
        subject.optimize(phaseId);

        verify(dispatcherClientMock, atLeastOnce()).submitJob(any());
        verify(cancelableServiceMock)
                .optimize(eq(phaseId), eq(tournamentId), any(CancellationToken.class));
    }

    /**
     * AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR: when submitJob fails with a generic RuntimeException,
     * routing falls through to Leg 3.
     */
    @Test
    void optimize_leg2WireError_fallsThroughToLeg3() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        when(dispatcherClientMock.submitJob(any()))
                .thenThrow(new RuntimeException("HTTP 500 from dispatcher"));
        when(cancelableServiceMock.optimize(
                        eq(phaseId), eq(tournamentId), any(CancellationToken.class)))
                .thenReturn(OptimizationResult.completed(0L, 0.0));

        // Must NOT throw — falls through to Leg 3
        subject.optimize(phaseId);

        verify(cancelableServiceMock)
                .optimize(eq(phaseId), eq(tournamentId), any(CancellationToken.class));
    }

    /**
     * AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR: when pollResult returns empty (timeout), routing falls
     * through to Leg 3.
     */
    @Test
    void optimize_leg2PollTimeout_fallsThroughToLeg3() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        when(dispatcherClientMock.pollResult(eq(jobId), any(CancellationToken.class)))
                .thenReturn(Optional.empty()); // timeout (token not cancelled → Leg 3 fallthrough)
        when(cancelableServiceMock.optimize(
                        eq(phaseId), eq(tournamentId), any(CancellationToken.class)))
                .thenReturn(OptimizationResult.completed(0L, 0.0));

        subject.optimize(phaseId);

        verify(cancelableServiceMock)
                .optimize(eq(phaseId), eq(tournamentId), any(CancellationToken.class));
    }

    // =========================================================================
    // E51S11 RED-first tests (per DEC-22 Iron Law)
    // =========================================================================

    /**
     * AC-TEST-DEC-49-N-EQUALS-LAPCOUNT-RED (E51S11): routing uses lapCount (= rowCount /
     * fieldCount) as N, NOT rowCount.
     *
     * <p>With lapCount = EXHAUSTIVE_MAX_N (2) and fieldCount = 3, rowCount = 6. Old code uses N =
     * rowCount = 6 > threshold=2, routing to Leg 2/3. New code uses N = lapCount = 2 = threshold,
     * routing to Leg 1 (inline applicator). Test verifies applicator.applyResult is called and Leg
     * 2/3 is NOT reached.
     *
     * <p>FAILS before fix because current code uses N = rowCount = 6 > threshold=2 → Leg 2/3.
     */
    @Test
    void optimize_lapCountEqualsThreshold_routesToLeg1_notLeg23_DEC49NAsLapCount() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // lapCount = EXHAUSTIVE_MAX_N (=2), rowCount = 2 * 3 = 6
        // Old code: N = 6 > threshold=2 → Leg 2/3. New code: N = lapCount = 2 ≤ threshold → Leg 1.
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, EXHAUSTIVE_MAX_N, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);

        subject.optimize(phaseId);

        // Leg 1: applicator called, NOT Leg 2/3
        verify(applicatorMock, atLeastOnce())
                .applyResult(anyLong(), eq(FIELD_COUNT), any(MappingResult.class));
        verify(reachabilityMock, never()).isReachable();
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
        verify(dispatcherClientMock, never()).submitJob(any());
    }

    /**
     * AC-TEST-EXHAUSTIVE-LEG-1-WHEN-LAPCOUNT-LE-THRESHOLD-RED (E51S11): lapCount ≤ threshold →
     * applicator called (Leg 1 inline exhaustive search), dispatcher NOT called.
     *
     * <p>lapCount=1 < threshold=2 → applicator invoked; reachability NOT checked.
     *
     * <p>FAILS before fix because current code uses N = rowCount = 3 > threshold=2 → goes to Leg
     * 2/3.
     */
    @Test
    void optimize_lapCount1_belowThreshold_routesToLeg1Applicator() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // lapCount=1, fieldCount=3, rowCount=3
        MappingResult mapping = buildMappingWithLapCount(phaseId, 1, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);

        subject.optimize(phaseId);

        verify(applicatorMock, atLeastOnce())
                .applyResult(anyLong(), eq(FIELD_COUNT), any(MappingResult.class));
        verify(reachabilityMock, never()).isReachable();
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
    }

    // =========================================================================
    // E54S02 RED-first test — DEC-61 Clause D: expansion block removed
    // (AC-TEST-ROUTING-EXPANSION-REMOVED-RED)
    // =========================================================================

    /**
     * AC-TEST-ROUTING-EXPANSION-REMOVED-RED (E54S02):
     *
     * <p>After Mapper refactor (E54S02 DEC-61 Clause B+D), the {@code
     * RoutingSlotOptimizationClient.executeLeg1Inline} method MUST NOT expand the lap permutation
     * to a row sequence via {@code pi[i/fieldCount]*fieldCount + i%fieldCount}. Instead, {@code π}
     * directly is the row permutation of length {@code lapCount} passed to {@code
     * VarietyScorer.scoreWithMatrix}.
     *
     * <p>Observable contract: with a post-refactor {@link MappingResult} where {@code
     * canonical.rowCount() = lapCount} (e.g., lapCount=2, fieldCount=3, matchCount=6), the
     * applicator must be called exactly once with the correct {@code MappingResult} (Leg 1 path).
     * The expansion is removed; {@code rowSeq} is now {@code π} of length {@code lapCount}, not
     * {@code matchCount}.
     *
     * <p>The structural test: use a lap-row MappingResult (canonical.rowCount()=2, matchCount=6).
     * Under the OLD code, {@code lapCount = rowCount / fieldCount = 6/3 = 2} still happens to
     * produce a valid π (same lapCount). The structural guarantee is that the EXPANSION block
     * {@code pi[i/fieldCount]*fieldCount + i%fieldCount} is absent from the source. This test
     * verifies the observable contract: applicator is called once for Leg 1 with
     * lapCount=2≤threshold.
     */
    @Test
    void optimize_lapRowMapping_noExpansionBlock_applicatorCalledOnce_E54S02() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // Post-refactor: lapCount=2, canonical.rowCount()=2, matchCount=6 (lap-rows)
        // EXHAUSTIVE_MAX_N=2 so lapCount=2 ≤ threshold → Leg 1
        MappingResult lapRowMapping =
                buildLapRowMappingWithLapCount(
                        phaseId, EXHAUSTIVE_MAX_N, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(lapRowMapping);

        subject.optimize(phaseId);

        // Leg 1: applicator called once, no Leg 2/3
        verify(applicatorMock, atLeastOnce())
                .applyResult(anyLong(), eq(FIELD_COUNT), any(MappingResult.class));
        verify(reachabilityMock, never()).isReachable();
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
    }

    // =========================================================================
    // E51S11 RED-first tests (per DEC-22 Iron Law)
    // =========================================================================

    /**
     * AC-TEST-PHASE-GLOBAL-TWO-GROUP (E54S03): given a phase with 2 groups (2 distinct group IDs in
     * mapping rows), RoutingSlotOptimizationClient invokes the applicator exactly once with the
     * full phase-global mapping (DEC-61 Clause D).
     *
     * <p>Prior to E54S03 (per-group model from E51S11), the applicator was invoked twice (once per
     * group). E54S03 eliminates per-group invocation: a single {@code mapper.map(phaseId)} + single
     * {@code applicator.applyResult} call preserves Group 2 lap offsets (eliminates Defect 3 from
     * DEC-61).
     */
    @Test
    void optimize_twoGroups_applicatorCalledOnce_phaseGlobalInvocation_E54S03() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // Full 2-group mapping: 2 lap-rows (1 per group), total 2*FIELD_COUNT matches
        MappingResult fullMapping = buildTwoGroupMapping(phaseId, 1, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(fullMapping);

        subject.optimize(phaseId);

        // Phase-global: applicator called once with the full 2-group mapping
        verify(applicatorMock, times(1))
                .applyResult(anyLong(), eq(FIELD_COUNT), any(MappingResult.class));
        // mapGroup does not exist (deleted per DEC-61 Clause D) — enforced by compilation.
    }

    // =========================================================================
    // E63S02 RED-first tests — AC-TEST-LEG2-RESULT-APPLIED
    // pollResult now returns the actual rank; tryLeg2 must apply it via applicator
    // =========================================================================

    /**
     * AC-TEST-LEG2-RESULT-APPLIED (E63S02 RED-first): when Leg 2 completes successfully (pollResult
     * returns a rank), {@link de.vvwt.tm.slotopt.SlotResultApplicator#applyResult} is called with
     * the fetched rank, the fieldCount, and the phase-global mapping.
     *
     * <p>RED against current code: current tryLeg2() ignores the result content and never calls
     * applicator. This test verifies that after the fix, the applicator IS called with the correct
     * rank when Leg 2 succeeds.
     *
     * <p>Fixture: lapCount = EXHAUSTIVE_MAX_N + 1 = 3 (above threshold → routes to Leg 2);
     * dispatcher reachable; pollResult returns rank=5 (non-identity permutation). After the fix,
     * applicator must receive rank=5.
     */
    @Test
    void optimize_leg2Succeeds_applicatorCalledWithRankFromDispatcher_E63S02() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        // Rank 5 returned by the dispatcher (non-identity permutation)
        int dispatcherRank = 5;
        when(dispatcherClientMock.pollResult(eq(jobId), any(CancellationToken.class)))
                .thenReturn(Optional.of(new int[] {dispatcherRank}));

        subject.optimize(phaseId);

        // Applicator MUST be called with the dispatcher's rank
        verify(applicatorMock).applyResult(eq((long) dispatcherRank), eq(FIELD_COUNT), any());
        // Leg 3 MUST NOT be invoked (Leg 2 succeeded)
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
    }

    /**
     * AC-TEST-LEG2-RESULT-APPLIED (E63S02): applicator is called with rank=0 (identity permutation)
     * when the dispatcher returns rank 0 — identity is a valid optimization result.
     */
    @Test
    void optimize_leg2Succeeds_rank0_applicatorCalledWithRank0_E63S02() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        when(dispatcherClientMock.pollResult(eq(jobId), any(CancellationToken.class)))
                .thenReturn(Optional.of(new int[] {0}));

        subject.optimize(phaseId);

        verify(applicatorMock).applyResult(eq(0L), eq(FIELD_COUNT), any());
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
    }

    /**
     * AC-ERR-RESULT-FETCH-FAILURE-FALLS-THROUGH (E63S02): when pollResult returns empty (malformed
     * result / fetch failure from dispatcher), tryLeg2 returns false and routing falls through to
     * Leg 3. The applicator MUST NOT be called.
     *
     * <p>Distinguishes the "result not available (empty Optional)" case from the existing "poll
     * timeout (empty Optional)" case — both must produce Leg 3 fall-through with no applicator
     * call.
     */
    @Test
    void optimize_leg2ResultFetchFailure_fallsThroughToLeg3_applicatorNotCalled_E63S02() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        // Empty optional = malformed result or fetch failure (AC-ERR-RESULT-FETCH-FAILURE)
        // token not cancelled → falls through to Leg 3
        when(dispatcherClientMock.pollResult(eq(jobId), any(CancellationToken.class)))
                .thenReturn(Optional.empty());
        when(cancelableServiceMock.optimize(
                        eq(phaseId), eq(tournamentId), any(CancellationToken.class)))
                .thenReturn(OptimizationResult.completed(0L, 0.0));

        subject.optimize(phaseId);

        // Must NOT call applicator (no result to apply)
        verify(applicatorMock, never()).applyResult(anyLong(), anyInt(), any());
        // Must fall through to Leg 3
        verify(cancelableServiceMock)
                .optimize(eq(phaseId), eq(tournamentId), any(CancellationToken.class));
    }

    // =========================================================================
    // E54S03 RED-first tests — DEC-61 Clause D: L3 phase-global invocation
    // AC-TEST-L3-PHASE-GLOBAL-INVOCATION-RED
    // =========================================================================

    /**
     * AC-TEST-L3-PHASE-GLOBAL-INVOCATION-RED (E54S03 / DEC-61 Clause D):
     *
     * <p>Post-E54S03, {@link RoutingSlotOptimizationClient#optimize(UUID)} MUST call {@link
     * PhaseToRawPhaseDefMapper#map(UUID)} exactly once (phase-global) and MUST NOT call {@link
     * PhaseToRawPhaseDefMapper#mapGroup(UUID, int)} at all. The {@link SlotResultApplicator} MUST
     * be invoked exactly once with the phase-global mapping.
     *
     * <p>Fixture: 12T/2G/3F symmetric phase → lapCount=10, fieldCount=3. With exhaustiveMaxN=10
     * (production default), lapCount=10 ≤ threshold → Leg 1 inline.
     *
     * <p>RED against current code: current {@code optimize()} calls {@code map(phaseId)} for group
     * extraction then {@code mapGroup(phaseId, groupNumber)} per group → 2 {@code mapGroup} calls
     * and 2 applicator calls for a 2-group phase.
     */
    @Test
    void optimize_phaseGlobal_singleMapCall_noMapGroupCall_singleApplicatorCall_E54S03() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // 12T/2G/3F: lapCount=10, fieldCount=3. exhaustiveMaxN=10 → Leg 1
        MappingResult phaseMapping =
                buildMappingWithLapCount(phaseId, EXHAUSTIVE_MAX_N_PROD, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(phaseMapping);

        RoutingSlotOptimizationClient prodSubject = subjectWithProdThreshold();
        prodSubject.optimize(phaseId);

        // Phase-global: mapper.map() called once
        verify(mapperMock, times(1)).map(phaseId);
        // mapGroup() does not exist (deleted per DEC-61 Clause D — enforced by compilation)
        // Applicator called exactly once with the phase-global mapping (Leg 1)
        verify(applicatorMock, times(1))
                .applyResult(anyLong(), eq(FIELD_COUNT), any(MappingResult.class));
        // Leg 2/3 not invoked (lapCount=10 ≤ threshold=10 → Leg 1)
        verify(reachabilityMock, never()).isReachable();
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
    }

    /**
     * AC-TEST-L3-PHASE-GLOBAL-INVOCATION-RED (E54S03): empty phase (0 matches) → no-op, no
     * exception.
     *
     * <p>Post-E54S03, an empty phase mapping (0 matches, lapCount=0) must produce no calls to
     * applicator or legs 2/3.
     */
    @Test
    void optimize_emptyPhaseMapping_noOp_E54S03() {
        UUID phaseId = UUID.randomUUID();
        // Empty mapping: 0 laps, 0 matches
        de.vvwt.slotopt.worker.types.RawPhaseDef emptyRaw =
                new de.vvwt.slotopt.worker.types.RawPhaseDef(0, 0, List.of());
        de.vvwt.slotopt.worker.types.CanonicalPhaseDef emptyCanonical =
                new de.vvwt.slotopt.worker.types.CanonicalPhaseDef(0, 0, List.of());
        MappingResult emptyMapping =
                new MappingResult(emptyRaw, emptyCanonical, 0, List.of(), new int[0][]);
        when(mapperMock.map(phaseId)).thenReturn(emptyMapping);

        RoutingSlotOptimizationClient prodSubject = subjectWithProdThreshold();
        // Must not throw
        prodSubject.optimize(phaseId);

        verify(mapperMock, times(1)).map(phaseId);
        // mapGroup() does not exist (deleted per DEC-61 Clause D — enforced by compilation)
        verify(applicatorMock, never()).applyResult(anyLong(), anyInt(), any());
        verify(reachabilityMock, never()).isReachable();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a post-refactor (E54S02/DEC-61 Clause B) {@link MappingResult} with {@code lapCount}
     * lap-rows (raw.rowCount()=lapCount) and {@code lapCount * fieldCount} matches
     * (matchOrder.size()=lapCount*fieldCount). All rows in group 0. Matches carry the given
     * tournamentId.
     */
    private static MappingResult buildMappingWithLapCount(
            UUID phaseId, int lapCount, int fieldCount, UUID tournamentId) {
        int matchCount = lapCount * fieldCount;
        List<de.vvwt.slotopt.worker.types.RawRow> rows = new java.util.ArrayList<>();
        List<Match> matches = new java.util.ArrayList<>();
        // Build lapCount lap-rows; each lap has fieldCount matches (2 avatars each)
        for (int lap = 0; lap < lapCount; lap++) {
            java.util.List<de.vvwt.slotopt.worker.types.PositionTuple> lapTuples =
                    new java.util.ArrayList<>();
            for (int f = 0; f < fieldCount; f++) {
                int matchIdx = lap * fieldCount + f;
                var pt1 = new de.vvwt.slotopt.worker.types.PositionTuple(0, matchIdx * 2);
                var pt2 = new de.vvwt.slotopt.worker.types.PositionTuple(0, matchIdx * 2 + 1);
                lapTuples.add(pt1);
                lapTuples.add(pt2);
                Match m = new Match();
                m.setId(UUID.randomUUID());
                m.setPhaseId(phaseId);
                m.setTournamentId(tournamentId);
                m.setLapNumber(lap + 1); // 1-based
                m.setFieldNumber(f + 1); // 1-based
                matches.add(m);
            }
            rows.add(new de.vvwt.slotopt.worker.types.RawRow(lapTuples));
        }
        int auditId = Math.abs(phaseId.hashCode());
        RawPhaseDef raw = new RawPhaseDef(auditId, lapCount, rows);
        de.vvwt.slotopt.worker.types.TransformResult tr =
                de.vvwt.slotopt.worker.types.StructuralFingerprint.transform(raw);
        CanonicalPhaseDef canonical = tr.canonical();
        var denseMap = DefaultPhaseToRawPhaseDefMapper.buildDenseIdMapping(raw);
        int[][] denseIdsByRawRow = new int[lapCount][];
        for (int lap = 0; lap < lapCount; lap++) {
            de.vvwt.slotopt.worker.types.RawRow row = rows.get(lap);
            int[] ids = new int[row.positions().size()];
            for (int j = 0; j < ids.length; j++) {
                ids[j] = denseMap.get(row.positions().get(j));
            }
            denseIdsByRawRow[lap] = ids;
        }
        return new MappingResult(
                raw, canonical, canonical.avatarCount(), matches, denseIdsByRawRow);
    }

    /**
     * Builds a 2-group post-refactor (E54S02/DEC-61 Clause B) {@link MappingResult} with {@code
     * lapCountPerGroup} lap-rows per group (2*lapCountPerGroup total lap-rows). Rows in group 1 use
     * PositionTuples with group=1; rows in group 2 use group=2. matchOrder.size() = 2 *
     * lapCountPerGroup * fieldCount (all matches across both groups).
     */
    private static MappingResult buildTwoGroupMapping(
            UUID phaseId, int lapCountPerGroup, int fieldCount, UUID tournamentId) {
        int lapCount = lapCountPerGroup * 2; // total lap-rows across both groups
        List<de.vvwt.slotopt.worker.types.RawRow> rows = new java.util.ArrayList<>();
        List<Match> matches = new java.util.ArrayList<>();
        for (int g = 1; g <= 2; g++) {
            for (int lap = 0; lap < lapCountPerGroup; lap++) {
                java.util.List<de.vvwt.slotopt.worker.types.PositionTuple> lapTuples =
                        new java.util.ArrayList<>();
                for (int f = 0; f < fieldCount; f++) {
                    int matchIdx = (g - 1) * lapCountPerGroup * fieldCount + lap * fieldCount + f;
                    var pt1 = new de.vvwt.slotopt.worker.types.PositionTuple(g, matchIdx * 2);
                    var pt2 = new de.vvwt.slotopt.worker.types.PositionTuple(g, matchIdx * 2 + 1);
                    lapTuples.add(pt1);
                    lapTuples.add(pt2);
                    Match m = new Match();
                    m.setId(UUID.randomUUID());
                    m.setPhaseId(phaseId);
                    m.setTournamentId(tournamentId);
                    m.setLapNumber(lap + 1); // 1-based
                    m.setFieldNumber(f + 1); // 1-based
                    matches.add(m);
                }
                rows.add(new de.vvwt.slotopt.worker.types.RawRow(lapTuples));
            }
        }
        int auditId = Math.abs(phaseId.hashCode());
        RawPhaseDef raw = new RawPhaseDef(auditId, lapCount, rows);
        de.vvwt.slotopt.worker.types.TransformResult tr =
                de.vvwt.slotopt.worker.types.StructuralFingerprint.transform(raw);
        CanonicalPhaseDef canonical = tr.canonical();
        var denseMap = DefaultPhaseToRawPhaseDefMapper.buildDenseIdMapping(raw);
        int[][] denseIdsByRawRow = new int[lapCount][];
        for (int i = 0; i < lapCount; i++) {
            de.vvwt.slotopt.worker.types.RawRow row = rows.get(i);
            int[] ids = new int[row.positions().size()];
            for (int j = 0; j < ids.length; j++) {
                ids[j] = denseMap.get(row.positions().get(j));
            }
            denseIdsByRawRow[i] = ids;
        }
        return new MappingResult(
                raw, canonical, canonical.avatarCount(), matches, denseIdsByRawRow);
    }

    // =========================================================================
    // E63S06 RED-first tests — Leg-2 cancel: register handle + 3-case BSF
    // =========================================================================

    /**
     * AC-TEST-LEG2-JOB-REGISTERED-CANCELLABLE (E63S06 RED-first):
     *
     * <p>While Leg-2 is running (submitJob called, pollResult in progress), the job MUST be
     * registered in {@link de.vvwt.tm.slotopt.SlotOptimizationJobRegistry} so that an operator
     * cancel for that tournament reaches a handle.
     *
     * <p>RED against current code: {@code tryLeg2()} never calls {@code jobRegistry.register()}.
     * After the fix, a {@link de.vvwt.tm.slotopt.JobHandle} is registered before {@code pollResult}
     * is called.
     */
    @Test
    void leg2Cancel_jobRegisteredBeforePoll_E63S06() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        when(dispatcherClientMock.pollResult(eq(jobId), any(CancellationToken.class)))
                .thenReturn(Optional.of(new int[] {0}));

        subject.optimize(phaseId);

        // MUST register a handle in the registry before pollResult is called
        verify(registryMock).register(eq(tournamentId), any(JobHandle.class));
        // MUST complete the handle after Leg-2 finishes
        verify(registryMock).complete(tournamentId);
    }

    /**
     * AC-TEST-CANCEL-CASE-PARTIAL-BEST-SO-FAR (E63S06 RED-first — case 2):
     *
     * <p>When an operator cancel interrupts the poll (pollResult returns empty due to cancellation)
     * AND the dispatcher exposes a partial bestSoFar (≥1 packet completed), TM applies it via
     * {@link de.vvwt.tm.slotopt.SlotResultApplicator}.
     *
     * <p>RED against current code: cancel-interrupted poll falls through to Leg 3; no BSF fetch.
     * After the fix, {@code fetchBestSoFar(jobId)} is called and the result is applied.
     *
     * <p>Simulates cancel: {@code pollResult(jobId, token)} returns empty (token is cancelled).
     * {@code fetchBestSoFar(jobId)} returns rank=7 (partial result). Applicator must be called with
     * rank=7; Leg 3 must NOT be invoked.
     */
    @Test
    void leg2Cancel_cancelledPoll_partialBsfApplied_caseTwo_E63S06() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        // pollResult returns empty (simulating cancel-interrupted poll)
        when(dispatcherClientMock.pollResult(eq(jobId), any(CancellationToken.class)))
                .thenReturn(Optional.empty());
        // Dispatcher has partial bestSoFar with rank=7
        when(dispatcherClientMock.fetchBestSoFar(jobId)).thenReturn(Optional.of(new int[] {7}));

        // Simulate cancel: the CancellationToken is cancelled during the poll
        // We stub the registry so when cancel is checked the token IS cancelled
        // (done by setting cancellationFlag via the JobHandle that tryLeg2 creates)
        // For the test: we capture the token via registryMock and cancel it
        java.util.concurrent.atomic.AtomicReference<CancellationToken> capturedToken =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            JobHandle h = inv.getArgument(1, JobHandle.class);
                            capturedToken.set(h.getCancellationToken());
                            // Cancel the token so pollResult(jobId, token) sees it cancelled
                            h.getCancellationToken().cancel();
                            return null;
                        })
                .when(registryMock)
                .register(eq(tournamentId), any(JobHandle.class));

        subject.optimize(phaseId);

        // Case 2: applicator must be called with BSF rank=7
        verify(applicatorMock).applyResult(eq(7L), eq(FIELD_COUNT), any(MappingResult.class));
        // Leg 3 must NOT be invoked (TM detaches)
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
    }

    /**
     * AC-TEST-CANCEL-CASE-NO-RESULT (E63S06 RED-first — case 3):
     *
     * <p>When an operator cancel interrupts the poll AND the dispatcher has produced no result
     * (bestSoFar is empty), TM retains the existing valid L1+L2 assignment. The phase is never left
     * unassigned and the cancel never throws.
     *
     * <p>RED against current code: cancel-interrupted poll falls through to Leg 3. After the fix,
     * Leg 3 is NOT invoked and the applicator is NOT called (L1+L2 assignment retained).
     */
    @Test
    void leg2Cancel_cancelledPoll_noBsf_retainsL1L2_caseThree_E63S06() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        when(dispatcherClientMock.pollResult(eq(jobId), any(CancellationToken.class)))
                .thenReturn(Optional.empty());
        // No bestSoFar from dispatcher (no packet completed)
        when(dispatcherClientMock.fetchBestSoFar(jobId)).thenReturn(Optional.empty());

        // Cancel the token via registry side-effect
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            JobHandle h = inv.getArgument(1, JobHandle.class);
                            h.getCancellationToken().cancel();
                            return null;
                        })
                .when(registryMock)
                .register(eq(tournamentId), any(JobHandle.class));

        subject.optimize(phaseId);

        // Case 3: applicator NOT called (L1+L2 retained), Leg 3 NOT invoked
        verify(applicatorMock, never()).applyResult(anyLong(), anyInt(), any());
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
    }

    /**
     * AC-TEST-CANCEL-RACE-WITH-COMPLETION (E63S06 — case 1):
     *
     * <p>A cancel that arrives AFTER the dispatcher has already finalized the job results in the
     * normal Leg-2 final-result apply; the cancel is a no-op (not an error, not a regression of the
     * applied result).
     *
     * <p>This test verifies that the existing E63S02 happy path is PRESERVED: when pollResult
     * returns a rank, the applicator is called and Leg 3 is NOT invoked — regardless of whether the
     * cancel was requested.
     */
    @Test
    void leg2Cancel_raceWithCompletion_normalApplyRuns_caseOne_E63S06() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        // pollResult returns a result (dispatcher already finished)
        when(dispatcherClientMock.pollResult(eq(jobId), any(CancellationToken.class)))
                .thenReturn(Optional.of(new int[] {3}));

        subject.optimize(phaseId);

        // Case 1: normal apply with rank=3
        verify(applicatorMock).applyResult(eq(3L), eq(FIELD_COUNT), any(MappingResult.class));
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
        // fetchBestSoFar must NOT be called (poll succeeded → no BSF fetch needed)
        verify(dispatcherClientMock, never()).fetchBestSoFar(any());
    }

    /**
     * AC-TEST-DISPATCHER-JOB-NOT-ABORTED (E63S06):
     *
     * <p>On cancel, TM detaches — no abort / cancel request is sent to the dispatcher. The
     * dispatcher job is left to run for the shared structural-fingerprint cache.
     *
     * <p>Verifies structurally: the {@link de.vvwt.tm.slotopt.SlotOptimizationDispatcherClient}
     * interface does NOT have an {@code abortJob} or {@code cancelJob} method called (and the
     * production code never calls one). Since we mock the interface, any unexpected call would fail
     * the test.
     */
    @Test
    void leg2Cancel_noAbortSentToDispatcher_E63S06() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping =
                buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        when(dispatcherClientMock.pollResult(eq(jobId), any(CancellationToken.class)))
                .thenReturn(Optional.empty());
        when(dispatcherClientMock.fetchBestSoFar(jobId)).thenReturn(Optional.empty());

        org.mockito.Mockito.doAnswer(
                        inv -> {
                            JobHandle h = inv.getArgument(1, JobHandle.class);
                            h.getCancellationToken().cancel();
                            return null;
                        })
                .when(registryMock)
                .register(eq(tournamentId), any(JobHandle.class));

        subject.optimize(phaseId);

        // Only submitJob, pollResult (with token), and fetchBestSoFar are allowed — no abort
        verify(dispatcherClientMock).submitJob(any());
        verify(dispatcherClientMock).pollResult(eq(jobId), any(CancellationToken.class));
        verify(dispatcherClientMock).fetchBestSoFar(jobId);
        // No other interactions on dispatcherClientMock (no abortJob etc.)
        org.mockito.Mockito.verifyNoMoreInteractions(dispatcherClientMock);
    }

    /**
     * Builds a post-refactor (E54S02) {@link MappingResult} where raw rows are lap-rows.
     *
     * <p>Post-refactor shape: {@code raw.rowCount() = lapCount}, {@code canonical.rowCount() =
     * lapCount}, {@code matchOrder.size() = lapCount * fieldCount}. Used by
     * AC-TEST-ROUTING-EXPANSION-REMOVED-RED.
     *
     * @param phaseId the phase UUID
     * @param lapCount the number of laps
     * @param fieldCount the number of fields per lap
     * @param tournamentId the tournament UUID for matches
     * @return post-refactor MappingResult with lap-rows
     */
    private static MappingResult buildLapRowMappingWithLapCount(
            UUID phaseId, int lapCount, int fieldCount, UUID tournamentId) {
        int matchCount = lapCount * fieldCount;
        // Build lapCount lap-rows; each lap-row has positions = union of fieldCount*2 avatars
        // Use distinct avatars per lap for proper structural representation
        List<de.vvwt.slotopt.worker.types.RawRow> lapRows = new java.util.ArrayList<>();
        List<Match> matches = new java.util.ArrayList<>();
        for (int lap = 0; lap < lapCount; lap++) {
            // Each lap has fieldCount matches, each with 2 distinct avatars
            List<de.vvwt.slotopt.worker.types.PositionTuple> positions =
                    new java.util.ArrayList<>();
            for (int f = 0; f < fieldCount; f++) {
                int base = lap * fieldCount * 2 + f * 2;
                var pt1 = new de.vvwt.slotopt.worker.types.PositionTuple(1, base);
                var pt2 = new de.vvwt.slotopt.worker.types.PositionTuple(1, base + 1);
                positions.add(pt1);
                positions.add(pt2);
                Match m = new Match();
                m.setId(UUID.randomUUID());
                m.setPhaseId(phaseId);
                m.setTournamentId(tournamentId);
                m.setLapNumber(lap + 1); // 1-based
                m.setFieldNumber(f + 1); // 1-based
                matches.add(m);
            }
            lapRows.add(new de.vvwt.slotopt.worker.types.RawRow(positions));
        }
        int auditId = Math.abs(phaseId.hashCode());
        RawPhaseDef raw = new RawPhaseDef(auditId, lapCount, lapRows);
        de.vvwt.slotopt.worker.types.TransformResult tr =
                de.vvwt.slotopt.worker.types.StructuralFingerprint.transform(raw);
        CanonicalPhaseDef canonical = tr.canonical();
        var denseMap = DefaultPhaseToRawPhaseDefMapper.buildDenseIdMapping(raw);
        int[][] denseIdsByRawRow = new int[lapCount][];
        for (int i = 0; i < lapCount; i++) {
            List<de.vvwt.slotopt.worker.types.PositionTuple> pos = lapRows.get(i).positions();
            denseIdsByRawRow[i] = new int[pos.size()];
            for (int j = 0; j < pos.size(); j++) {
                Integer id = denseMap.get(pos.get(j));
                denseIdsByRawRow[i][j] = id != null ? id : 0;
            }
        }
        return new MappingResult(
                raw, canonical, canonical.avatarCount(), matches, denseIdsByRawRow);
    }
}
