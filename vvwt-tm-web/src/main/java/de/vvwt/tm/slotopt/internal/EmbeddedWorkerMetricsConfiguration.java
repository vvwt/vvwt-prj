// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.EmbeddedWorker;
import de.vvwt.tm.slotopt.EmbeddedWorkerMetricsRegistrar;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for embedded-worker Micrometer metrics (E63S05).
 *
 * <p>This configuration is <strong>unconditional</strong> — it is always active, regardless of
 * whether the embedded worker is enabled (AC-ERR-METRICS-WHEN-DISABLED). When the worker bean is
 * absent (flag-disabled), the metrics report {@code 0} (STOPPED state, zero counters) rather than
 * being absent entirely.
 *
 * <p>DEC-58/DEC-72 Clause A-ext: the {@code @Bean} method returns {@link
 * EmbeddedWorkerMetricsRegistrar} (the public first-party interface), not the concrete {@link
 * EmbeddedWorkerMetrics} class.
 *
 * <p>Story: E63S05 AC-TEST-METRICS-EXPOSED, AC-ERR-METRICS-WHEN-DISABLED.
 */
@Configuration
class EmbeddedWorkerMetricsConfiguration {

    /**
     * Creates the metrics registrar bean.
     *
     * @param registry the Micrometer meter registry (always present in a Spring Boot app)
     * @param workerOpt the embedded worker bean (empty when {@code
     *     tm.slotopt.embedded-worker.enabled} is absent or {@code false})
     * @return the registrar that has already registered all three metrics
     */
    @Bean
    EmbeddedWorkerMetricsRegistrar embeddedWorkerMetrics(
            MeterRegistry registry, Optional<EmbeddedWorker> workerOpt) {
        return new EmbeddedWorkerMetrics(registry, workerOpt);
    }
}
