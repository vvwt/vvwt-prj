package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.vvwt.tm.phaselifecycle.WorkerRegistry;
import de.vvwt.tm.tenant.TenantContext;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * RED-first unit tests for {@link DefaultWorkerRegistry} per DEC-22 Pattern B.
 *
 * <p>Tests in this class are in the same package as {@code DefaultWorkerRegistry}'s interface
 * ({@code de.vvwt.tm.phaselifecycle}) — they reference {@link WorkerRegistry} (the public
 * interface) per DEC-36 cross-package test typing rule. Because this test class is in the
 * module-root package (not in {@code .internal}), it MUST use the interface type for field
 * declarations and assertions, not the concrete class.
 *
 * <p>Exception: constructor invocation uses {@link DefaultWorkerRegistry} directly because unit
 * tests wire the object under test explicitly (no Spring context). The implementation constructor
 * is package-private from the test's perspective (same module-root package). This is a DEC-36
 * white-box exception.
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-36 (cross-package typing), DEC-64 D-3
 * (per-tournament single-thread executor lifecycle).
 *
 * @since E55S03
 */
class WorkerRegistryUnitTest {

    private TenantContext tenantContext;
    private DefaultWorkerRegistry registry;

    @BeforeEach
    void setUp() {
        tenantContext = Mockito.mock(TenantContext.class);
        // No tenant bound → TenantContext.current() throws ISE (no-tenant path)
        Mockito.when(tenantContext.current())
                .thenThrow(new IllegalStateException("no tenant bound in unit test"));
        registry =
                new DefaultWorkerRegistry(
                        tenantContext,
                        /* idleTimeoutSeconds= */ 300L,
                        /* shutdownTimeoutSeconds= */ 30L,
                        /* idlePollSeconds= */ 3600L); // very long poll — don't fire in tests
        registry.startIdlePoller(); // must be called manually in unit tests (no Spring context)
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        registry.shutdownAll();
    }

    /**
     * AC-TEST-LAZY-CREATE-IDENTITY-RED: two getOrCreate(tournamentA) calls return the SAME
     * instance; getOrCreate(tournamentB) returns a DIFFERENT instance.
     */
    @Test
    void lazyCreateReturnsSameInstanceForSameTournament() {
        UUID tournamentA = UUID.randomUUID();
        UUID tournamentB = UUID.randomUUID();

        WorkerRegistry reg = registry;

        ExecutorService esA1 = reg.getOrCreate(tournamentA);
        ExecutorService esA2 = reg.getOrCreate(tournamentA);
        ExecutorService esB = reg.getOrCreate(tournamentB);

        assertThat(esA1).isNotNull();
        assertThat(esA1).isSameAs(esA2); // reference equality — same instance
        assertThat(esA1).isNotSameAs(esB); // different tournament → different instance
    }

    /**
     * AC-TEST-CONCURRENT-CREATE-SINGLE-INSTANCE-RED: 10 concurrent threads call
     * getOrCreate(sameTournamentId); only ONE ExecutorService instance is created.
     */
    @Test
    void concurrentGetOrCreateProducesSingleInstance() throws InterruptedException {
        UUID tournamentId = UUID.randomUUID();
        int threadCount = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        Set<ExecutorService> instances = Collections.newSetFromMap(new ConcurrentHashMap<>());

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(
                    () -> {
                        try {
                            start.await();
                            instances.add(registry.getOrCreate(tournamentId));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
        }
        start.countDown(); // release all threads simultaneously
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // Only ONE ExecutorService instance should have been created across all 10 threads
        assertThat(instances).hasSize(1);
    }

    /**
     * AC-TEST-IDLE-TIMEOUT-SHUTDOWN-RED: with reduced idle timeout (1 s) and poll interval (1 s),
     * after submitting a quick task and waiting 3 s, the worker is removed from the registry.
     * Subsequent getOrCreate() creates a NEW instance (reference inequality).
     *
     * <p>This test uses a separate registry instance with accelerated timeouts.
     */
    @Test
    void idleTimeoutShutdownRemovesWorkerAfterIdle() throws Exception {
        // Accelerated registry: 1 s idle timeout, 1 s poll interval
        DefaultWorkerRegistry fastRegistry =
                new DefaultWorkerRegistry(
                        tenantContext,
                        /* idleTimeoutSeconds= */ 1L,
                        /* shutdownTimeoutSeconds= */ 5L,
                        /* idlePollSeconds= */ 1L);
        fastRegistry.startIdlePoller(); // must be called manually in unit tests (no Spring context)
        try {
            UUID tournamentId = UUID.randomUUID();

            ExecutorService first = fastRegistry.getOrCreate(tournamentId);
            // Submit a quick task and wait for it to complete
            first.submit(() -> {}).get(2, TimeUnit.SECONDS);

            // Wait long enough for the idle poller to fire (idle timeout = 1s, poll = 1s)
            // Sleep 3s to ensure at least one poll cycle fires after the 1-s idle window expires
            Thread.sleep(3_000);

            // The worker should have been shut down and removed from the registry
            ExecutorService second = fastRegistry.getOrCreate(tournamentId);
            assertThat(second).isNotSameAs(first); // new instance, not the shut-down one
            assertThat(first.isShutdown()).isTrue();
        } finally {
            fastRegistry.shutdownAll();
        }
    }

    /**
     * AC-TEST-JVM-SHUTDOWN-AWAIT-TERMINATION-RED: submitting a long-running task, then calling
     * shutdownAll(), verifies that the task completes (or the call returns within the timeout) and
     * that post-shutdown getOrCreate() creates a NEW instance (transparent restart).
     *
     * <p>Delivery contract choice (per AC note "bifurcated contract"): post-shutdown {@code
     * getOrCreate()} transparently re-creates a new {@link ExecutorService} for the tournament.
     * This is the "restart-transparent" path — consistent with DEC-64 D-3 worker lifecycle
     * (idle-timeout restart semantics).
     */
    @Test
    void shutdownAllAwaitsInFlightTaskAndTransparentlyAllowsRestart() throws Exception {
        UUID tournamentId = UUID.randomUUID();
        ExecutorService worker = registry.getOrCreate(tournamentId);

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskCanProceed = new CountDownLatch(1);

        // Submit a task that signals when it starts, then waits to be released
        worker.submit(
                () -> {
                    taskStarted.countDown();
                    try {
                        taskCanProceed.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        // Wait for task to start
        assertThat(taskStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // Release the task so awaitTermination can complete
        taskCanProceed.countDown();

        // Calling shutdownAll() should block until the task completes (within timeout)
        assertThatCode(() -> registry.shutdownAll()).doesNotThrowAnyException();

        // After shutdownAll(), getOrCreate() should create a NEW instance (transparent restart)
        ExecutorService newWorker = registry.getOrCreate(tournamentId);
        assertThat(newWorker).isNotSameAs(worker);
        assertThat(newWorker.isShutdown()).isFalse(); // new instance is alive
    }
}
