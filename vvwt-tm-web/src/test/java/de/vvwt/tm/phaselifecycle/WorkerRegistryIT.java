package de.vvwt.tm.phaselifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * RED-first integration tests for {@link WorkerRegistry} per DEC-22 Pattern B.
 *
 * <p>Uses {@code @SpringBootTest(NONE)} with the full application class per DEC-44 bounded-context
 * module IT convention. All tests use the {@link WorkerRegistry} public interface per DEC-36.
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-36 (cross-package typing), DEC-44
 * (@SpringBootTest(NONE, classes=TournamentManagerApplication.class)), DEC-64 D-3 (parallelism +
 * FIFO + tenant propagation).
 *
 * @since E55S03
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TournamentManagerApplication.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:workerit-${random.uuid};DB_CLOSE_DELAY=-1",
            "spring.flyway.enabled=true"
        })
class WorkerRegistryIT {

    @Autowired private WorkerRegistry workerRegistry;

    @Autowired private TenantContext tenantContext;

    /**
     * AC-TEST-PARALLEL-TOURNAMENT-EXECUTION-RED: two distinct tournaments each submit a 200ms
     * blocking task. Total elapsed time must be < 350ms (proving parallel execution). If serialized,
     * total would be ≥ 400ms.
     */
    @Test
    void differentTournamentWorkerExecuteInParallel() throws Exception {
        UUID tournamentA = UUID.randomUUID();
        UUID tournamentB = UUID.randomUUID();

        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch bothDone = new CountDownLatch(2);

        long start = System.nanoTime();

        workerRegistry
                .getOrCreate(tournamentA)
                .submit(
                        () -> {
                            bothStarted.countDown();
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                bothDone.countDown();
                            }
                        });

        workerRegistry
                .getOrCreate(tournamentB)
                .submit(
                        () -> {
                            bothStarted.countDown();
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                bothDone.countDown();
                            }
                        });

        assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(bothDone.await(2, TimeUnit.SECONDS)).isTrue();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // Parallel execution: total elapsed < 350ms (serial would be ≥ 400ms)
        assertThat(elapsedMs)
                .as("Expected parallel execution (< 350ms) but was: %d ms", elapsedMs)
                .isLessThan(350L);
    }

    /**
     * AC-TEST-FIFO-WITHIN-TOURNAMENT-RED: a single tournament submits 5 tasks each writing a
     * sequence number to a shared list. The list ordering matches submission order (FIFO within
     * single-thread executor).
     */
    @Test
    void singleTournamentWorkerExecutesTasksInFifoOrder() throws Exception {
        UUID tournamentId = UUID.randomUUID();
        List<Integer> order = new ArrayList<>();
        CountDownLatch allDone = new CountDownLatch(5);

        for (int i = 0; i < 5; i++) {
            final int seqNum = i;
            workerRegistry
                    .getOrCreate(tournamentId)
                    .submit(
                            () -> {
                                order.add(seqNum);
                                allDone.countDown();
                            });
        }

        assertThat(allDone.await(5, TimeUnit.SECONDS)).isTrue();

        // Tasks must complete in submission order (FIFO single-thread executor)
        assertThat(order).containsExactly(0, 1, 2, 3, 4);
    }

    /**
     * AC-TEST-TENANT-CONTEXT-PROPAGATION-RED: submit a task that reads {@link
     * TenantContext#current()}; assert the returned tenantId matches the submitting thread's tenant.
     */
    @Test
    @SuppressWarnings("try") // scope variable used only for AutoCloseable.close() side-effect
    void submittedTaskInheritsTenantContextFromSubmittingThread() throws Exception {
        UUID tournamentId = UUID.randomUUID();
        UUID expectedTenantId = UUID.randomUUID();

        AtomicReference<UUID> capturedTenantId = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        // Bind tenant context on the submitting (test) thread
        try (TenantContext.Scope scope = tenantContext.bind(expectedTenantId)) {
            Future<?> f =
                    workerRegistry
                            .getOrCreate(tournamentId)
                            .submit(
                                    () -> {
                                        try {
                                            capturedTenantId.set(tenantContext.current());
                                        } catch (Exception e) {
                                            error.set(e);
                                        } finally {
                                            done.countDown();
                                        }
                                    });
            f.get(5, TimeUnit.SECONDS);
        }

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        assertThat(capturedTenantId.get())
                .as("Worker thread should have the same tenant as the submitting thread")
                .isEqualTo(expectedTenantId);
    }
}
