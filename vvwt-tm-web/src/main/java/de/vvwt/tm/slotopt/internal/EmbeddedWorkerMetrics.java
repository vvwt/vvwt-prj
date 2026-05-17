// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.EmbeddedWorker;
import de.vvwt.tm.slotopt.EmbeddedWorkerMetricsRegistrar;
import de.vvwt.tm.slotopt.EmbeddedWorkerState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers Micrometer metrics for the embedded slot-optimization worker (E63S05).
 *
 * <p>Registers three metrics:
 *
 * <ul>
 *   <li>{@code worker.state} — numeric gauge: 0=STOPPED, 1=RUNNING, 2=PAUSED_BY_HOST_ACTIVITY,
 *       3=PAUSED_BY_OPERATOR, 4=ERROR (AC-TEST-METRICS-EXPOSED, fixed encoding)
 *   <li>{@code worker.packets.completed.total} — total packets successfully processed
 *   <li>{@code worker.packets.failed.total} — total packets that failed (ComputeStepException)
 * </ul>
 *
 * <p>When the embedded worker is absent (flag-disabled), all gauges report 0 (STOPPED state, zero
 * counters) — never absent or an error (AC-ERR-METRICS-WHEN-DISABLED).
 *
 * <p>Implements {@link EmbeddedWorkerMetricsRegistrar} to satisfy DEC-58/DEC-72 Clause A-ext: the
 * {@code @Bean} factory method in {@code EmbeddedWorkerMetricsConfiguration} returns the public
 * interface, not this concrete class.
 *
 * <p>Story: E63S05 AC-TEST-METRICS-EXPOSED.
 */
class EmbeddedWorkerMetrics implements EmbeddedWorkerMetricsRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedWorkerMetrics.class);

    private static final String METRIC_STATE = "worker.state";
    private static final String METRIC_PACKETS_COMPLETED = "worker.packets.completed.total";
    private static final String METRIC_PACKETS_FAILED = "worker.packets.failed.total";

    /**
     * Constructs and registers the metrics.
     *
     * @param registry the Micrometer meter registry
     * @param workerOpt the embedded worker bean (absent when flag-disabled)
     */
    EmbeddedWorkerMetrics(MeterRegistry registry, Optional<EmbeddedWorker> workerOpt) {
        if (workerOpt.isPresent()) {
            EmbeddedWorker worker = workerOpt.get();
            Gauge.builder(METRIC_STATE, worker, w -> w.getState().numericCode())
                    .description(
                            "Embedded worker state: 0=STOPPED, 1=RUNNING,"
                                    + " 2=PAUSED_BY_HOST_ACTIVITY, 3=PAUSED_BY_OPERATOR, 4=ERROR")
                    .register(registry);
            Gauge.builder(METRIC_PACKETS_COMPLETED, worker, w -> (double) w.getPacketsCompleted())
                    .description("Total packets successfully processed by the embedded worker")
                    .register(registry);
            Gauge.builder(METRIC_PACKETS_FAILED, worker, w -> (double) w.getPacketsFailed())
                    .description("Total packets that failed in the embedded worker")
                    .register(registry);
            LOG.info(
                    "EmbeddedWorkerMetrics: registered metrics {}, {}, {}",
                    METRIC_STATE,
                    METRIC_PACKETS_COMPLETED,
                    METRIC_PACKETS_FAILED);
        } else {
            // AC-ERR-METRICS-WHEN-DISABLED: worker absent → report STOPPED (0) and zero counters
            Gauge.builder(METRIC_STATE, () -> (double) EmbeddedWorkerState.STOPPED.numericCode())
                    .description("Embedded worker state: 0=STOPPED (worker not enabled at boot)")
                    .register(registry);
            Gauge.builder(METRIC_PACKETS_COMPLETED, () -> 0.0)
                    .description("Total packets successfully processed by the embedded worker")
                    .register(registry);
            Gauge.builder(METRIC_PACKETS_FAILED, () -> 0.0)
                    .description("Total packets that failed in the embedded worker")
                    .register(registry);
            LOG.info(
                    "EmbeddedWorkerMetrics: embedded worker not enabled — metrics report"
                            + " STOPPED/zero");
        }
    }
}
