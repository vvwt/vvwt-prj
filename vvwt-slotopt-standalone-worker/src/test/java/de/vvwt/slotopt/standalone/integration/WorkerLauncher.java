package de.vvwt.slotopt.standalone.integration;

import de.vvwt.slotopt.standalone.WorkerConfig;
import de.vvwt.slotopt.standalone.bootstrap.BootstrapException;
import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import de.vvwt.slotopt.standalone.bootstrap.internal.DefaultBootstrapService;
import de.vvwt.slotopt.standalone.runtime.CpuThrottle;
import de.vvwt.slotopt.standalone.runtime.WorkerLoopException;
import de.vvwt.slotopt.standalone.runtime.internal.DefaultWorkerLoop;
import de.vvwt.slotopt.worker.identity.WorkerKeyCorruptException;
import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.identity.internal.DefaultWorkerKeyManager;
import de.vvwt.slotopt.worker.runtime.ComputeStep;
import de.vvwt.slotopt.worker.runtime.DispatcherClient;
import de.vvwt.slotopt.worker.runtime.ResultSigner;
import de.vvwt.slotopt.worker.runtime.internal.DefaultComputeStep;
import de.vvwt.slotopt.worker.runtime.internal.DefaultDispatcherClient;
import de.vvwt.slotopt.worker.runtime.internal.DefaultResultSigner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.LoggerFactory;

/**
 * In-process worker runner for integration tests.
 *
 * <p>Constructs the full worker pipeline (key manager, dispatcher client, compute step, bootstrap
 * service, worker loop) from a {@link WorkerConfig} and runs the worker in a background thread.
 * Captures stderr output and structured events for assertion.
 *
 * <p>Does NOT invoke {@link de.vvwt.slotopt.standalone.OptimizerWorkerMain#main} — that would call
 * {@code System.exit()}, which terminates the JVM. Instead, component classes are instantiated
 * directly and exceptions are caught to determine the exit code.
 *
 * <p>DEC-36: only references public interfaces ({@link DispatcherClient}, {@link
 * de.vvwt.slotopt.standalone.bootstrap.BootstrapService}, {@link
 * de.vvwt.slotopt.standalone.runtime.WorkerLoop}).
 *
 * <p>Story: E41S06 AC-INTEGRATION-TEST-HAPPY-PATH and related ITs; E63S01 re-wired onto shared
 * runtime library (AC-GOV-NO-TEST-ONLY-MEMBERS-IN-EXTRACTED-CODE: stopAfterNextIteration removed).
 * E60S05: made {@code public} so the class is accessible from the dispatcher E2E test package via
 * the test-jar of vvwt-slotopt-standalone-worker.
 */
public class WorkerLauncher {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final URI dispatcherUrl;
    private final Duration timeout;
    private volatile DefaultWorkerLoop workerLoopRef;

    /**
     * Creates a launcher pointing at the given dispatcher URL with a 30-second timeout.
     *
     * @param dispatcherUrl base URL of the test-double dispatcher
     */
    public WorkerLauncher(URI dispatcherUrl) {
        this(dispatcherUrl, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a launcher with a custom timeout.
     *
     * @param dispatcherUrl base URL of the test-double dispatcher
     * @param timeout maximum wait time for worker thread to complete
     */
    public WorkerLauncher(URI dispatcherUrl, Duration timeout) {
        this.dispatcherUrl = dispatcherUrl;
        this.timeout = timeout;
    }

    /**
     * Launches the worker and waits for it to complete.
     *
     * <p>Creates a temporary key directory, constructs all worker components, runs the bootstrap
     * phase, then the runtime loop. Captures stderr and structured events.
     *
     * @return {@link WorkerRunResult} with exit code, stderr, and captured event list
     */
    public WorkerRunResult launch() {
        ByteArrayOutputStream stderrBaos = new ByteArrayOutputStream();
        PrintStream capturedErr = new PrintStream(stderrBaos);
        PrintStream originalErr = System.err;

        List<String> bootstrapEvents = Collections.synchronizedList(new ArrayList<>());
        CapturingStructuredLogger runtimeLogger = new CapturingStructuredLogger();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Integer> future =
                executor.submit(
                        () -> {
                            System.setErr(capturedErr);
                            try {
                                return runWorkerInThread(bootstrapEvents, runtimeLogger);
                            } finally {
                                System.setErr(originalErr);
                                capturedErr.flush();
                            }
                        });

        try {
            int exitCode = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            executor.shutdown();

            List<String> allEvents = new ArrayList<>(bootstrapEvents);
            allEvents.addAll(runtimeLogger.capturedEvents());

            return new WorkerRunResult(
                    exitCode, stderrBaos.toString(), Collections.unmodifiableList(allEvents));
        } catch (TimeoutException e) {
            future.cancel(true);
            executor.shutdown();
            return new WorkerRunResult(-1, "TIMEOUT after " + timeout, Collections.emptyList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdown();
            return new WorkerRunResult(-1, "INTERRUPTED", Collections.emptyList());
        } catch (ExecutionException e) {
            executor.shutdown();
            return new WorkerRunResult(
                    -1, "EXECUTION_ERROR: " + e.getCause(), Collections.emptyList());
        }
    }

    /**
     * Requests graceful shutdown of the running worker loop (if started).
     *
     * <p>Used by tests that need to trigger graceful termination without waiting for a natural
     * stop.
     */
    public void requestShutdown() {
        DefaultWorkerLoop loop = workerLoopRef;
        if (loop != null) {
            loop.requestShutdown();
        }
    }

    private int runWorkerInThread(
            List<String> bootstrapEventSink, CapturingStructuredLogger runtimeLogger) {

        Path keyDir;
        try {
            keyDir = Files.createTempDirectory("e41s06-it-keydir-");
        } catch (IOException e) {
            return ExitCode.DISPATCHER_UNREACHABLE; // fallback — infra error
        }

        WorkerConfig config =
                new WorkerConfig(
                        dispatcherUrl,
                        keyDir,
                        Duration.ofMillis(100), // fast poll for tests
                        "Ed25519",
                        Duration.ofSeconds(5),
                        50,
                        "test-worker",
                        "text");

        WorkerKeyManager workerKeyManager;
        try {
            workerKeyManager =
                    new DefaultWorkerKeyManager(
                            config.keyDir(),
                            config.signingAlgorithm(),
                            LoggerFactory.getLogger(DefaultWorkerKeyManager.class));
        } catch (WorkerKeyCorruptException e) {
            System.err.println("ERROR: Worker key corrupt: " + e.getMessage());
            return 78; // EX_CONFIG
        } catch (IOException e) {
            System.err.println("ERROR: Key manager init failed: " + e.getMessage());
            return 75; // EX_TEMPFAIL
        }

        DispatcherClient dispatcherClient =
                new DefaultDispatcherClient(config.dispatcherUrl(), config.httpTimeout());
        ResultSigner resultSigner =
                new DefaultResultSigner(workerKeyManager, config.signingAlgorithm());
        ComputeStep computeStep =
                new DefaultComputeStep(dispatcherClient, resultSigner, config.signingAlgorithm());

        // Bootstrap phase with event capture.
        // Pass the real workerKeyManager so that registration uses the actual Ed25519 public key
        // (E60S05: completes the wiring that was deferred from E41S05 — dummy key caused HTTP 401
        // on submit-result because the dispatcher verified against a 32-zero-byte dummy).
        UUID workerId;
        try {
            workerId =
                    new DefaultBootstrapService(
                                    dispatcherClient,
                                    event -> {
                                        // Extract event name (first token before space)
                                        String eventName =
                                                event.contains(" ")
                                                        ? event.substring(0, event.indexOf(' '))
                                                        : event;
                                        bootstrapEventSink.add(eventName);
                                    },
                                    workerKeyManager)
                            .run(config);
        } catch (BootstrapException e) {
            System.err.println("ERROR: Bootstrap failed: " + e.getMessage());
            return e.getExitCode();
        }

        // Runtime loop — no-op CpuThrottle for IT speed
        CpuThrottle noOpThrottle = duration -> {};

        DefaultWorkerLoop workerLoop =
                new DefaultWorkerLoop(computeStep, config, noOpThrottle, runtimeLogger, workerId);
        workerLoopRef = workerLoop;

        try {
            workerLoop.run();
            return 0;
        } catch (WorkerLoopException e) {
            System.err.println("ERROR: Worker loop terminated: " + e.getMessage());
            return e.getExitCode();
        }
    }
}
