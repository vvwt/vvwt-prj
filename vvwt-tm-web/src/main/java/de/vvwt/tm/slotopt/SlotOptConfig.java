package de.vvwt.tm.slotopt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spring configuration for slot-optimization infrastructure beans (E04S04).
 *
 * <p>Provides the {@code slotOptExecutor} {@link ExecutorService} bean used by
 * {@link DirectSlotOptimizationClient} for multi-threaded micro-segment search.
 * The executor is a managed singleton to avoid thread pool churn on repeated
 * {@code optimize()} calls (AC3 Notes).
 *
 * <h2>Lifecycle</h2>
 * <p>Spring calls {@link ExecutorService#shutdown()} on context close via the
 * {@link java.io.Closeable} lifecycle. The executor is destroyed gracefully when
 * the application exits or when the Spring test context is torn down.
 *
 * @see DirectSlotOptimizationClient
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E04S04.story.md">Story E04S04</a>
 */
@Configuration
public class SlotOptConfig {

    /**
     * Fixed-size thread pool for micro-segment slot-optimization (AC3).
     *
     * <p>Thread count is configurable via {@code tm.slotopt.thread-count}. Defaults to
     * {@code Runtime.getRuntime().availableProcessors()} — the number of logical CPUs
     * available to the JVM. For a 4-core host this yields 4 threads; for an 8-core host
     * this yields 8 threads.
     *
     * @param threadCount number of threads in the pool; defaults to available processors
     * @return a fixed-size {@link ExecutorService} named {@code slotOptExecutor}
     */
    @Bean(name = "slotOptExecutor", destroyMethod = "shutdown")
    public ExecutorService slotOptExecutor(
            @Value("${tm.slotopt.thread-count:#{T(java.lang.Runtime).getRuntime().availableProcessors()}}") int threadCount) {
        return Executors.newFixedThreadPool(threadCount);
    }
}
