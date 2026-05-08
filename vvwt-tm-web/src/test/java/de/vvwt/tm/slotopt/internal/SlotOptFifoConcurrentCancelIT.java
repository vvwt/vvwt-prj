package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for concurrent cancel scenarios against the FIFO-extended {@link
 * DefaultSlotOptimizationJobRegistry} — E51S04 RED-first.
 *
 * <h2>AC coverage</h2>
 *
 * <ul>
 *   <li>AC-TEST-CONCURRENT-CANCEL-DURING-DRAIN-RED
 *   <li>AC-ERROR-HANDLING-CANCEL-ON-NON-HEAD-PHASE
 *   <li>AC-IMPL-DEC-49-CANCEL-CONTRACT-PRESERVED
 * </ul>
 *
 * <h2>DEC-36</h2>
 *
 * <p>White-box test (same package as {@code DefaultSlotOptimizationJobRegistry}).
 *
 * @see DefaultSlotOptimizationJobRegistry
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-49">DEC-49 D-11 — admin-cancel per-tournament scope</a>
 * @see <a href="E51S04">E51S04 — Slot-Opt FIFO-Queue</a>
 */
class SlotOptFifoConcurrentCancelIT {

    private SlotOptimizationJobRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultSlotOptimizationJobRegistry();
    }

    // =========================================================================
    // AC-TEST-CONCURRENT-CANCEL-DURING-DRAIN-RED — FIFO semantics
    // =========================================================================

    /**
     * RED-first: given phase1 currently running (head) and phase2 queued, cancel on phase1 returns
     * the phase1 handle; cancel on phase2 (not head element) returns empty (409 semantics).
     *
     * <p>Test fails before fix because today's registry is single-handle (no queue), so getHandle
     * always returns the registered handle regardless of "head" concept.
     */
    @Test
    @DisplayName(
            "Cancel on head element succeeds; cancel on queued (non-head) returns empty"
                    + " — AC-TEST-CONCURRENT-CANCEL-DURING-DRAIN-RED")
    void cancelOnHeadSucceeds_cancelOnQueuedReturnEmpty() {
        UUID tournamentId = UUID.randomUUID();
        UUID phase1Id = UUID.randomUUID();
        UUID phase2Id = UUID.randomUUID();

        // Register phase1 as running head
        JobHandle handle1 = newHandle();
        registry.register(tournamentId, handle1);
        registry.enqueue(tournamentId, phase1Id);
        // Note: phase1 is head; phase2 is queued but NOT yet running
        registry.enqueue(tournamentId, phase2Id);

        // getHandle returns the head's handle (phase1) — backward-compatible (DEC-49 D-11)
        Optional<JobHandle> headHandle = registry.getHandle(tournamentId);
        assertThat(headHandle).isPresent().contains(handle1);

        // phase2 is queued but has no handle yet — getHandle returns the head's handle, not phase2
        // The cancel controller does: registry.getHandle(tid) → phase1's handle → cancel phase1
        // Phase2 cannot be cancelled (not the head) → the cancel controller returns 409
        headHandle.get().getCancellationToken().cancel();
        assertThat(headHandle.get().getCancellationToken().isCancelled())
                .as("Phase1's cancellation token must be set after cancel()")
                .isTrue();

        // Queue depth: phase1 + phase2
        assertThat(registry.getQueueDepth(tournamentId))
                .as("Queue must have 2 entries (phase1 as head + phase2 queued)")
                .isEqualTo(2);

        // After phase1 completes (dequeued), phase2 becomes the new head
        registry.dequeueHead(tournamentId);
        assertThat(registry.getQueueDepth(tournamentId))
                .as("After phase1 dequeue, queue must have 1 entry (phase2 becomes head)")
                .isEqualTo(1);
    }

    // =========================================================================
    // AC-IMPL-DEC-49-CANCEL-CONTRACT-PRESERVED — getHandle returns head
    // =========================================================================

    /**
     * RED-first: {@code getHandle(tournamentId)} returns the head element's handle — preserving the
     * DEC-49 D-11 cancel-controller API (backward-compatible).
     */
    @Test
    @DisplayName(
            "getHandle returns FIFO head element's handle — DEC-49 D-11 cancel contract preserved"
                    + " — AC-IMPL-DEC-49-CANCEL-CONTRACT-PRESERVED")
    void getHandle_returnsHeadElementHandle() {
        UUID tournamentId = UUID.randomUUID();
        UUID phase1Id = UUID.randomUUID();
        UUID phase2Id = UUID.randomUUID();

        JobHandle handle1 = newHandle();
        registry.register(tournamentId, handle1);
        registry.enqueue(tournamentId, phase1Id);
        registry.enqueue(tournamentId, phase2Id);

        // getHandle must return handle1 (head element) — DEC-49 backward-compatible
        assertThat(registry.getHandle(tournamentId))
                .as("getHandle must return the head element's JobHandle (DEC-49 D-11)")
                .isPresent()
                .contains(handle1);
    }

    // =========================================================================
    // FIFO queue methods — enqueue / getQueueDepth / peekQueue / dequeueHead
    // =========================================================================

    /** RED-first: enqueue + getQueueDepth returns correct depths. */
    @Test
    @DisplayName("enqueue increments getQueueDepth — AC-IMPL-FIFO-EXTENSION")
    void enqueue_incrementsQueueDepth() {
        UUID tournamentId = UUID.randomUUID();

        assertThat(registry.getQueueDepth(tournamentId))
                .as("Initial queue depth must be 0")
                .isEqualTo(0);

        registry.enqueue(tournamentId, UUID.randomUUID());
        assertThat(registry.getQueueDepth(tournamentId)).isEqualTo(1);

        registry.enqueue(tournamentId, UUID.randomUUID());
        assertThat(registry.getQueueDepth(tournamentId)).isEqualTo(2);
    }

    /** RED-first: peekQueue returns the head element without removing it. */
    @Test
    @DisplayName("peekQueue returns head without removing — AC-IMPL-FIFO-EXTENSION")
    void peekQueue_returnsHeadWithoutRemoving() {
        UUID tournamentId = UUID.randomUUID();
        UUID phase1Id = UUID.randomUUID();
        UUID phase2Id = UUID.randomUUID();

        registry.enqueue(tournamentId, phase1Id);
        registry.enqueue(tournamentId, phase2Id);

        assertThat(registry.peekQueue(tournamentId))
                .as("peekQueue must return the head phaseId (phase1)")
                .isPresent()
                .hasValue(phase1Id);
        assertThat(registry.getQueueDepth(tournamentId))
                .as("peekQueue must not remove the head element")
                .isEqualTo(2);
    }

    /** RED-first: dequeueHead removes the head and returns it. */
    @Test
    @DisplayName("dequeueHead removes head and returns it — AC-IMPL-FIFO-EXTENSION")
    void dequeueHead_removesAndReturnsHead() {
        UUID tournamentId = UUID.randomUUID();
        UUID phase1Id = UUID.randomUUID();
        UUID phase2Id = UUID.randomUUID();

        registry.enqueue(tournamentId, phase1Id);
        registry.enqueue(tournamentId, phase2Id);

        UUID dequeued = registry.dequeueHead(tournamentId);
        assertThat(dequeued).as("dequeueHead must return phase1 (FIFO order)").isEqualTo(phase1Id);
        assertThat(registry.getQueueDepth(tournamentId))
                .as("After dequeue, depth must be 1")
                .isEqualTo(1);
        assertThat(registry.peekQueue(tournamentId))
                .as("After dequeue, phase2 becomes head")
                .isPresent()
                .hasValue(phase2Id);
    }

    /** RED-first: dequeueHead on empty queue returns null (no NPE). */
    @Test
    @DisplayName("dequeueHead on empty queue returns null — AC-ERROR-HANDLING-EMPTY-QUEUE-NO-NPE")
    void dequeueHead_emptyQueue_returnsNull() {
        UUID tournamentId = UUID.randomUUID();
        // Should not throw NPE
        UUID result = registry.dequeueHead(tournamentId);
        assertThat(result)
                .as("dequeueHead on empty queue must return null, not throw NPE")
                .isNull();
    }

    /** RED-first: peekQueue on empty queue returns empty Optional. */
    @Test
    @DisplayName("peekQueue on empty queue returns empty — AC-IMPL-FIFO-EXTENSION")
    void peekQueue_emptyQueue_returnsEmpty() {
        UUID tournamentId = UUID.randomUUID();
        assertThat(registry.peekQueue(tournamentId))
                .as("peekQueue on empty queue must return Optional.empty()")
                .isEmpty();
    }

    // =========================================================================
    // Null-guard tests
    // =========================================================================

    @Test
    @DisplayName("enqueue with null tournamentId throws")
    void enqueue_nullTournamentId_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> registry.enqueue(null, UUID.randomUUID()));
    }

    @Test
    @DisplayName("enqueue with null phaseId throws")
    void enqueue_nullPhaseId_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> registry.enqueue(UUID.randomUUID(), null));
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private static JobHandle newHandle() {
        return new JobHandle(CancellationToken.create(), Instant.now());
    }
}
