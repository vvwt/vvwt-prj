// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import de.vvwt.slotopt.worker.identity.WorkerKeyCorruptException;
import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.identity.internal.DefaultWorkerKeyManager;
import de.vvwt.slotopt.worker.runtime.ComputeStep;
import de.vvwt.slotopt.worker.runtime.DispatcherClient;
import de.vvwt.slotopt.worker.runtime.OutagePolicy;
import de.vvwt.slotopt.worker.runtime.ResultSigner;
import de.vvwt.slotopt.worker.runtime.internal.DefaultComputeStep;
import de.vvwt.slotopt.worker.runtime.internal.DefaultDispatcherClient;
import de.vvwt.slotopt.worker.runtime.internal.DefaultResultSigner;
import de.vvwt.tm.slotopt.EmbeddedWorker;
import de.vvwt.tm.slotopt.HostActivityProbe;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the TM embedded slot-optimization worker (E63S03, E63S04).
 *
 * <p>Activated only when {@code tm.slotopt.embedded-worker.enabled=true}. When the property is
 * absent or {@code false}, no bean is registered, no keypair is generated, and no dispatcher
 * request is made (AC-GOV-OPT-IN-CONFIG, AC-TEST-OFF-BY-DEFAULT).
 *
 * <h2>DEC-11 boundary</h2>
 *
 * <p>This class MUST NOT import any class from {@code vvwt-slotopt-dispatcher}. The embedded worker
 * uses {@code vvwt-slotopt-worker-runtime} (Spring-Boot-free shared library) for all dispatcher
 * communication. The Maven Enforcer rule in {@code vvwt-tm-web/pom.xml} makes this build-checked.
 *
 * <h2>DEC-68</h2>
 *
 * <p>The embedded worker keypair derives its default path from {@code
 * ${tm.data.dir}/embedded-worker-keys/} — a separate sub-directory from the Leg-2 submitter's
 * {@code ${tm.data.dir}/slotopt-keys/}. Override via {@code TM_SLOTOPT_EMBEDDED_WORKER_KEY_DIR}.
 *
 * <h2>E63S04 host-protection wiring</h2>
 *
 * <p>The {@link HostActivityProbe} bean (provided by {@link DefaultHostActivityProbe}) is injected
 * via the {@code embeddedWorker} factory method. The {@link InterPacketThrottle} is constructed
 * from the {@code tm.slotopt.embedded-worker.cpu-max-ratio} property.
 *
 * @see EmbeddedWorker
 * @see DefaultEmbeddedWorker
 */
@Configuration
@ConditionalOnProperty(name = "tm.slotopt.embedded-worker.enabled", havingValue = "true")
class EmbeddedWorkerConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedWorkerConfiguration.class);

    /**
     * Creates the {@link EmbeddedWorker} bean, wiring together:
     *
     * <ul>
     *   <li>{@link WorkerKeyManager} — loads or generates the embedded worker's OWN Ed25519 keypair
     *       (distinct from the Leg-2 submitter keypair — different key directory).
     *   <li>{@link DispatcherClient} — hand-rolled HTTP client from vvwt-slotopt-worker-runtime
     *       (DEC-11: no compile dependency on vvwt-slotopt-dispatcher).
     *   <li>{@link ResultSigner} — signs computation results with the embedded worker's private
     *       key.
     *   <li>{@link ComputeStep} — pull-solve-sign-submit per iteration.
     *   <li>{@link OutagePolicy} — embedded form: logs and returns normally (backoff + resume).
     *   <li>{@link HostActivityProbe} — injected Spring bean (E63S04): the only host-coupling
     *       channel (DEC-64/C-5), tracking live-scoring state via phase-status events.
     *   <li>{@link InterPacketThrottle} — constructed from config (E63S04): bounds averaged CPU
     *       share between packets.
     * </ul>
     *
     * <p>The returned bean is typed as {@link EmbeddedWorker} (the public interface per
     * DEC-58/DEC-72 Clause A-ext @Bean mandate).
     *
     * @param hostActivityProbe the Spring-managed probe bean (auto-wired from context)
     * @throws IOException if key directory cannot be created or accessed
     * @throws WorkerKeyCorruptException if existing key files are corrupt (operator must intervene)
     */
    @Bean
    EmbeddedWorker embeddedWorker(
            HostActivityProbe hostActivityProbe,
            @Value("${tm.slotopt.embedded-worker.key-dir:" + "${tm.data.dir}/embedded-worker-keys}")
                    String keyDir,
            @Value("${tm.slotopt.embedded-worker.dispatcher-url:}") String dispatcherUrl,
            @Value("${tm.slotopt.embedded-worker.poll-interval-ms:5000}") long pollIntervalMs,
            @Value("${tm.slotopt.embedded-worker.backoff-max-ms:30000}") long backoffMaxMs,
            @Value("${tm.slotopt.embedded-worker.http-timeout-ms:10000}") long httpTimeoutMs,
            @Value("${tm.slotopt.embedded-worker.cpu-max-ratio:0.25}") double cpuMaxRatio,
            @Value("${tm.slotopt.embedded-worker.live-scoring-pause-check-interval-ms:1000}")
                    long pauseCheckIntervalMs)
            throws IOException, WorkerKeyCorruptException {
        Path keyPath = Path.of(keyDir);
        LOG.info(
                "EmbeddedWorkerConfiguration: keypair dir={}, dispatcher={}, cpuMaxRatio={},"
                        + " pauseCheckIntervalMs={}",
                keyPath,
                dispatcherUrl.isBlank() ? "(none)" : dispatcherUrl,
                cpuMaxRatio,
                pauseCheckIntervalMs);

        WorkerKeyManager keyManager = new DefaultWorkerKeyManager(keyPath, "Ed25519", LOG);

        URI dispatcherUri =
                dispatcherUrl.isBlank()
                        ? URI.create("http://localhost:0")
                        : URI.create(dispatcherUrl);
        DispatcherClient dispatcherClient =
                new DefaultDispatcherClient(dispatcherUri, Duration.ofMillis(httpTimeoutMs));

        ResultSigner resultSigner = new DefaultResultSigner(keyManager, "Ed25519");

        ComputeStep computeStep = new DefaultComputeStep(dispatcherClient, resultSigner, "Ed25519");

        OutagePolicy outagePolicy = new EmbeddedOutagePolicy();

        // E63S04: inter-packet CPU throttle — maxSleepMs capped at backoffMaxMs
        InterPacketThrottle interPacketThrottle =
                new InterPacketThrottle(cpuMaxRatio, backoffMaxMs);

        return new DefaultEmbeddedWorker(
                computeStep,
                keyManager,
                dispatcherClient,
                pollIntervalMs,
                backoffMaxMs,
                outagePolicy,
                hostActivityProbe,
                interPacketThrottle,
                pauseCheckIntervalMs);
    }
}
