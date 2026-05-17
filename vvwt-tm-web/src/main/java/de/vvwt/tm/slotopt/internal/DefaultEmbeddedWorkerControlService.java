// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.EmbeddedWorker;
import de.vvwt.tm.slotopt.EmbeddedWorkerControlService;
import de.vvwt.tm.slotopt.EmbeddedWorkerState;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link EmbeddedWorkerControlService} (E63S05).
 *
 * <p>Delegates state transitions and counter queries to the injected {@link EmbeddedWorker}
 * instance. All operations are non-blocking (AC-ERR-CONTROL-INVOCATION-NON-BLOCKING).
 *
 * <p>DEC-58/DEC-72: wired by {@link EmbeddedWorkerConfiguration} which returns the {@link
 * EmbeddedWorkerControlService} interface from its {@code @Bean} method. This class is NOT
 * component-scanned.
 *
 * <p>Story: E63S05 AC-GOV-INTERFACE-MANDATE.
 */
class DefaultEmbeddedWorkerControlService implements EmbeddedWorkerControlService {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultEmbeddedWorkerControlService.class);

    private final EmbeddedWorker worker;

    /**
     * Constructs the control service.
     *
     * @param worker the embedded worker instance to control; must not be {@code null}
     */
    DefaultEmbeddedWorkerControlService(EmbeddedWorker worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    /** {@inheritDoc} */
    @Override
    public ControlResult pause() {
        EmbeddedWorkerState current = worker.getState();
        if (!isControllable(current)) {
            return conflict(current, "pause");
        }
        if (current == EmbeddedWorkerState.PAUSED_BY_OPERATOR) {
            LOG.debug("DefaultEmbeddedWorkerControlService.pause(): already paused — no-op");
            return new ControlResult(false, "Worker is already paused by operator", false, current);
        }
        worker.pause();
        EmbeddedWorkerState after = worker.getState();
        LOG.info("DefaultEmbeddedWorkerControlService: paused worker (state={})", after);
        return new ControlResult(true, "Worker paused", false, after);
    }

    /** {@inheritDoc} */
    @Override
    public ControlResult resume() {
        EmbeddedWorkerState current = worker.getState();
        if (!isControllable(current)) {
            return conflict(current, "resume");
        }
        if (current != EmbeddedWorkerState.PAUSED_BY_OPERATOR) {
            LOG.debug(
                    "DefaultEmbeddedWorkerControlService.resume(): state={} — not paused by"
                            + " operator, no-op",
                    current);
            return new ControlResult(
                    false, "Worker is not paused by operator — nothing to resume", false, current);
        }
        worker.resume();
        EmbeddedWorkerState after = worker.getState();
        LOG.info("DefaultEmbeddedWorkerControlService: resumed worker (state={})", after);
        return new ControlResult(true, "Worker resumed", false, after);
    }

    /** {@inheritDoc} */
    @Override
    public ControlResult disable() {
        EmbeddedWorkerState current = worker.getState();
        if (!isControllable(current)) {
            return conflict(current, "disable");
        }
        worker.disable();
        EmbeddedWorkerState after = worker.getState();
        LOG.info("DefaultEmbeddedWorkerControlService: disabled worker (state={})", after);
        return new ControlResult(true, "Worker disabled", false, after);
    }

    /** {@inheritDoc} */
    @Override
    public WorkerStatus getStatus() {
        EmbeddedWorkerState current = worker.getState();
        return new WorkerStatus(current, worker.getPacketsCompleted(), worker.getPacketsFailed());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the worker is in a state that accepts operator controls.
     *
     * <p>Controllable states: RUNNING, PAUSED_BY_HOST_ACTIVITY, PAUSED_BY_OPERATOR. The STOPPED and
     * ERROR states are not controllable — they indicate the worker is not running (flag-disabled,
     * runtime-disabled, or self-stopped after a failure).
     */
    private static boolean isControllable(EmbeddedWorkerState state) {
        return state == EmbeddedWorkerState.RUNNING
                || state == EmbeddedWorkerState.PAUSED_BY_HOST_ACTIVITY
                || state == EmbeddedWorkerState.PAUSED_BY_OPERATOR;
    }

    private static ControlResult conflict(EmbeddedWorkerState state, String operation) {
        String message =
                "Cannot "
                        + operation
                        + ": worker is not in a controllable state (state="
                        + state
                        + "). A host restart is required to re-enable the worker.";
        return new ControlResult(false, message, true, state);
    }
}
