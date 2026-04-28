package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.OptimizationAlreadyInProgressException;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultSlotOptimizationJobRegistry} — E27S02 AC-JOB-REGISTRY-AUTHORED,
 * AC-PER-TOURNAMENT-ISOLATION, AC-NATURAL-COMPLETION-CLEARS-REGISTRY.
 *
 * <p>TDD RED-first per DEC-22 Iron Law. Tests written before {@link
 * DefaultSlotOptimizationJobRegistry} existed.
 *
 * <p>Per DEC-36: this test class is in the same package ({@code slotopt.internal}) as the subject,
 * so white-box reference to {@link DefaultSlotOptimizationJobRegistry} is permitted.
 *
 * @see DefaultSlotOptimizationJobRegistry
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 */
class DefaultSlotOptimizationJobRegistryTest {

    private SlotOptimizationJobRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultSlotOptimizationJobRegistry();
    }

    // =========================================================================
    // AC-JOB-REGISTRY-AUTHORED: basic register + retrieve
    // =========================================================================

    /** AC-JOB-REGISTRY-AUTHORED: register stores the handle and getHandle retrieves it. */
    @Test
    void register_thenGetHandle_returnsHandle() {
        UUID tournamentId = UUID.randomUUID();
        JobHandle handle = newHandle();

        registry.register(tournamentId, handle);

        assertThat(registry.getHandle(tournamentId)).isPresent().contains(handle);
    }

    /** AC-JOB-REGISTRY-AUTHORED: getHandle on unknown tournament returns empty. */
    @Test
    void getHandle_unknownTournament_returnsEmpty() {
        assertThat(registry.getHandle(UUID.randomUUID())).isEmpty();
    }

    // =========================================================================
    // AC-PER-TOURNAMENT-ISOLATION: cancel A doesn't affect B
    // =========================================================================

    /**
     * AC-PER-TOURNAMENT-ISOLATION: handle for tournament A is independent of tournament B.
     * Cancelling B's token does NOT affect A's token.
     */
    @Test
    void perTournamentIsolation_cancellingBDoesNotCancelA() {
        UUID tournamentA = UUID.randomUUID();
        UUID tournamentB = UUID.randomUUID();
        JobHandle handleA = newHandle();
        JobHandle handleB = newHandle();

        registry.register(tournamentA, handleA);
        registry.register(tournamentB, handleB);

        // Cancel B's token
        handleB.getCancellationToken().cancel();

        // A's token must be unaffected
        assertThat(handleA.getCancellationToken().isCancelled()).isFalse();
        assertThat(handleB.getCancellationToken().isCancelled()).isTrue();
    }

    /**
     * AC-PER-TOURNAMENT-ISOLATION: attempting to register a second job for the same tournament
     * throws OptimizationAlreadyInProgressException.
     */
    @Test
    void register_duplicateTournament_throwsOptimizationAlreadyInProgressException() {
        UUID tournamentId = UUID.randomUUID();
        registry.register(tournamentId, newHandle());

        assertThatThrownBy(() -> registry.register(tournamentId, newHandle()))
                .isInstanceOf(OptimizationAlreadyInProgressException.class)
                .hasMessageContaining(tournamentId.toString());
    }

    // =========================================================================
    // AC-NATURAL-COMPLETION-CLEARS-REGISTRY
    // =========================================================================

    /**
     * AC-NATURAL-COMPLETION-CLEARS-REGISTRY: complete() removes the handle; subsequent start
     * succeeds.
     */
    @Test
    void complete_clearsHandle_subsequentStartSucceeds() {
        UUID tournamentId = UUID.randomUUID();
        registry.register(tournamentId, newHandle());

        registry.complete(tournamentId);

        // Registry should be empty for this tournament
        assertThat(registry.getHandle(tournamentId)).isEmpty();

        // A new registration should succeed without exception
        registry.register(tournamentId, newHandle());
        assertThat(registry.getHandle(tournamentId)).isPresent();
    }

    /**
     * AC-NATURAL-COMPLETION-CLEARS-REGISTRY: complete() on unknown tournament is a no-op (no
     * exception).
     */
    @Test
    void complete_unknownTournament_isNoOp() {
        // Should not throw
        registry.complete(UUID.randomUUID());
    }

    // =========================================================================
    // Null-guard tests (DEC-22 Iron Law — interface contract)
    // =========================================================================

    @Test
    void register_nullTournamentId_throws() {
        assertThatThrownBy(() -> registry.register(null, newHandle()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void register_nullHandle_throws() {
        assertThatThrownBy(() -> registry.register(UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getHandle_nullTournamentId_throws() {
        assertThatThrownBy(() -> registry.getHandle(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void complete_nullTournamentId_throws() {
        assertThatThrownBy(() -> registry.complete(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static JobHandle newHandle() {
        return new JobHandle(CancellationToken.create(), Instant.now());
    }
}
