package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private RoutingSlotOptimizationClient subject;

    /** Default exhaustiveMaxN = 2 for tests (smaller threshold for easy fixtures). */
    private static final int EXHAUSTIVE_MAX_N = 2;

    @BeforeEach
    void setUp() {
        directMock = mock(DirectSlotOptimizationClient.class);
        cancelableServiceMock = mock(CancelableInProcessSlotOptimizationService.class);
        registryMock = mock(SlotOptimizationJobRegistry.class);
        mapperMock = mock(PhaseToRawPhaseDefMapper.class);
        reachabilityMock = mock(DispatcherReachabilityService.class);
        dispatcherClientMock = mock(SlotOptimizationDispatcherClient.class);
        subject =
                new RoutingSlotOptimizationClient(
                        directMock,
                        cancelableServiceMock,
                        registryMock,
                        mapperMock,
                        reachabilityMock,
                        dispatcherClientMock,
                        EXHAUSTIVE_MAX_N);
    }

    // =========================================================================
    // AC-LEG-1-DELEGATION-CONTRACT-TESTED (E27S01 — preserved)
    // =========================================================================

    /** AC-LEG-1-DELEGATION-CONTRACT-TESTED (a): N at threshold → delegates to Direct, not Leg 3. */
    @Test
    void optimize_nAtThreshold_delegatesToDirect() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        MappingResult mapping = buildMapping(phaseId, EXHAUSTIVE_MAX_N, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);

        subject.optimize(phaseId);

        verify(directMock).optimize(phaseId);
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
        verify(reachabilityMock, never()).isReachable();
    }

    /** AC-LEG-1-DELEGATION-CONTRACT-TESTED (b): N below threshold → Direct, not Leg 3. */
    @Test
    void optimize_nBelowThreshold_delegatesToDirect() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        MappingResult mapping = buildMapping(phaseId, 1, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);

        subject.optimize(phaseId);

        verify(directMock).optimize(phaseId);
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
        verify(reachabilityMock, never()).isReachable();
    }

    // =========================================================================
    // AC-ROUTING-EXTENDED-LEG-2 (E27S03): reachable → Leg 2 dispatcher
    // =========================================================================

    /**
     * AC-ROUTING-EXTENDED-LEG-2: N > threshold + dispatcher reachable → calls Leg 2 (submitJob +
     * pollResult), does NOT call Leg 3.
     */
    @Test
    void optimize_nAboveThreshold_dispatcherReachable_callsLeg2() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int n = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping = buildMapping(phaseId, n, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        UUID jobId = UUID.randomUUID();
        when(dispatcherClientMock.submitJob(any())).thenReturn(jobId);
        when(dispatcherClientMock.pollResult(jobId)).thenReturn(Optional.of(new int[0]));

        subject.optimize(phaseId);

        verify(reachabilityMock).isReachable();
        verify(dispatcherClientMock).submitJob(any());
        verify(dispatcherClientMock).pollResult(jobId);
        verify(cancelableServiceMock, never()).optimize(any(), any(), any());
        verify(directMock, never()).optimize(any());
    }

    // =========================================================================
    // AC-ROUTING-EXTENDED-LEG-3 (E27S02 preserved): unreachable → Leg 3
    // =========================================================================

    /**
     * AC-ROUTING-EXTENDED-LEG-3: N > threshold + dispatcher NOT reachable → falls through to Leg 3
     * (cancelable in-process). Does NOT call Leg 2 dispatcher.
     */
    @Test
    void optimize_nAboveThreshold_dispatcherUnreachable_callsLeg3() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int n = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping = buildMapping(phaseId, n, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(false);
        when(cancelableServiceMock.optimize(
                        eq(phaseId), eq(tournamentId), any(CancellationToken.class)))
                .thenReturn(OptimizationResult.completed(0L, 0.0));

        subject.optimize(phaseId);

        verify(reachabilityMock).isReachable();
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
        int n = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping = buildMapping(phaseId, n, tournamentId);
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
     * falls through to Leg 3. User observes a usable optimization result.
     */
    @Test
    void optimize_leg2AlgorithmMismatch_fallsThroughToLeg3() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int n = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping = buildMapping(phaseId, n, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(reachabilityMock.isReachable()).thenReturn(true);
        when(dispatcherClientMock.submitJob(any()))
                .thenThrow(new DispatcherAlgorithmMismatchException("Ed25519", 400));
        when(cancelableServiceMock.optimize(
                        eq(phaseId), eq(tournamentId), any(CancellationToken.class)))
                .thenReturn(OptimizationResult.completed(0L, 0.0));

        // Must NOT throw — falls through to Leg 3
        subject.optimize(phaseId);

        verify(dispatcherClientMock).submitJob(any());
        verify(cancelableServiceMock)
                .optimize(eq(phaseId), eq(tournamentId), any(CancellationToken.class));
    }

    /**
     * AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR: when submitJob fails with a generic RuntimeException
     * (e.g., HTTP 500, network error), routing falls through to Leg 3.
     */
    @Test
    void optimize_leg2WireError_fallsThroughToLeg3() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int n = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping = buildMapping(phaseId, n, tournamentId);
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
        int n = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping = buildMapping(phaseId, n, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
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
    // Helpers
    // =========================================================================

    /**
     * Builds a minimal {@link MappingResult} with given rowCount. Matches carry the given
     * tournamentId so the routing client can extract it for registry/Leg 3 calls.
     */
    private static MappingResult buildMapping(UUID phaseId, int rowCount, UUID tournamentId) {
        List<de.vvwt.slotopt.worker.types.RawRow> rows = new java.util.ArrayList<>();
        List<Match> matches = new java.util.ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            var pt1 = new de.vvwt.slotopt.worker.types.PositionTuple(r, 0);
            var pt2 = new de.vvwt.slotopt.worker.types.PositionTuple(r, 1);
            rows.add(new de.vvwt.slotopt.worker.types.RawRow(List.of(pt1, pt2)));
            Match m = new Match();
            m.setId(UUID.randomUUID());
            m.setPhaseId(phaseId);
            m.setTournamentId(tournamentId);
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
            var pt1 = new de.vvwt.slotopt.worker.types.PositionTuple(r, 0);
            var pt2 = new de.vvwt.slotopt.worker.types.PositionTuple(r, 1);
            denseIdsByRawRow[r] = new int[] {denseMap.get(pt1), denseMap.get(pt2)};
        }
        return new MappingResult(
                raw, canonical, canonical.avatarCount(), matches, denseIdsByRawRow);
    }
}
