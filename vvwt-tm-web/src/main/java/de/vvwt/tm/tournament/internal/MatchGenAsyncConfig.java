package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tenant.TenantContext;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Activates Spring async execution and registers a custom {@link AsyncUncaughtExceptionHandler} for
 * the Match-Gen + Slot-Opt background-job pipeline (DEC-55 D-3, E51S03).
 *
 * <h2>Why a custom {@code AsyncUncaughtExceptionHandler}?</h2>
 *
 * <p>Spring's {@code @Async} infrastructure silently discards unchecked exceptions thrown by void
 * async methods — they are wrapped in a {@link java.util.concurrent.CompletableFuture} that is
 * never observed. The {@link de.vvwt.tm.tournament.internal.MatchGenJobListener} handles its own
 * exceptions via an internal {@code try/catch} and writes {@code phase.last_job_state='failed'}
 * before re-throwing. This handler serves as the last-resort safety net for any uncaught exception
 * that escapes the listener's try/catch (e.g., a runtime error in error-handling itself), ensuring
 * the failure is always observable in the application log at ERROR level per
 * AC-ERROR-HANDLING-LISTENER-EXCEPTION-NO-SILENT-LOSS.
 *
 * <h2>Executor choice (AC-IMPL-ASYNC-EXECUTOR)</h2>
 *
 * <p>Uses a dedicated {@code matchGenTaskExecutor} bean backed by a {@link ThreadPoolTaskExecutor}.
 * The executor is decorated with a {@link TenantContextTaskDecorator} that propagates the {@link
 * TenantContext} from the publishing thread to the worker thread (Wave-1 async cross-thread pattern
 * per {@code TenantContext} Javadoc). The executor respects DEC-15 LAN-safe operation and DEC-3 (no
 * new external dependencies). Promotion to a larger thread-pool or dedicated queue is deferred to
 * an observability-triggered future story.
 *
 * <h2>Tenant propagation (Wave-1 pattern)</h2>
 *
 * <p>{@code ThreadLocal}-based {@link TenantContext} is NOT automatically propagated across
 * {@code @Async} thread boundaries. The {@link TenantContextTaskDecorator} captures the tenant ID
 * from the submitting thread and re-binds it on the worker thread before the task executes. This
 * ensures repository calls inside the async listener route to the correct tenant DataSource.
 *
 * @see MatchGenJobListener
 * @see <a href="DEC-55">DEC-55 D-3 — Background-Job-Pipeline (events-only)</a>
 * @see <a href="E51S03">E51S03 — Background-Job-Pipeline foundation</a>
 */
@Configuration
@EnableAsync
class MatchGenAsyncConfig implements AsyncConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(MatchGenAsyncConfig.class);

    private final TenantContext tenantContext;

    MatchGenAsyncConfig(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    /**
     * Returns the task executor used for {@code @Async} methods in this configuration class.
     *
     * <p>Decorated with {@link TenantContextTaskDecorator} to propagate {@link TenantContext}
     * across the thread boundary.
     *
     * @return the executor
     */
    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("matchgen-async-");
        executor.setTaskDecorator(new TenantContextTaskDecorator(tenantContext));
        executor.initialize();
        return executor;
    }

    /**
     * Returns the uncaught-exception handler for async void methods (last-resort safety net).
     *
     * <p>Logs at ERROR level with method name and arguments — ensures failure is always observable
     * in the application log per AC-ERROR-HANDLING-LISTENER-EXCEPTION-NO-SILENT-LOSS.
     *
     * @return the async uncaught exception handler
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new MatchGenAsyncUncaughtExceptionHandler();
    }

    /**
     * {@link TaskDecorator} that captures the {@link TenantContext} from the submitting thread and
     * re-binds it on the worker thread before the task executes (Wave-1 async cross-thread
     * pattern).
     *
     * <p>The decorator is called on the submitting thread at task-submission time. It captures the
     * current tenant ID via {@link TenantContext#current()}. The returned {@link Runnable} runs on
     * the worker thread: it opens a new {@link TenantContext.Scope} with the captured tenant ID,
     * executes the original runnable, then closes the scope.
     *
     * <p>If no tenant is bound on the submitting thread (e.g., during application startup or health
     * checks), the decorator skips propagation and executes the original runnable directly.
     */
    static class TenantContextTaskDecorator implements TaskDecorator {

        private static final Logger DECORATOR_LOG =
                LoggerFactory.getLogger(TenantContextTaskDecorator.class);

        private final TenantContext tenantContext;

        TenantContextTaskDecorator(TenantContext tenantContext) {
            this.tenantContext = tenantContext;
        }

        @Override
        @SuppressWarnings("try") // scope variable not referenced in body — used only for auto-close
        public Runnable decorate(Runnable runnable) {
            // Capture the tenant ID on the submitting (calling) thread
            java.util.UUID capturedTenantId;
            try {
                capturedTenantId = tenantContext.current();
            } catch (IllegalStateException e) {
                // No tenant bound on submitting thread — skip propagation
                DECORATOR_LOG.debug(
                        "TenantContextTaskDecorator: no tenant bound on submitting thread"
                                + " — propagation skipped");
                return runnable;
            }

            // Return a decorated runnable that re-binds the tenant on the worker thread.
            // The scope variable is intentionally not referenced in the body — it is used
            // only for its AutoCloseable.close() side-effect at the end of the try block.
            final java.util.UUID tenantId = capturedTenantId;
            return () -> {
                try (TenantContext.Scope scope = tenantContext.bind(tenantId)) {
                    runnable.run();
                }
            };
        }
    }

    /** Last-resort {@link AsyncUncaughtExceptionHandler} for {@code @Async} void methods. */
    static class MatchGenAsyncUncaughtExceptionHandler implements AsyncUncaughtExceptionHandler {

        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            LOG.error(
                    "Uncaught exception in @Async method {}.{}() — params={} — exception escaped"
                            + " listener try/catch (DEC-55 D-3, AC-ERROR-HANDLING-LISTENER-"
                            + "EXCEPTION-NO-SILENT-LOSS)",
                    method.getDeclaringClass().getSimpleName(),
                    method.getName(),
                    params,
                    ex);
        }
    }
}
