package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.OptimizationResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.slotopt.SlotResultApplicator;
import de.vvwt.tm.tournament.Match;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link DefaultCancelableInProcessSlotOptimizationService} — E27S02
 * AC-CANCELABLE-SERVICE-AUTHORED, AC-COOPERATIVE-CANCELLATION-TESTED,
 * AC-BEST-SO-FAR-NON-NULL-AFTER-FIRST-PERMUTATION.
 *
 * <p>TDD RED-first per DEC-22 Iron Law. Tests written before the implementation existed.
 *
 * <p>Per DEC-36: this test class is in the same package ({@code slotopt.internal}) as the subject,
 * so white-box reference to {@link DefaultCancelableInProcessSlotOptimizationService} is permitted.
 *
 * @see DefaultCancelableInProcessSlotOptimizationService
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 */
class DefaultCancelableInProcessSlotOptimizationServiceTest {

    // N=4 has 4!=24 permutations — measurable but fast enough for a unit test.
    // We use a real fixture to satisfy PacketSolver's need for a valid CanonicalPhaseDef.

    private PhaseToRawPhaseDefMapper mapperMock;
    private SlotResultApplicator applicatorMock;
    private SlotOptimizationJobRegistry registryMock;
    private DefaultCancelableInProcessSlotOptimizationService subject;

    /** fieldCount = 1 for unit tests: lapCount = rowCount / 1 = rowCount (simplest case). */
    private static final int TEST_FIELD_COUNT = 1;

    @BeforeEach
    void setUp() {
        mapperMock = mock(PhaseToRawPhaseDefMapper.class);
        applicatorMock = mock(SlotResultApplicator.class);
        registryMock = mock(SlotOptimizationJobRegistry.class);
        // fieldCount=1: lapCount = rowCount / 1 = rowCount (each match is its own "lap").
        // This preserves the original test semantics (2 rows = 2 laps = 2! perms).
        when(mapperMock.getFieldCount()).thenReturn(TEST_FIELD_COUNT);
        subject =
                new DefaultCancelableInProcessSlotOptimizationService(
                        mapperMock, applicatorMock, registryMock, "mean");
    }

    // =========================================================================
    // AC-BEST-SO-FAR-NON-NULL-AFTER-FIRST-PERMUTATION: cancel before first perm
    // =========================================================================

    /**
     * AC-BEST-SO-FAR-NON-NULL-AFTER-FIRST-PERMUTATION (cancel-before-first-permutation contract):
     * if the cancellation token is already cancelled BEFORE optimize() begins looping, the service
     * applies trivial coordinates and returns a non-null result with {@code cancelled = true}.
     */
    @Test
    void optimize_cancelledBeforeStart_returnsTrivialResult() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        MappingResult mapping = buildMinimalMapping(phaseId, 2);
        when(mapperMock.map(phaseId)).thenReturn(mapping);

        CancellationToken token = CancellationToken.create();
        token.cancel(); // cancelled before optimize() begins

        JobHandle handle = new JobHandle(token, Instant.now());
        when(registryMock.getHandle(tournamentId)).thenReturn(Optional.of(handle));

        OptimizationResult result = subject.optimize(phaseId, tournamentId, token);

        assertThat(result).isNotNull();
        assertThat(result.cancelled()).isTrue();
        // SlotResultApplicator must have been called (result applied to matches)
        verify(applicatorMock).applyResult(anyLong(), anyInt(), eq(mapping));
    }

    // =========================================================================
    // AC-TEST-CANCELABLE-BEST-SO-FAR-ON-L2-DEFAULT-RED (E51S11)
    // =========================================================================

    /**
     * AC-TEST-CANCELABLE-BEST-SO-FAR-ON-L2-DEFAULT-RED (E51S11): when cancelled before any rank is
     * evaluated, the service applies rank=0 (L2 baseline / identity lap permutation), NOT "trivial
     * coordinates" from FallbackSlotOptimizationClient.
     *
     * <p>The L2 baseline (rank=0 = identity permutation) must be applied, which preserves the
     * lap/field values set by L2. This is semantically correct: rank=0 is the identity permutation
     * over laps, so L3 leaves L2's assignment unchanged.
     *
     * <p>FAILS before fix if applyResult is NOT called with rank=0 on cancel-before-permutation
     * (e.g. if "trivial coordinates" path directly sets lap=0/field=sequential bypassing
     * SlotResultApplicator). Current implementation calls applyResult(0L, ...) which is correct —
     * test verifies this explicitly and is already GREEN (no code change needed for this specific
     * AC). Added here for traceability.
     */
    @Test
    void optimize_cancelledBeforeStart_appliesRank0_l2BaselinePreserved() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // With fieldCount=1 (from TEST_FIELD_COUNT stub), rowCount=2 → lapCount=2.
        // Cancelled before any rank evaluated → must apply rank=0 (L2 baseline).
        MappingResult mapping = buildMinimalMapping(phaseId, 2);
        when(mapperMock.map(phaseId)).thenReturn(mapping);

        CancellationToken token = CancellationToken.create();
        token.cancel(); // cancelled before any rank is evaluated

        JobHandle handle = new JobHandle(token, Instant.now());
        when(registryMock.getHandle(tournamentId)).thenReturn(Optional.of(handle));

        OptimizationResult result = subject.optimize(phaseId, tournamentId, token);

        assertThat(result).isNotNull();
        assertThat(result.cancelled()).isTrue();
        // Must apply rank=0 (L2 baseline = identity permutation) via SlotResultApplicator
        verify(applicatorMock).applyResult(eq(0L), anyInt(), eq(mapping));
    }

    // =========================================================================
    // AC-CANCELABLE-SERVICE-AUTHORED: natural completion returns non-cancelled result
    // =========================================================================

    /**
     * AC-CANCELABLE-SERVICE-AUTHORED: when the token is never cancelled, optimize() runs to
     * completion and returns a non-cancelled result.
     */
    @Test
    void optimize_naturalCompletion_returnsNonCancelledResult() {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        MappingResult mapping = buildMinimalMapping(phaseId, 2); // N=2 → 2 permutations, instant
        when(mapperMock.map(phaseId)).thenReturn(mapping);

        CancellationToken token = CancellationToken.create(); // never cancelled
        JobHandle handle = new JobHandle(token, Instant.now());
        when(registryMock.getHandle(tournamentId)).thenReturn(Optional.of(handle));

        OptimizationResult result = subject.optimize(phaseId, tournamentId, token);

        assertThat(result).isNotNull();
        assertThat(result.cancelled()).isFalse();
        verify(applicatorMock).applyResult(anyLong(), anyInt(), eq(mapping));
    }

    // =========================================================================
    // AC-COOPERATIVE-CANCELLATION-TESTED: cancel mid-computation terminates promptly
    // =========================================================================

    /**
     * AC-COOPERATIVE-CANCELLATION-TESTED: when cancel() is called mid-computation, optimize()
     * terminates and returns a cancelled result within a bounded wall-clock window (≤ 2 seconds).
     *
     * <p>Uses N=8 (8! = 40,320 permutations) with a cancel signal sent 10ms after start. Bounded
     * window is 2000ms (generous for CI). The cancel flag is checked between permutations, so worst
     * case is one full permutation evaluation after the flag is set — negligible at N=8.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void optimize_cancelMidComputation_terminatesWithinBound() throws Exception {
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        // N=8 rows → 8! = 40,320 permutations — enough to not complete in 10ms.
        // Each row uses a unique avatar pair, so the canonical form retains all 8 rows.
        MappingResult mapping = buildMinimalMapping(phaseId, 8); // 8 rows → N=8
        when(mapperMock.map(phaseId)).thenReturn(mapping);

        CancellationToken token = CancellationToken.create();
        JobHandle handle = new JobHandle(token, Instant.now());
        when(registryMock.getHandle(tournamentId)).thenReturn(Optional.of(handle));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<OptimizationResult> future =
                executor.submit(() -> subject.optimize(phaseId, tournamentId, token));

        // Send cancel signal after 10ms
        Thread.sleep(10);
        token.cancel();

        OptimizationResult result = future.get(2, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.cancelled()).isTrue();
        executor.shutdownNow();
    }

    // =========================================================================
    // Null-guard tests
    // =========================================================================

    @Test
    void optimize_nullPhaseId_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> subject.optimize(null, UUID.randomUUID(), CancellationToken.create()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optimize_nullTournamentId_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> subject.optimize(UUID.randomUUID(), null, CancellationToken.create()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optimize_nullToken_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> subject.optimize(UUID.randomUUID(), UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // Helper — build a minimal MappingResult fixture
    // =========================================================================

    /**
     * Builds a minimal {@link MappingResult} with {@code rowCount} rows, each row having 2 distinct
     * avatars. Suitable for passing to PacketSolver (real computation).
     *
     * <p>Uses the real {@link de.vvwt.slotopt.worker.types.StructuralFingerprint} to produce a
     * valid canonical form.
     */
    private static MappingResult buildMinimalMapping(UUID phaseId, int rowCount) {
        // Build PositionTuples for rowCount matches, each with 2 unique avatars
        // avatar dense IDs: 0..2*rowCount-1 (each match uses a fresh pair)
        List<de.vvwt.slotopt.worker.types.RawRow> rows = new java.util.ArrayList<>();
        List<Match> matches = new java.util.ArrayList<>();

        // Use sequential group/position for distinct avatars
        for (int r = 0; r < rowCount; r++) {
            de.vvwt.slotopt.worker.types.PositionTuple pt1 =
                    new de.vvwt.slotopt.worker.types.PositionTuple(r, 0);
            de.vvwt.slotopt.worker.types.PositionTuple pt2 =
                    new de.vvwt.slotopt.worker.types.PositionTuple(r, 1);
            rows.add(new de.vvwt.slotopt.worker.types.RawRow(List.of(pt1, pt2)));
            Match m = new Match();
            m.setId(UUID.randomUUID());
            m.setPhaseId(phaseId);
            matches.add(m);
        }

        int auditPhaseId = Math.abs(phaseId.hashCode());
        RawPhaseDef raw = new RawPhaseDef(auditPhaseId, rowCount, rows);

        de.vvwt.slotopt.worker.types.TransformResult tr =
                de.vvwt.slotopt.worker.types.StructuralFingerprint.transform(raw);
        CanonicalPhaseDef canonical = tr.canonical();

        // Build denseIdsByRawRow
        java.util.Map<de.vvwt.slotopt.worker.types.PositionTuple, Integer> denseMap =
                DefaultPhaseToRawPhaseDefMapper.buildDenseIdMapping(raw);
        int[][] denseIdsByRawRow = new int[rowCount][];
        for (int r = 0; r < rowCount; r++) {
            de.vvwt.slotopt.worker.types.PositionTuple pt1 =
                    new de.vvwt.slotopt.worker.types.PositionTuple(r, 0);
            de.vvwt.slotopt.worker.types.PositionTuple pt2 =
                    new de.vvwt.slotopt.worker.types.PositionTuple(r, 1);
            denseIdsByRawRow[r] = new int[] {denseMap.get(pt1), denseMap.get(pt2)};
        }

        return new MappingResult(
                raw, canonical, canonical.avatarCount(), matches, denseIdsByRawRow);
    }
}
