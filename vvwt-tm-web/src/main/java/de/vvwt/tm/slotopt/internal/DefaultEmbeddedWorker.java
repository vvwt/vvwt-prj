// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.runtime.AnnouncedAlgorithmsResponse;
import de.vvwt.slotopt.worker.runtime.ComputeStep;
import de.vvwt.slotopt.worker.runtime.ComputeStepException;
import de.vvwt.slotopt.worker.runtime.ComputeStepResult;
import de.vvwt.slotopt.worker.runtime.DispatcherClient;
import de.vvwt.slotopt.worker.runtime.DispatcherException;
import de.vvwt.slotopt.worker.runtime.OutagePolicy;
import de.vvwt.slotopt.worker.runtime.RegisterKeyRequest;
import de.vvwt.slotopt.worker.runtime.RegisterKeyResponse;
import de.vvwt.tm.slotopt.EmbeddedWorker;
import de.vvwt.tm.slotopt.EmbeddedWorkerState;
import de.vvwt.tm.slotopt.HostActivityProbe;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link EmbeddedWorker}.
 *
 * <p>Wraps the shared compute-path library ({@link ComputeStep} from E63S01) and runs it on an
 * independent background thread. Key behaviours:
 *
 * <ul>
 *   <li><strong>Opt-in / OFF by default</strong> — controlled by the enclosing {@link
 *       EmbeddedWorkerConfiguration} ({@code @ConditionalOnProperty}). This class does not
 *       auto-start; {@link #start()} is called by the configuration after wiring.
 *   <li><strong>Own keypair</strong> — the injected {@link WorkerKeyManager} manages a keypair
 *       stored in a path distinct from the Leg-2 submitter's keypair (AC-SEC-OWN-DISTINCT-KEYPAIR,
 *       DEC-68).
 *   <li><strong>No JVM shutdown hook</strong> — unlike the standalone worker loop, this class DOES
 *       NOT register a JVM shutdown hook. Lifecycle is controlled externally via {@link #start()} /
 *       {@link #stop()} (AC-GOV-EMBEDDED-LIFECYCLE-NOT-CLI-LIFECYCLE).
 *   <li><strong>Outage survive-and-resume</strong> — on a dispatcher outage, the {@link
 *       OutagePolicy} is invoked (logs and returns normally for the embedded form); the loop then
 *       applies capped exponential backoff and retries (AC-TEST-OUTAGE-SURVIVE-AND-RESUME,
 *       AC-ERR-OUTAGE-BACKOFF-NO-BUSY-LOOP).
 *   <li><strong>Host-protection: live-scoring auto-pause</strong> (E63S04) — between packets, the
 *       worker checks {@link HostActivityProbe#isLiveScoringActive()}. When the probe returns
 *       {@code true}, the worker pauses (sleeps {@code pauseCheckIntervalMs}) and rechecks on an
 *       interval rather than pulling the next packet. The probe is the ONLY host-coupling channel
 *       (DEC-64/C-5). If the probe throws, the worker conservatively treats the host as active
 *       (AC-ERR-PROBE-FAILURE-IS-CONSERVATIVE).
 *   <li><strong>Host-protection: inter-packet CPU throttle</strong> (E63S04) — after each solved
 *       packet, the worker sleeps proportionally to keep its averaged CPU share within the
 *       configured ratio. This is {@link InterPacketThrottle} — NOT the standalone {@code
 *       CpuThrottle} (idle-poll pacing only).
 *   <li><strong>No host DB / Saga-Orchestrator coupling</strong> — this class has no direct
 *       dependency on any TM DAO or repository. The only host read is through the injected {@link
 *       HostActivityProbe} interface (AC-GOV-NO-HOST-DB-OR-ORCHESTRATOR-COUPLING, DEC-64).
 *   <li><strong>DEC-11</strong> — talks to the dispatcher over HTTP only via the injected {@link
 *       ComputeStep} and {@link DispatcherClient}; no compile dependency on {@code
 *       vvwt-slotopt-dispatcher}.
 * </ul>
 *
 * <h2>Between-packet sequence (E63S04)</h2>
 *
 * <ol>
 *   <li>Probe check: while {@code isLiveScoringActive()} returns {@code true} (or throws) AND
 *       shutdown is not requested → sleep {@code pauseCheckIntervalMs} and recheck.
 *   <li>Pull and solve (packet runs to completion uninterrupted — no mid-packet check).
 *   <li>If PACKET_PROCESSED or PACKET_SUPERSEDED: apply inter-packet throttle sleep (computed from
 *       solve time and {@code cpuMaxRatio}).
 *   <li>If NO_PACKET: apply the existing idle sleep ({@code pollIntervalMs}).
 * </ol>
 *
 * <h2>Thread lifecycle</h2>
 *
 * <ol>
 *   <li>{@link #start()} → creates and starts exactly one background thread named {@code
 *       vvwt-embedded-worker}.
 *   <li>The thread registers with the dispatcher (DEC-6/DEC-43), then enters the pull-solve-submit
 *       loop.
 *   <li>On {@link #stop()} → sets the shutdown flag, interrupts the thread; the thread finishes the
 *       current iteration (if any) and exits cleanly (no mid-packet termination).
 * </ol>
 *
 * <p>DEC-58/DEC-72: wired by {@link EmbeddedWorkerConfiguration} which returns the {@link
 * EmbeddedWorker} interface from its {@code @Bean} method — satisfying the @Bean-produced-service
 * interface mandate (Clause A-ext). This class is NOT component-scanned; it is instantiated
 * exclusively by the configuration class.
 *
 * <p>Story: E63S03 (base), E63S04 (host-protection additions), E63S05 (operator controls +
 * observability).
 */
class DefaultEmbeddedWorker implements EmbeddedWorker {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultEmbeddedWorker.class);

    /** Capped exponential backoff: initial 2s → 4s → 8s → 16s → 30s (cap). */
    private static final long BACKOFF_INITIAL_MS = 2_000L;

    private static final long BACKOFF_MULTIPLIER = 2;

    private final ComputeStep computeStep;
    private final WorkerKeyManager keyManager;
    private final DispatcherClient dispatcherClient;
    private final long pollIntervalMs;
    private final long backoffMaxMs;
    private final OutagePolicy outagePolicy;
    private final List<String> supportedAlgorithms;

    // E63S04 host-protection
    private final HostActivityProbe hostActivityProbe;
    private final InterPacketThrottle interPacketThrottle;
    private final long pauseCheckIntervalMs;

    /**
     * Volatile flag — set by {@link #stop()} to signal the loop to exit after current iteration.
     */
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    // E63S05: operator controls + observability state

    /** Current worker lifecycle state (E63S05). Updated atomically by the loop and controls. */
    private final AtomicReference<EmbeddedWorkerState> state =
            new AtomicReference<>(EmbeddedWorkerState.STOPPED);

    /**
     * Set by {@link #disable()} — prevents restart after run-time disable (E63S05). Once true, the
     * worker cannot be re-enabled without a host restart.
     */
    private final AtomicBoolean runtimeDisabled = new AtomicBoolean(false);

    /** Total packets successfully processed since last {@link #start()} (E63S05). */
    private final AtomicLong packetsCompleted = new AtomicLong(0);

    /** Total packets that failed (ComputeStepException) since last {@link #start()} (E63S05). */
    private final AtomicLong packetsFailed = new AtomicLong(0);

    /** The background worker thread; null before {@link #start()} or after termination. */
    private volatile Thread workerThread;

    /** Registered worker UUID from the dispatcher's registration response. */
    private volatile UUID workerId;

    /**
     * Constructs a new {@code DefaultEmbeddedWorker}.
     *
     * <p>Does NOT start the background thread or generate a keypair. Call {@link #start()} to
     * begin.
     *
     * @param computeStep shared compute-path library (pull-solve-sign-submit per iteration)
     * @param keyManager manages the embedded worker's OWN Ed25519 keypair
     * @param dispatcherClient HTTP client for registration (pull/submit handled by computeStep)
     * @param pollIntervalMs sleep duration (ms) between pull attempts when no packet is available
     * @param backoffMaxMs maximum backoff duration (ms) during a dispatcher outage
     * @param outagePolicy strategy for reacting to a dispatcher outage
     * @param hostActivityProbe probe for host live-scoring state (E63S04); the only host-coupling
     *     channel (DEC-64/C-5)
     * @param interPacketThrottle inter-packet CPU throttle (E63S04); bounds averaged CPU share
     * @param pauseCheckIntervalMs how often (ms) to recheck the probe while paused (E63S04)
     */
    DefaultEmbeddedWorker(
            ComputeStep computeStep,
            WorkerKeyManager keyManager,
            DispatcherClient dispatcherClient,
            long pollIntervalMs,
            long backoffMaxMs,
            OutagePolicy outagePolicy,
            HostActivityProbe hostActivityProbe,
            InterPacketThrottle interPacketThrottle,
            long pauseCheckIntervalMs) {
        this.computeStep = Objects.requireNonNull(computeStep, "computeStep");
        this.keyManager = Objects.requireNonNull(keyManager, "keyManager");
        this.dispatcherClient = Objects.requireNonNull(dispatcherClient, "dispatcherClient");
        this.pollIntervalMs = pollIntervalMs;
        this.backoffMaxMs = backoffMaxMs;
        this.outagePolicy = Objects.requireNonNull(outagePolicy, "outagePolicy");
        this.hostActivityProbe = Objects.requireNonNull(hostActivityProbe, "hostActivityProbe");
        this.interPacketThrottle =
                Objects.requireNonNull(interPacketThrottle, "interPacketThrottle");
        this.pauseCheckIntervalMs = pauseCheckIntervalMs;
        this.supportedAlgorithms = List.of(keyManager.algorithmId());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Starts the background worker thread. If already running, this call is a no-op. The thread
     * is named {@code vvwt-embedded-worker}.
     */
    @Override
    public synchronized void start() {
        if (runtimeDisabled.get()) {
            LOG.warn("DefaultEmbeddedWorker.start() called but worker is runtime-disabled — no-op");
            return;
        }
        if (workerThread != null && workerThread.isAlive()) {
            LOG.debug("DefaultEmbeddedWorker.start() called but worker is already running — no-op");
            return;
        }
        shutdownRequested.set(false);
        state.set(EmbeddedWorkerState.RUNNING);
        workerThread = new Thread(this::runLoop, "vvwt-embedded-worker");
        workerThread.setDaemon(true);
        workerThread.start();
        LOG.info("DefaultEmbeddedWorker: started background thread '{}'", workerThread.getName());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Signals shutdown and waits up to 10 s for the background thread to finish its current
     * iteration. After this call, {@link #isRunning()} returns {@code false}.
     */
    @Override
    public void stop() {
        shutdownRequested.set(true);
        Thread t = workerThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
            try {
                t.join(10_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        state.compareAndSet(EmbeddedWorkerState.RUNNING, EmbeddedWorkerState.STOPPED);
        state.compareAndSet(
                EmbeddedWorkerState.PAUSED_BY_HOST_ACTIVITY, EmbeddedWorkerState.STOPPED);
        state.compareAndSet(EmbeddedWorkerState.PAUSED_BY_OPERATOR, EmbeddedWorkerState.STOPPED);
        LOG.info("DefaultEmbeddedWorker: stopped");
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRunning() {
        Thread t = workerThread;
        return t != null && t.isAlive();
    }

    // -------------------------------------------------------------------------
    // E63S05: operator controls + observability
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public EmbeddedWorkerState getState() {
        return state.get();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Non-blocking: sets state to {@link EmbeddedWorkerState#PAUSED_BY_OPERATOR} if the worker
     * is in a controllable state (RUNNING or PAUSED_BY_HOST_ACTIVITY). The loop detects this on its
     * next iteration.
     */
    @Override
    public void pause() {
        EmbeddedWorkerState current = state.get();
        if (current == EmbeddedWorkerState.RUNNING
                || current == EmbeddedWorkerState.PAUSED_BY_HOST_ACTIVITY) {
            state.set(EmbeddedWorkerState.PAUSED_BY_OPERATOR);
            LOG.info("DefaultEmbeddedWorker: paused by operator");
        } else {
            LOG.debug("DefaultEmbeddedWorker.pause(): state={} — no transition applied", current);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Non-blocking: transitions the worker from {@link EmbeddedWorkerState#PAUSED_BY_OPERATOR}
     * to {@link EmbeddedWorkerState#RUNNING}. If already running, this is a no-op.
     */
    @Override
    public void resume() {
        if (state.compareAndSet(
                EmbeddedWorkerState.PAUSED_BY_OPERATOR, EmbeddedWorkerState.RUNNING)) {
            LOG.info("DefaultEmbeddedWorker: resumed by operator");
        } else {
            LOG.debug(
                    "DefaultEmbeddedWorker.resume(): state={} — no transition applied",
                    state.get());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Non-blocking: sets the runtime-disabled flag, then signals the worker thread to stop. The
     * flag prevents restart via {@link #start()}.
     */
    @Override
    public void disable() {
        runtimeDisabled.set(true);
        stop();
        state.set(EmbeddedWorkerState.STOPPED);
        LOG.info("DefaultEmbeddedWorker: runtime-disabled by operator");
    }

    /** {@inheritDoc} */
    @Override
    public long getPacketsCompleted() {
        return packetsCompleted.get();
    }

    /** {@inheritDoc} */
    @Override
    public long getPacketsFailed() {
        return packetsFailed.get();
    }

    // -------------------------------------------------------------------------
    // Internal loop
    // -------------------------------------------------------------------------

    /**
     * Main loop executed on the background thread.
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Register keypair with the dispatcher (DEC-6/DEC-43).
     *   <li>Enter the pull-solve-submit loop with host-protection (E63S04).
     *   <li>On outage: invoke outage policy (returns normally for embedded form) → back off →
     *       retry.
     *   <li>On shutdown: exit cleanly after the current iteration.
     *   <li>On persistent non-recoverable failure: log ERROR, exit (does not re-throw into host).
     * </ol>
     */
    private void runLoop() {
        // Step 1: Register with dispatcher
        try {
            registerWithDispatcher();
        } catch (DispatcherException e) {
            LOG.error(
                    "DefaultEmbeddedWorker: registration failed — dispatcher permanently rejected"
                            + " (HTTP {}). Worker stopping. Reason: {}",
                    e.getHttpStatus(),
                    e.getMessage());
            // AC-ERR-PERSISTENT-FAILURE-SELF-STOPS-NOT-CRASH: stop, don't crash host
            state.set(EmbeddedWorkerState.ERROR); // E63S05 observability
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("DefaultEmbeddedWorker: interrupted during registration — stopping");
            return;
        }

        // Step 2: Pull-solve-submit loop with host-protection
        long currentBackoffMs = BACKOFF_INITIAL_MS;
        while (!shutdownRequested.get()) {

            // E63S05: operator pause — check FIRST (takes priority over host-activity check).
            if (state.get() == EmbeddedWorkerState.PAUSED_BY_OPERATOR) {
                sleepUnlessShutdown(pauseCheckIntervalMs);
                continue;
            }

            // E63S04: live-scoring auto-pause — check BETWEEN packets, never mid-packet.
            // AC-TEST-AUTO-PAUSE-ON-LIVE-SCORING, AC-TEST-PAUSE-IS-BETWEEN-PACKETS
            if (isLiveScoringActiveConservative()) {
                state.compareAndSet(
                        EmbeddedWorkerState.RUNNING, EmbeddedWorkerState.PAUSED_BY_HOST_ACTIVITY);
                sleepUnlessShutdown(pauseCheckIntervalMs);
                continue;
            }
            // Probe returned false — clear host-activity pause if still set.
            state.compareAndSet(
                    EmbeddedWorkerState.PAUSED_BY_HOST_ACTIVITY, EmbeddedWorkerState.RUNNING);

            if (shutdownRequested.get()) {
                break;
            }

            try {
                long solveStartNs = System.nanoTime();
                ComputeStepResult result = computeStep.execute(workerId, supportedAlgorithms);
                long solveTimeNs = System.nanoTime() - solveStartNs;

                // Reset backoff after a successful iteration
                currentBackoffMs = BACKOFF_INITIAL_MS;

                if (result == ComputeStepResult.NO_PACKET) {
                    // AC-ERR-NO-PACKET-IDLES-GRACEFULLY: no packet → idle, no error
                    // No solve-time measured, no throttle sleep.
                    sleepUnlessShutdown(pollIntervalMs);
                } else {
                    // PACKET_PROCESSED or PACKET_SUPERSEDED: apply inter-packet CPU throttle.
                    // AC-TEST-CPU-THROTTLE-RATIO, AC-TEST-THROTTLE-IS-BETWEEN-PACKETS (E63S04)
                    packetsCompleted.incrementAndGet(); // E63S05 observability
                    long throttleSleepMs = interPacketThrottle.computeSleepMs(solveTimeNs);
                    if (throttleSleepMs > 0) {
                        LOG.trace(
                                "DefaultEmbeddedWorker: inter-packet throttle sleep {}ms"
                                        + " (solveTime={}ns)",
                                throttleSleepMs,
                                solveTimeNs);
                        sleepUnlessShutdown(throttleSleepMs);
                    }
                }

            } catch (ComputeStepException e) {
                packetsFailed.incrementAndGet(); // E63S05 observability
                // The embedded worker survives outages — invoke outage policy
                DispatcherException cause =
                        (e.getCause() instanceof DispatcherException de) ? de : null;
                if (cause != null) {
                    outagePolicy.onOutage(cause);
                } else {
                    LOG.warn(
                            "DefaultEmbeddedWorker: compute step failed (exit code {}) — backing"
                                    + " off. cause={}",
                            e.getExitCode(),
                            e.getMessage());
                }

                // AC-ERR-OUTAGE-BACKOFF-NO-BUSY-LOOP: capped exponential backoff
                sleepUnlessShutdown(currentBackoffMs);
                currentBackoffMs = Math.min(currentBackoffMs * BACKOFF_MULTIPLIER, backoffMaxMs);
            }
        }

        LOG.info("DefaultEmbeddedWorker: loop exited cleanly (shutdown requested)");
    }

    /**
     * Probes the host for live-scoring activity with conservative fail-safe semantics.
     *
     * <p>Returns {@code true} (treat as active/pause) if the probe throws or is otherwise
     * unavailable. This ensures a probe failure causes the worker to pause rather than charging
     * ahead and competing with TM's primary duties.
     *
     * <p>AC-ERR-PROBE-FAILURE-IS-CONSERVATIVE.
     *
     * @return {@code true} if live-scoring is active OR if the probe threw an exception
     */
    private boolean isLiveScoringActiveConservative() {
        try {
            return hostActivityProbe.isLiveScoringActive();
        } catch (Exception e) {
            LOG.warn(
                    "DefaultEmbeddedWorker: HostActivityProbe threw — treating host as active"
                            + " (fail-safe). cause={}",
                    e.getMessage());
            return true; // AC-ERR-PROBE-FAILURE-IS-CONSERVATIVE
        }
    }

    /**
     * Registers the embedded worker's public key with the dispatcher (DEC-6 / DEC-43).
     *
     * <p>Fetches the announced algorithms, selects the worker's configured algorithm, and sends a
     * {@code POST /api/register-key} request. The dispatcher's response provides the stable {@link
     * #workerId} used in all subsequent pull/submit requests.
     *
     * <p>AC-SEC-REGISTRATION-DEC6-HANDSHAKE: no team UUIDs or PII in the registration request.
     * AC-SEC-KEY-MATERIAL-NOT-LOGGED: private key is never logged.
     *
     * @throws DispatcherException if the dispatcher rejects registration (e.g., HTTP 410 —
     *     algorithm deprecated)
     * @throws InterruptedException if the thread is interrupted during registration
     */
    private void registerWithDispatcher() throws DispatcherException, InterruptedException {
        LOG.info("DefaultEmbeddedWorker: registering with dispatcher (DEC-6/DEC-43)...");

        // Fetch announced algorithms (DEC-43 D1)
        AnnouncedAlgorithmsResponse announcedAlgorithms;
        try {
            announcedAlgorithms = dispatcherClient.fetchAnnouncedAlgorithms();
        } catch (DispatcherException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("interrupted during fetchAnnouncedAlgorithms");
            }
            throw e;
        }

        // Verify our algorithm is announced (DEC-43 D2 free-choice, V1 = Ed25519)
        String algorithm = keyManager.algorithmId();
        boolean algorithmAnnounced =
                announcedAlgorithms.algorithms().stream()
                        .anyMatch(a -> algorithm.equals(a.algorithmId()));
        if (!algorithmAnnounced) {
            LOG.warn(
                    "DefaultEmbeddedWorker: dispatcher does not announce algorithm '{}'. "
                            + "Announced: {}. Proceeding with registration anyway.",
                    algorithm,
                    announcedAlgorithms.algorithms());
        }

        // Register public key (AC-SEC-REGISTRATION-DEC6-HANDSHAKE: no PII, no team UUIDs)
        byte[] publicKeyBytes = keyManager.getPublicKeyBytes();
        RegisterKeyRequest request =
                new RegisterKeyRequest(null, "general", algorithm, publicKeyBytes);

        RegisterKeyResponse response;
        try {
            response = dispatcherClient.registerKey(request);
        } catch (DispatcherException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("interrupted during registerKey");
            }
            throw e;
        }

        this.workerId = response.workerId();
        LOG.info("DefaultEmbeddedWorker: registered successfully. workerId={}", this.workerId);
        // AC-SEC-KEY-MATERIAL-NOT-LOGGED: private key is never printed; only workerId logged.
    }

    /**
     * Sleeps for the given duration unless shutdown has been requested or the thread is
     * interrupted.
     *
     * @param ms sleep duration in milliseconds
     */
    private void sleepUnlessShutdown(long ms) {
        if (shutdownRequested.get() || ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
