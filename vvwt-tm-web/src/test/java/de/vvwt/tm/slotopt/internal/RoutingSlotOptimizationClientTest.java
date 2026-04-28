package de.vvwt.tm.slotopt.internal;

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
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.OptimizationResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.tournament.Match;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RoutingSlotOptimizationClient} — Leg-1 delegation contract (E27S01) and
 * Leg-3 routing (E27S02, AC-ROUTING-EXTENDED-LEG-3, AC-NO-LEG-2-YET).
 *
 * <p>TDD RED-first per DEC-22 Iron Law. E27S01 delegation tests are preserved; E27S02 adds tests
 * for the N > threshold → Leg 3 routing branch.
 *
 * <p>Per DEC-36: this test class is in the {@code slotopt.internal} package (same as the subject),
 * so white-box reference to {@link RoutingSlotOptimizationClient} is permitted.
 *
 * @see RoutingSlotOptimizationClient
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S01.story.md">Story
 *     E27S01</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 */
class RoutingSlotOptimizationClientTest {

    private DirectSlotOptimizationClient directMock;
    private CancelableInProcessSlotOptimizationService cancelableServiceMock;
    private SlotOptimizationJobRegistry registryMock;
    private PhaseToRawPhaseDefMapper mapperMock;
    private RoutingSlotOptimizationClient subject;

    /** Default exhaustiveMaxN = 2 for tests (smaller threshold for easy fixtures). */
    private static final int EXHAUSTIVE_MAX_N = 2;

    @BeforeEach
    void setUp() {
        directMock = mock(DirectSlotOptimizationClient.class);
        cancelableServiceMock = mock(CancelableInProcessSlotOptimizationService.class);
        registryMock = mock(SlotOptimizationJobRegistry.class);
        mapperMock = mock(PhaseToRawPhaseDefMapper.class);
        subject =
                new RoutingSlotOptimizationClient(
                        directMock,
                        cancelableServiceMock,
                        registryMock,
                        mapperMock,
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
    }

    // =========================================================================
    // AC-ROUTING-EXTENDED-LEG-3 (E27S02)
    // =========================================================================

    /**
     * AC-ROUTING-EXTENDED-LEG-3: N > threshold → registers job + calls Leg 3 + completes registry.
     */
    @Test
    void optimize_nAboveThreshold_callsLeg3AndCompletesRegistry() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int n = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping = buildMapping(phaseId, n, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(cancelableServiceMock.optimize(
                        eq(phaseId), eq(tournamentId), any(CancellationToken.class)))
                .thenReturn(OptimizationResult.completed(0L, 0.0));

        subject.optimize(phaseId);

        verify(directMock, never()).optimize(any());
        verify(registryMock).register(eq(tournamentId), any(JobHandle.class));
        verify(cancelableServiceMock)
                .optimize(eq(phaseId), eq(tournamentId), any(CancellationToken.class));
        verify(registryMock).complete(tournamentId);
    }

    /** AC-NO-LEG-2-YET: N > threshold routing does NOT invoke any dispatcher service. */
    @Test
    void optimize_nAboveThreshold_noDispatcherInvoked() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        int n = EXHAUSTIVE_MAX_N + 1;
        MappingResult mapping = buildMapping(phaseId, n, tournamentId);
        when(mapperMock.map(phaseId)).thenReturn(mapping);
        when(cancelableServiceMock.optimize(eq(phaseId), eq(tournamentId), any()))
                .thenReturn(OptimizationResult.completed(0L, 0.0));

        // Must complete without throwing — no dispatcher mock = no NPE from dispatcher
        subject.optimize(phaseId);
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
        when(cancelableServiceMock.optimize(eq(phaseId), eq(tournamentId), any()))
                .thenThrow(new IllegalStateException("simulated compute error"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> subject.optimize(phaseId))
                .isInstanceOf(IllegalStateException.class);

        verify(registryMock).complete(tournamentId);
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
