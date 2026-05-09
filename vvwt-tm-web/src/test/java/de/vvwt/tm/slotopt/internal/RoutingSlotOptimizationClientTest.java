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
 * routing tests (reachable→Leg2; unreachable→Leg3; wire-error→fallthrough-Leg3).
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
                        EXHAUSTIVE_MAX_N);
    }

    // =========================================================================
    // AC-LEG-1-DELEGATION-CONTRACT-TESTED (E27S01 — preserved)
    // =========================================================================

    /** AC-LEG-1-DELEGATION-CONTRACT-TESTED (a): lapCount at threshold → Leg 1 (direct or inline). */
    @Test
    void optimize_lapCountAtThreshold_delegatesToLeg1() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // lapCount = EXHAUSTIVE_MAX_N (=2), rowCount = lapCount * FIELD_COUNT = 6
        MappingResult mapping = buildMappingWithLapCount(phaseId, EXHAUSTIVE_MAX_N, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(mapperMock.mapGroup(eq(phaseId), anyInt())).thenReturn(
                buildMappingWithLapCount(phaseId, EXHAUSTIVE_MAX_N, FIELD_COUNT, tournamentId));

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
        when(mapperMock.mapGroup(eq(phaseId), anyInt())).thenReturn(
                buildMappingWithLapCount(phaseId, 1, FIELD_COUNT, tournamentId));

        subject.optimize(phaseId);

        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
        verify(reachabilityMock, never()).isReachable();
    }

    // =========================================================================
    // AC-ROUTING-EXTENDED-LEG-2 (E27S03): reachable → Leg 2 dispatcher
    // =========================================================================

    /**
     * AC-ROUTING-EXTENDED-LEG-2: lapCount > threshold + dispatcher reachable → calls Leg 2
     * (submitJob + pollResult), does NOT call Leg 3.
     */
    @Test
    void optimize_lapCountAboveThreshold_dispatcherReachable_callsLeg2() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // lapCount = EXHAUSTIVE_MAX_N + 1 = 3, rowCount = 3 * FIELD_COUNT = 9
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping = buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        MappingResult groupMapping = buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(mapperMock.mapGroup(eq(phaseId), anyInt())).thenReturn(groupMapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        when(dispatcherClientMock.pollResult(jobId)).thenReturn(Optional.of(new int[0]));

        subject.optimize(phaseId);

        verify(reachabilityMock, atLeastOnce()).isReachable();
        verify(dispatcherClientMock, atLeastOnce()).submitJob(any());
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
    }

    // =========================================================================
    // AC-ROUTING-EXTENDED-LEG-3 (E27S02 preserved): unreachable → Leg 3
    // =========================================================================

    /**
     * AC-ROUTING-EXTENDED-LEG-3: lapCount > threshold + dispatcher NOT reachable → Leg 3.
     */
    @Test
    void optimize_lapCountAboveThreshold_dispatcherUnreachable_callsLeg3() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int lapCount = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping = buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        MappingResult groupMapping = buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(mapperMock.mapGroup(eq(phaseId), anyInt())).thenReturn(groupMapping);
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
        MappingResult mapping = buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        MappingResult groupMapping = buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(mapperMock.mapGroup(eq(phaseId), anyInt())).thenReturn(groupMapping);
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
        MappingResult mapping = buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        MappingResult groupMapping = buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(mapperMock.mapGroup(eq(phaseId), anyInt())).thenReturn(groupMapping);
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
        MappingResult mapping = buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        MappingResult groupMapping = buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(mapperMock.mapGroup(eq(phaseId), anyInt())).thenReturn(groupMapping);
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
        MappingResult mapping = buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        MappingResult groupMapping = buildMappingWithLapCount(phaseId, lapCount, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(mapperMock.mapGroup(eq(phaseId), anyInt())).thenReturn(groupMapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        when(dispatcherClientMock.pollResult(jobId)).thenReturn(Optional.empty()); // timeout
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
     * AC-TEST-DEC-49-N-EQUALS-LAPCOUNT-RED (E51S11): routing uses lapCount (= rowCount / fieldCount)
     * as N, NOT rowCount.
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
        MappingResult mapping = buildMappingWithLapCount(phaseId, EXHAUSTIVE_MAX_N, FIELD_COUNT, tournamentId);
        MappingResult groupMapping = buildMappingWithLapCount(phaseId, EXHAUSTIVE_MAX_N, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(mapperMock.mapGroup(eq(phaseId), anyInt())).thenReturn(groupMapping);

        subject.optimize(phaseId);

        // Leg 1: applicator called, NOT Leg 2/3
        verify(applicatorMock, atLeastOnce()).applyResult(anyLong(), eq(FIELD_COUNT), any(MappingResult.class));
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
        MappingResult groupMapping = buildMappingWithLapCount(phaseId, 1, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(mapperMock.mapGroup(eq(phaseId), anyInt())).thenReturn(groupMapping);

        subject.optimize(phaseId);

        verify(applicatorMock, atLeastOnce()).applyResult(anyLong(), eq(FIELD_COUNT), any(MappingResult.class));
        verify(reachabilityMock, never()).isReachable();
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
    }

    /**
     * AC-TEST-PER-GROUP-INVOKE-RED (E51S11, NF-MED-1): given a phase with 2 groups (2 distinct group
     * IDs in mapping rows), RoutingSlotOptimizationClient invokes the applicator separately per group
     * (2 applicator invocations).
     *
     * <p>FAILS before fix because current code maps the whole phase and calls applicator once (or
     * delegates to directClient without per-group awareness).
     */
    @Test
    void optimize_twoGroups_applicatorCalledTwice_perGroupInvocation() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // Build a 2-group mapping: group 1 has lapCount=1, group 2 has lapCount=1 (both ≤ threshold)
        // Full mapping has 2 rows (1 per group), but for routing we use per-group mappings
        MappingResult fullMapping = buildTwoGroupMapping(phaseId, 1, FIELD_COUNT, tournamentId);
        MappingResult group1Mapping = buildMappingWithLapCount(phaseId, 1, FIELD_COUNT, tournamentId);
        MappingResult group2Mapping = buildMappingWithLapCount(phaseId, 1, FIELD_COUNT, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(fullMapping);
        // Group numbers from buildTwoGroupMapping: groups 1 and 2
        when(mapperMock.mapGroup(eq(phaseId), eq(1))).thenReturn(group1Mapping);
        when(mapperMock.mapGroup(eq(phaseId), eq(2))).thenReturn(group2Mapping);

        subject.optimize(phaseId);

        // Applicator must be invoked once per group = 2 times total
        verify(applicatorMock, times(2)).applyResult(anyLong(), eq(FIELD_COUNT), any(MappingResult.class));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a minimal {@link MappingResult} with {@code lapCount * fieldCount} rows, all in group
     * 0. N = lapCount (after N-redefinition). Matches carry the given tournamentId.
     */
    private static MappingResult buildMappingWithLapCount(
            UUID phaseId, int lapCount, int fieldCount, UUID tournamentId) {
        int rowCount = lapCount * fieldCount;
        List<de.vvwt.slotopt.worker.types.RawRow> rows = new java.util.ArrayList<>();
        List<Match> matches = new java.util.ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            // All rows in group 0 (single-group mapping)
            var pt1 = new de.vvwt.slotopt.worker.types.PositionTuple(0, r * 2);
            var pt2 = new de.vvwt.slotopt.worker.types.PositionTuple(0, r * 2 + 1);
            rows.add(new de.vvwt.slotopt.worker.types.RawRow(List.of(pt1, pt2)));
            Match m = new Match();
            m.setId(UUID.randomUUID());
            m.setPhaseId(phaseId);
            m.setTournamentId(tournamentId);
            m.setLapNumber(r / fieldCount);
            m.setFieldNumber(r % fieldCount);
            matches.add(m);
        }
        int auditId = Math.abs(phaseId.hashCode());
        RawPhaseDef raw = new RawPhaseDef(auditId, rowCount, rows);
        de.vvwt.slotopt.worker.types.TransformResult tr =
                de.vvwt.slotopt.worker.types.StructuralFingerprint.transform(raw);
        CanonicalPhaseDef canonical = tr.canonical();
        var denseMap = PhaseToRawPhaseDefMapper.buildDenseIdMapping(raw);
        int[][] denseIdsByRawRow = new int[rowCount][];
        for (int r = 0; r < rowCount; r++) {
            var pt1 = new de.vvwt.slotopt.worker.types.PositionTuple(0, r * 2);
            var pt2 = new de.vvwt.slotopt.worker.types.PositionTuple(0, r * 2 + 1);
            denseIdsByRawRow[r] = new int[] {denseMap.get(pt1), denseMap.get(pt2)};
        }
        return new MappingResult(raw, canonical, canonical.avatarCount(), matches, denseIdsByRawRow);
    }

    /**
     * Builds a 2-group {@link MappingResult} with {@code lapCount * fieldCount} rows per group.
     * Rows in group 1 use PositionTuples with group=1; rows in group 2 use group=2.
     */
    private static MappingResult buildTwoGroupMapping(
            UUID phaseId, int lapCountPerGroup, int fieldCount, UUID tournamentId) {
        int rowsPerGroup = lapCountPerGroup * fieldCount;
        int rowCount = rowsPerGroup * 2;
        List<de.vvwt.slotopt.worker.types.RawRow> rows = new java.util.ArrayList<>();
        List<Match> matches = new java.util.ArrayList<>();
        for (int g = 1; g <= 2; g++) {
            for (int r = 0; r < rowsPerGroup; r++) {
                var pt1 = new de.vvwt.slotopt.worker.types.PositionTuple(g, r * 2);
                var pt2 = new de.vvwt.slotopt.worker.types.PositionTuple(g, r * 2 + 1);
                rows.add(new de.vvwt.slotopt.worker.types.RawRow(List.of(pt1, pt2)));
                Match m = new Match();
                m.setId(UUID.randomUUID());
                m.setPhaseId(phaseId);
                m.setTournamentId(tournamentId);
                m.setLapNumber(r / fieldCount);
                m.setFieldNumber(r % fieldCount);
                matches.add(m);
            }
        }
        int auditId = Math.abs(phaseId.hashCode());
        RawPhaseDef raw = new RawPhaseDef(auditId, rowCount, rows);
        de.vvwt.slotopt.worker.types.TransformResult tr =
                de.vvwt.slotopt.worker.types.StructuralFingerprint.transform(raw);
        CanonicalPhaseDef canonical = tr.canonical();
        var denseMap = PhaseToRawPhaseDefMapper.buildDenseIdMapping(raw);
        int[][] denseIdsByRawRow = new int[rowCount][];
        for (int r = 0; r < rowCount; r++) {
            int g = r < rowsPerGroup ? 1 : 2;
            int rInGroup = r < rowsPerGroup ? r : r - rowsPerGroup;
            var pt1 = new de.vvwt.slotopt.worker.types.PositionTuple(g, rInGroup * 2);
            var pt2 = new de.vvwt.slotopt.worker.types.PositionTuple(g, rInGroup * 2 + 1);
            denseIdsByRawRow[r] = new int[] {denseMap.get(pt1), denseMap.get(pt2)};
        }
        return new MappingResult(raw, canonical, canonical.avatarCount(), matches, denseIdsByRawRow);
    }
}
