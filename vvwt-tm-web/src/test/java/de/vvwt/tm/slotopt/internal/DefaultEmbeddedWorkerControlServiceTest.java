// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.slotopt.EmbeddedWorker;
import de.vvwt.tm.slotopt.EmbeddedWorkerControlService;
import de.vvwt.tm.slotopt.EmbeddedWorkerControlService.ControlResult;
import de.vvwt.tm.slotopt.EmbeddedWorkerControlService.WorkerStatus;
import de.vvwt.tm.slotopt.EmbeddedWorkerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultEmbeddedWorkerControlService} (E63S05).
 *
 * <p>RED-first per DEC-22 (AC-GOV-RED-FIRST).
 *
 * <p>Stories: E63S05 AC-TEST-CONTROL-PAUSE-RESUME-DISABLE, AC-ERR-CONTROL-ON-DISABLED-WORKER,
 * AC-ERR-REDUNDANT-CONTROL-IDEMPOTENT.
 */
@ExtendWith(MockitoExtension.class)
class DefaultEmbeddedWorkerControlServiceTest {

    private EmbeddedWorkerControlService controlService(EmbeddedWorker worker) {
        return new DefaultEmbeddedWorkerControlService(worker);
    }

    private EmbeddedWorker mockWorker(EmbeddedWorkerState state) {
        EmbeddedWorker worker = mock(EmbeddedWorker.class);
        when(worker.getState()).thenReturn(state);
        return worker;
    }

    // =========================================================================
    // pause()
    // =========================================================================

    @Test
    void pauseFromRunning_transitionsToOperatorPaused() {
        EmbeddedWorker worker = mockWorker(EmbeddedWorkerState.RUNNING);
        // After pause() is called, getState() returns PAUSED_BY_OPERATOR
        when(worker.getState())
                .thenReturn(EmbeddedWorkerState.RUNNING)
                .thenReturn(EmbeddedWorkerState.PAUSED_BY_OPERATOR);

        ControlResult result = controlService(worker).pause();

        assertThat(result.success()).isTrue();
        assertThat(result.conflict()).isFalse();
        assertThat(result.state()).isEqualTo(EmbeddedWorkerState.PAUSED_BY_OPERATOR);
    }

    @Test
    void pauseFromPausedByHostActivity_transitionsToOperatorPaused() {
        EmbeddedWorker worker = mock(EmbeddedWorker.class);
        when(worker.getState())
                .thenReturn(EmbeddedWorkerState.PAUSED_BY_HOST_ACTIVITY)
                .thenReturn(EmbeddedWorkerState.PAUSED_BY_OPERATOR);

        ControlResult result = controlService(worker).pause();

        assertThat(result.success()).isTrue();
        assertThat(result.conflict()).isFalse();
        assertThat(result.state()).isEqualTo(EmbeddedWorkerState.PAUSED_BY_OPERATOR);
    }

    @Test
    void pauseFromAlreadyPaused_isIdempotent() {
        EmbeddedWorker worker = mockWorker(EmbeddedWorkerState.PAUSED_BY_OPERATOR);

        ControlResult result = controlService(worker).pause();

        assertThat(result.success()).isFalse();
        assertThat(result.conflict()).isFalse();
        assertThat(result.state()).isEqualTo(EmbeddedWorkerState.PAUSED_BY_OPERATOR);
    }

    @Test
    void pauseFromStopped_returnsConflict() {
        EmbeddedWorker worker = mockWorker(EmbeddedWorkerState.STOPPED);

        ControlResult result = controlService(worker).pause();

        assertThat(result.success()).isFalse();
        assertThat(result.conflict()).isTrue();
        assertThat(result.state()).isEqualTo(EmbeddedWorkerState.STOPPED);
    }

    @Test
    void pauseFromError_returnsConflict() {
        EmbeddedWorker worker = mockWorker(EmbeddedWorkerState.ERROR);

        ControlResult result = controlService(worker).pause();

        assertThat(result.success()).isFalse();
        assertThat(result.conflict()).isTrue();
        assertThat(result.state()).isEqualTo(EmbeddedWorkerState.ERROR);
    }

    // =========================================================================
    // resume()
    // =========================================================================

    @Test
    void resumeFromPausedByOperator_transitionsToRunning() {
        EmbeddedWorker worker = mock(EmbeddedWorker.class);
        when(worker.getState())
                .thenReturn(EmbeddedWorkerState.PAUSED_BY_OPERATOR)
                .thenReturn(EmbeddedWorkerState.RUNNING);

        ControlResult result = controlService(worker).resume();

        assertThat(result.success()).isTrue();
        assertThat(result.conflict()).isFalse();
        assertThat(result.state()).isEqualTo(EmbeddedWorkerState.RUNNING);
    }

    @Test
    void resumeFromRunning_isIdempotent() {
        EmbeddedWorker worker = mockWorker(EmbeddedWorkerState.RUNNING);

        ControlResult result = controlService(worker).resume();

        assertThat(result.success()).isFalse();
        assertThat(result.conflict()).isFalse();
        assertThat(result.state()).isEqualTo(EmbeddedWorkerState.RUNNING);
    }

    @Test
    void resumeFromStopped_returnsConflict() {
        EmbeddedWorker worker = mockWorker(EmbeddedWorkerState.STOPPED);

        ControlResult result = controlService(worker).resume();

        assertThat(result.success()).isFalse();
        assertThat(result.conflict()).isTrue();
    }

    // =========================================================================
    // disable()
    // =========================================================================

    @Test
    void disableFromRunning_returnsSuccess() {
        EmbeddedWorker worker = mock(EmbeddedWorker.class);
        when(worker.getState())
                .thenReturn(EmbeddedWorkerState.RUNNING)
                .thenReturn(EmbeddedWorkerState.STOPPED);

        ControlResult result = controlService(worker).disable();

        assertThat(result.success()).isTrue();
        assertThat(result.conflict()).isFalse();
        assertThat(result.state()).isEqualTo(EmbeddedWorkerState.STOPPED);
    }

    @Test
    void disableFromStopped_returnsConflict() {
        EmbeddedWorker worker = mockWorker(EmbeddedWorkerState.STOPPED);

        ControlResult result = controlService(worker).disable();

        assertThat(result.success()).isFalse();
        assertThat(result.conflict()).isTrue();
    }

    // =========================================================================
    // getStatus()
    // =========================================================================

    @Test
    void getStatus_returnsCurrentStateAndCounters() {
        EmbeddedWorker worker = mock(EmbeddedWorker.class);
        when(worker.getState()).thenReturn(EmbeddedWorkerState.RUNNING);
        when(worker.getPacketsCompleted()).thenReturn(42L);
        when(worker.getPacketsFailed()).thenReturn(3L);

        WorkerStatus status = controlService(worker).getStatus();

        assertThat(status.state()).isEqualTo(EmbeddedWorkerState.RUNNING);
        assertThat(status.packetsCompleted()).isEqualTo(42L);
        assertThat(status.packetsFailed()).isEqualTo(3L);
    }

    @Test
    void getStatus_whenStopped_reportsStopped() {
        EmbeddedWorker worker = mock(EmbeddedWorker.class);
        when(worker.getState()).thenReturn(EmbeddedWorkerState.STOPPED);
        when(worker.getPacketsCompleted()).thenReturn(0L);
        when(worker.getPacketsFailed()).thenReturn(0L);

        WorkerStatus status = controlService(worker).getStatus();

        assertThat(status.state()).isEqualTo(EmbeddedWorkerState.STOPPED);
        assertThat(status.packetsCompleted()).isZero();
        assertThat(status.packetsFailed()).isZero();
    }
}
