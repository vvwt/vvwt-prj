package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.WorkerRegistry;
import de.vvwt.tm.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Per-tournament {@link ExecutorService} registry (DEC-64 D-3, DEC-35, DEC-58).
 *
 * <h2>Lifecycle (DEC-64 D-3)</h2>
 *
 * <ul>
 *   <li><b>Lazy-create:</b> {@link #getOrCreate(UUID)} creates a new {@code
 *       Executors.newSingleThreadExecutor()} on first call for a tournament. Two consecutive calls
 *       for the same tournament ID return the <em>same</em> {@link ExecutorService} instance
 *       (reference equality) until the worker is evicted or shut down.
 *   <li><b>Idle-timeout:</b> a per-registry {@link ScheduledExecutorService} polls at {@code
 *       tm.phaselifecycle.worker-idle-poll-seconds} (default 60 s). Workers idle for ≥ {@code
 *       tm.phaselifecycle.worker-idle-timeout-seconds} (default 300 s) are shut down and removed
 *       from the registry. Restart on next {@code getOrCreate()}.
 *   <li><b>JVM-shutdown:</b> {@link #shutdownAll()} is called via {@link PreDestroy}. Each worker
 *       receives {@code shutdown()} and is awaited up to {@code
 *       tm.phaselifecycle.worker-shutdown-timeout-seconds} (default 30 s). Workers that do not
 *       terminate within the timeout are logged at WARN.
 * </ul>
 *
 * <h2>Reference identity contract</h2>
 *
 * <p>The registry stores one {@link DecoratingExecutorWrapper} per tournament. {@code getOrCreate}
 * returns the cached wrapper on subsequent calls — satisfying the reference-equality contract in
 * AC-TEST-LAZY-CREATE-IDENTITY-RED. After idle eviction, the wrapper is removed; the next {@code
 * getOrCreate} creates a new wrapper backed by a fresh underlying executor.
 *
 * <h2>Tenant-context propagation</h2>
 *
 * <p>{@link TenantContext} is {@link ThreadLocal}-backed and does NOT propagate automatically
 * across thread boundaries (see {@code TenantContext} Javadoc, Wave-1 documentation obligation).
 * Each submitted {@link Runnable} is wrapped in a decorator that captures the tenant ID from the
 * submitting thread and re-binds it on the worker thread — identical to the {@code
 * TenantContextTaskDecorator} pattern introduced in {@code MatchGenAsyncConfig} (E51S03).
 *
 * <p>If no tenant is bound on the submitting thread (e.g., during startup probes), propagation is
 * skipped (logged at DEBUG).
 *
 * <h2>Named threads</h2>
 *
 * <p>Each worker's {@link ThreadFactory} names threads {@code phaselifecycle-{shortId}-{n}}, where
 * {@code shortId} is the first 8 hex characters of the tournament UUID.
 *
 * <h2>Task-exception handling (AC-ERROR-HANDLING-TASK-EXCEPTION-LOGGED)</h2>
 *
 * <p>Uncaught exceptions thrown by submitted tasks are caught and logged at ERROR with the
 * tournament context before the exception propagates to the {@link java.util.concurrent.Future}.
 * The worker {@link ExecutorService} remains alive for subsequent submissions.
 *
 * <h2>Shutdown-during-execution (AC-ERROR-HANDLING-SHUTDOWN-DURING-EXECUTION)</h2>
 *
 * <p>{@code shutdown()} initiates an orderly shutdown — in-flight tasks complete. Any
 * {@code @Transactional} (DEC-37 Clause B) boundaries inside the task commit or roll back
 * naturally; {@code shutdown()} does not interrupt them.
 *
 * @see WorkerRegistry
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-64.md">DEC-64 D-3</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-58.md">DEC-58</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-35.md">DEC-35</a>
 * @since E55S03
 */
@Service("workerRegistry")
public class DefaultWorkerRegistry implements WorkerRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultWorkerRegistry.class);

    // ── Per-tournament wrapper map (wrapper cached for reference-equality contract) ──────────────
    private final ConcurrentHashMap<UUID, DecoratingExecutorWrapper> registry =
            new ConcurrentHashMap<>();

    // ── Last-submission timestamp per tournament (nanos) ────────────────────
    private final ConcurrentHashMap<UUID, Long> lastSubmittedNanos = new ConcurrentHashMap<>();

    // ── Dependencies ────────────────────────────────────────────────────────
    private final TenantContext tenantContext;
    private final long idleTimeoutSeconds;
    private final long shutdownTimeoutSeconds;
    private final long idlePollSeconds;

    // ── Internal poller for idle-timeout eviction ────────────────────────────
    private final ScheduledExecutorService idlePoller;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Spring-wired constructor (DEC-58 — all new beans have constructor injection).
     *
     * @param tenantContext for tenant-context propagation to worker threads
     * @param idleTimeoutSeconds configurable idle timeout (default 300 s)
     * @param shutdownTimeoutSeconds configurable JVM-shutdown await timeout (default 30 s)
     * @param idlePollSeconds configurable idle-poller interval (default 60 s)
     */
    public DefaultWorkerRegistry(
            TenantContext tenantContext,
            @Value("${tm.phaselifecycle.worker-idle-timeout-seconds:300}") long idleTimeoutSeconds,
            @Value("${tm.phaselifecycle.worker-shutdown-timeout-seconds:30}")
                    long shutdownTimeoutSeconds,
            @Value("${tm.phaselifecycle.worker-idle-poll-seconds:60}") long idlePollSeconds) {
        if (tenantContext == null) {
            throw new IllegalArgumentException("tenantContext must not be null");
        }
        this.tenantContext = tenantContext;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.idlePollSeconds = idlePollSeconds;
        this.idlePoller =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "phaselifecycle-idle-poller");
                            t.setDaemon(true);
                            return t;
                        });
    }

    /**
     * Starts the idle-poller after Spring has completed dependency injection.
     *
     * <p>Using {@link PostConstruct} rather than inline-in-constructor ensures the poller fires
     * only once the full application context is ready, avoiding races during startup.
     */
    @PostConstruct
    void startIdlePoller() {
        idlePoller.scheduleWithFixedDelay(
                this::evictIdleWorkers, idlePollSeconds, idlePollSeconds, TimeUnit.SECONDS);
        LOG.info(
                "[phaselifecycle] WorkerRegistry idle-poller started: idle-timeout={}s, poll={}s"
                        + " (E55S03, DEC-64 D-3)",
                idleTimeoutSeconds,
                idlePollSeconds);
    }

    // ─── WorkerRegistry ───────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Lazy-creates a cached {@link DecoratingExecutorWrapper} backed by a {@code
     * Executors.newSingleThreadExecutor()} for the tournament on first call. Thread-safe under
     * concurrent calls via {@link ConcurrentHashMap#computeIfAbsent}. Returns the <em>same cached
     * wrapper instance</em> on subsequent calls (reference equality) until the worker is evicted or
     * shut down.
     *
     * <p>If the cached wrapper's underlying executor has been shut down (e.g., idle eviction), a
     * fresh wrapper backed by a new executor is created atomically.
     *
     * @param tournamentId must not be {@code null}
     * @return the per-tournament executor wrapper; never {@code null}
     */
    @Override
    public ExecutorService getOrCreate(UUID tournamentId) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        DecoratingExecutorWrapper wrapper =
                registry.computeIfAbsent(tournamentId, id -> buildWrapper(id));
        // If the underlying executor was shut down (e.g., idle eviction), replace atomically
        if (wrapper.rawExecutor.isShutdown()) {
            DecoratingExecutorWrapper fresh = buildWrapper(tournamentId);
            if (registry.replace(tournamentId, wrapper, fresh)) {
                wrapper = fresh;
            } else {
                // Another thread replaced it; discard our redundant instance
                fresh.rawExecutor.shutdownNow();
                wrapper = registry.get(tournamentId);
                if (wrapper == null) {
                    return getOrCreate(tournamentId);
                }
            }
        }
        updateLastSubmitted(tournamentId);
        return wrapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Removes the worker for the given tournament and calls {@code shutdown()} on its underlying
     * executor. Running jobs will complete; no new jobs are accepted.
     */
    @Override
    public void shutdownWorker(UUID tournamentId) {
        DecoratingExecutorWrapper wrapper = registry.remove(tournamentId);
        if (wrapper != null && !wrapper.rawExecutor.isShutdown()) {
            wrapper.rawExecutor.shutdown();
            LOG.debug(
                    "[phaselifecycle] shutdownWorker: tournament {} — executor shut down"
                            + " (E55S03, DEC-64 D-3)",
                    tournamentId);
        }
        lastSubmittedNanos.remove(tournamentId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Iterates ALL registered workers, calls {@code shutdown()} on each underlying executor,
     * then awaits termination up to {@code tm.phaselifecycle.worker-shutdown-timeout-seconds} per
     * worker. Workers that do not terminate within the timeout are logged at WARN. Also stops the
     * idle-poller.
     *
     * <p>After {@code shutdownAll()}, subsequent calls to {@link #getOrCreate(UUID)} transparently
     * re-create a new executor (restart-transparent contract per DEC-64 D-3).
     */
    @Override
    @PreDestroy
    public void shutdownAll() {
        // Stop the idle poller first so it does not race with the shutdown loop
        idlePoller.shutdownNow();

        LOG.info(
                "[phaselifecycle] shutdownAll() — shutting down {} worker(s) (E55S03, DEC-64 D-3)",
                registry.size());

        registry.forEach(
                (tournamentId, wrapper) -> {
                    registry.remove(tournamentId, wrapper);
                    ExecutorService exec = wrapper.rawExecutor;
                    if (!exec.isShutdown()) {
                        exec.shutdown();
                    }
                    try {
                        if (!exec.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                            LOG.warn(
                                    "[phaselifecycle] shutdownAll: worker for tournament {} did not"
                                            + " terminate within {}s — incomplete jobs will be"
                                            + " reclaimed by next JVM instance per DEC-64 D-7",
                                    tournamentId,
                                    shutdownTimeoutSeconds);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.warn(
                                "[phaselifecycle] shutdownAll: interrupted while awaiting"
                                        + " termination of worker for tournament {}",
                                tournamentId);
                    }
                });
        lastSubmittedNanos.clear();
        LOG.info("[phaselifecycle] shutdownAll() complete (E55S03, DEC-64 D-3)");
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Builds a new {@link DecoratingExecutorWrapper} for the tournament with a named-thread {@link
     * ThreadFactory}.
     *
     * <p>Thread name pattern: {@code phaselifecycle-{shortId}-{n}} where {@code shortId} is the
     * first 8 hex chars of the tournament UUID.
     */
    private DecoratingExecutorWrapper buildWrapper(UUID tournamentId) {
        String shortId = tournamentId.toString().replace("-", "").substring(0, 8);
        AtomicInteger threadNum = new AtomicInteger(0);
        ThreadFactory factory =
                r -> {
                    Thread t =
                            new Thread(
                                    r,
                                    "phaselifecycle-"
                                            + shortId
                                            + "-"
                                            + threadNum.getAndIncrement());
                    t.setDaemon(false);
                    return t;
                };
        ExecutorService raw = Executors.newSingleThreadExecutor(factory);
        return new DecoratingExecutorWrapper(tournamentId, raw);
    }

    /** Updates the last-submitted timestamp for idle-eviction tracking. */
    private void updateLastSubmitted(UUID tournamentId) {
        lastSubmittedNanos.put(tournamentId, System.nanoTime());
    }

    /**
     * Idle-poller runnable: iterates {@link #lastSubmittedNanos}, shuts down and removes workers
     * that have been idle for ≥ {@link #idleTimeoutSeconds} seconds.
     */
    private void evictIdleWorkers() {
        long nowNanos = System.nanoTime();
        long idleNanos = TimeUnit.SECONDS.toNanos(idleTimeoutSeconds);
        lastSubmittedNanos.forEach(
                (tournamentId, lastNanos) -> {
                    if (nowNanos - lastNanos >= idleNanos) {
                        DecoratingExecutorWrapper wrapper = registry.get(tournamentId);
                        if (wrapper != null && !wrapper.rawExecutor.isShutdown()) {
                            Long current = lastSubmittedNanos.get(tournamentId);
                            if (current != null && nowNanos - current >= idleNanos) {
                                registry.remove(tournamentId, wrapper);
                                lastSubmittedNanos.remove(tournamentId);
                                wrapper.rawExecutor.shutdown();
                                LOG.debug(
                                        "[phaselifecycle] idle eviction: tournament {} worker shut"
                                                + " down after {}s idle (E55S03, DEC-64 D-3)",
                                        tournamentId,
                                        idleTimeoutSeconds);
                            }
                        }
                    }
                });
    }

    // ─── Inner class: DecoratingExecutorWrapper ───────────────────────────────

    /**
     * A minimal {@link ExecutorService} wrapper that:
     *
     * <ol>
     *   <li>Updates the last-submitted timestamp on each {@link #execute} call.
     *   <li>Wraps the runnable with tenant-context propagation (capture on submitting thread,
     *       re-bind on worker thread).
     *   <li>Wraps the runnable with exception-logging (ERROR with tournament context).
     * </ol>
     *
     * <p>Delegation pattern: all {@link ExecutorService} lifecycle methods are forwarded to the
     * {@link #rawExecutor}. Only {@link #execute} is augmented; {@code submit()} methods from
     * {@link AbstractExecutorService} delegate to {@code execute()}, picking up the augmentation
     * transparently.
     *
     * <p>The {@link #rawExecutor} field is package-private for access by the enclosing registry
     * (idle eviction + shutdown paths). Callers outside the registry interact only via the {@link
     * ExecutorService} interface.
     */
    final class DecoratingExecutorWrapper extends AbstractExecutorService {

        private final UUID tournamentId;
        final ExecutorService rawExecutor; // accessed by enclosing registry for lifecycle mgmt

        DecoratingExecutorWrapper(UUID tournamentId, ExecutorService rawExecutor) {
            this.tournamentId = tournamentId;
            this.rawExecutor = rawExecutor;
        }

        // ── Augmented entry point ─────────────────────────────────────────────

        @Override
        public void execute(Runnable command) {
            updateLastSubmitted(tournamentId);
            rawExecutor.execute(decorated(command));
        }

        // AbstractExecutorService.submit() delegates to execute() — augmentation flows through.

        // ── Delegation for lifecycle methods ─────────────────────────────────

        @Override
        public void shutdown() {
            rawExecutor.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return rawExecutor.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return rawExecutor.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return rawExecutor.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return rawExecutor.awaitTermination(timeout, unit);
        }

        // ── Private decorator factory ─────────────────────────────────────────

        /**
         * Wraps {@code runnable} with tenant-context propagation and exception logging.
         *
         * <p>Tenant ID is captured on the <em>submitting thread</em> at decoration time. The
         * decorated runnable re-binds it on the <em>worker thread</em> before invoking the original
         * runnable — identical to the {@code TenantContextTaskDecorator} pattern (E51S03 / {@code
         * MatchGenAsyncConfig}).
         */
        @SuppressWarnings("try") // scope variable used only for AutoCloseable.close() side-effect
        private Runnable decorated(Runnable runnable) {
            final UUID capturedTenantId;
            try {
                capturedTenantId = tenantContext.current();
            } catch (IllegalStateException e) {
                LOG.debug(
                        "[phaselifecycle] DecoratingExecutorWrapper: no tenant bound on submitting"
                                + " thread for tournament {} — propagation skipped (E55S03)",
                        tournamentId);
                return withExceptionLogging(runnable);
            }

            return () -> {
                try (TenantContext.Scope scope = tenantContext.bind(capturedTenantId)) {
                    withExceptionLogging(runnable).run();
                }
            };
        }

        /** Wraps {@code runnable} so that uncaught exceptions are logged at ERROR. */
        private Runnable withExceptionLogging(Runnable runnable) {
            return () -> {
                try {
                    runnable.run();
                } catch (RuntimeException | Error ex) {
                    LOG.error(
                            "[phaselifecycle] Uncaught exception in worker task for tournament {}"
                                    + " — worker remains alive (E55S03, DEC-64 D-3)",
                            tournamentId,
                            ex);
                    throw ex;
                }
            };
        }
    }
}
