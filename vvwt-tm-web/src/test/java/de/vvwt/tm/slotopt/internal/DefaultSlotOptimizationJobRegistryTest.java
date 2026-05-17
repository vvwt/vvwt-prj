// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.OptimizationAlreadyInProgressException;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit tests for {@link DefaultSlotOptimizationJobRegistry} — E27S02 AC-JOB-REGISTRY-AUTHORED,
 * AC-PER-TOURNAMENT-ISOLATION, AC-NATURAL-COMPLETION-CLEARS-REGISTRY.
 *
 * <p>E55S06 update (DEC-64 D-6): {@link #getHandle(UUID)} is now DB-primary. Tests use a mocked
 * {@link JdbcTemplate} that simulates the DB RUNNING-row check:
 *
 * <ul>
 *   <li>Use {@code jdbcReturnsRunning(tournamentId)} to simulate a RUNNING row in DB.
 *   <li>Use {@code jdbcReturnsNoRunning(tournamentId)} to simulate no RUNNING row.
 * </ul>
 *
 * <p>Per DEC-36: this test class is in the same package ({@code slotopt.internal}) as the subject,
 * so white-box reference to {@link DefaultSlotOptimizationJobRegistry} is permitted.
 *
 * @see DefaultSlotOptimizationJobRegistry
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-64.md">DEC-64 D-6</a>
 */
class DefaultSlotOptimizationJobRegistryTest {

    private JdbcTemplate jdbcTemplate;
    private SlotOptimizationJobRegistry registry;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        // Default: no RUNNING row in DB
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object.class)))
                .thenReturn(0);
        registry = new DefaultSlotOptimizationJobRegistry(jdbcTemplate);
    }

    // =========================================================================
    // AC-JOB-REGISTRY-AUTHORED: basic register + retrieve
    // =========================================================================

    /**
     * AC-JOB-REGISTRY-AUTHORED: register stores the handle; getHandle() returns it when DB reports
     * a RUNNING row (DEC-64 D-6 DB-primary: DB is authoritative source).
     */
    @Test
    void register_thenGetHandle_returnsHandle_whenDbReportsRunning() {
        UUID tournamentId = UUID.randomUUID();
        JobHandle handle = newHandle();
        registry.register(tournamentId, handle);

        // Simulate DB reporting a RUNNING row for this tournament
        jdbcReturnsRunning(tournamentId);

        assertThat(registry.getHandle(tournamentId)).isPresent().contains(handle);
    }

    /**
     * AC-JOB-REGISTRY-AUTHORED (DEC-64 D-6): if DB reports no RUNNING row, getHandle() returns
     * empty — even if a handle is registered in memory.
     */
    @Test
    void getHandle_noRunningRowInDb_returnsEmpty_evenIfInMemoryHandleExists() {
        UUID tournamentId = UUID.randomUUID();
        registry.register(tournamentId, newHandle());

        // DB reports no RUNNING row (default mock setup)
        assertThat(registry.getHandle(tournamentId))
                .as(
                        "getHandle() must return empty when DB reports no RUNNING row"
                                + " (DEC-64 D-6 DB-primary)")
                .isEmpty();
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

        // Simulate both tournaments having RUNNING rows
        jdbcReturnsRunning(tournamentA);
        jdbcReturnsRunning(tournamentB);

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
     * AC-NATURAL-COMPLETION-CLEARS-REGISTRY: complete() removes the handle; subsequent getHandle()
     * returns empty (DB also reports no running row after complete).
     */
    @Test
    void complete_clearsHandle_subsequentGetHandleReturnsEmpty() {
        UUID tournamentId = UUID.randomUUID();
        registry.register(tournamentId, newHandle());

        registry.complete(tournamentId);

        // DB still returns 0 running (default) — handle was cleared from memory
        assertThat(registry.getHandle(tournamentId)).isEmpty();
    }

    /** AC-NATURAL-COMPLETION-CLEARS-REGISTRY: after complete(), a new registration succeeds. */
    @Test
    void complete_thenRegisterAgain_succeeds() {
        UUID tournamentId = UUID.randomUUID();
        registry.register(tournamentId, newHandle());
        registry.complete(tournamentId);

        // A new registration should succeed without exception
        JobHandle newHandle = newHandle();
        registry.register(tournamentId, newHandle);

        // Simulate DB reporting a RUNNING row for this tournament
        jdbcReturnsRunning(tournamentId);

        assertThat(registry.getHandle(tournamentId)).isPresent().contains(newHandle);
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

    /** Configure mock JDBC to return a RUNNING row count of 1 for the given tournament. */
    @SuppressWarnings("unchecked")
    private void jdbcReturnsRunning(UUID tournamentId) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(tournamentId)))
                .thenReturn(1);
    }
}
