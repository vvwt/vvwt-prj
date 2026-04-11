package de.vvwt.standalone.runtime;

import de.vvwt.standalone.config.WorkerConfig;
import de.vvwt.standalone.crypto.ResultSigner;
import de.vvwt.standalone.http.DispatcherClient;
import de.vvwt.standalone.http.DispatcherException;
import de.vvwt.standalone.log.StructuredLogger;
import de.vvwt.worker.solver.PacketSolver;
import de.vvwt.worker.types.JobDef;
import de.vvwt.worker.types.PacketResult;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the main pull → solve → submit loop.
 *
 * <h2>Retry policy (AC5)</h2>
 * <p>Network errors and 5xx responses are retried with exponential back-off:
 * 1s, 2s, 4s, 8s, 16s, then 30s steady. After 30 consecutive minutes of failures,
 * logs WARN and exits with code 75 (EX_TEMPFAIL).
 *
 * <h2>4xx handling (AC6)</h2>
 * <p>Any 4xx response is logged at ERROR with the full response body and exits with
 * code 78 (EX_CONFIG). No retry.
 *
 * <h2>No-packet handling (AC3)</h2>
 * <p>HTTP 204 from the dispatcher causes an idle sleep of {@code idlePollSeconds},
 * then the loop retries.
 *
 * <h2>CPU throttling (AC7)</h2>
 * <p>After each packet solve, {@link CpuThrottle} is consulted for the required sleep.
 *
 * <p>Implements Story E01S05 AC3, AC5, AC6, AC7, AC8.
 */
public final class WorkerLoop {

    /** Exit code: transient failure — supervisor should restart (sysexits.h EX_TEMPFAIL). */
    public static final int EXIT_TEMPFAIL = 75;

    /** Exit code: configuration / permanent error — manual intervention required (sysexits.h EX_CONFIG). */
    public static final int EXIT_CONFIG = 78;

    /** Maximum continuous failure window before giving up. */
    static final long MAX_FAILURE_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(30);

    /** Back-off schedule in seconds. Index represents consecutive failure count (capped at last entry). */
    private static final long[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 30};

    private final WorkerConfig config;
    private final DispatcherClient dispatcherClient;
    private final ResultSigner resultSigner;
    private final CpuThrottle cpuThrottle;
    private final StructuredLogger log;
    private final UUID workerKeyId;

    // Exposed for testing
    volatile boolean running = true;

    /**
     * Constructs a {@code WorkerLoop}.
     *
     * @param config           effective worker configuration
     * @param dispatcherClient configured HTTP client
     * @param resultSigner     signing helper
     * @param cpuThrottle      CPU throttle
     * @param log              structured logger
     * @param workerKeyId      the registered worker key UUID
     */
    public WorkerLoop(
            WorkerConfig config,
            DispatcherClient dispatcherClient,
            ResultSigner resultSigner,
            CpuThrottle cpuThrottle,
            StructuredLogger log,
            UUID workerKeyId
    ) {
        this.config = config;
        this.dispatcherClient = dispatcherClient;
        this.resultSigner = resultSigner;
        this.cpuThrottle = cpuThrottle;
        this.log = log;
        this.workerKeyId = workerKeyId;
    }

    /**
     * Runs the loop until stopped or until an exit condition is reached.
     *
     * @return process exit code: 0 on graceful stop, {@link #EXIT_TEMPFAIL} or {@link #EXIT_CONFIG}
     *         on error
     */
    public int run() {
        long consecutiveFailureStartNanos = 0L;
        boolean inFailureWindow = false;
        int failureCount = 0;

        while (running) {
            try {
                // Pull a packet
                String signedNonce = Instant.now().toString();
                String pullSignature = resultSigner.signPullNonce(workerKeyId, signedNonce);

                log.info("packet-pull", "Requesting next packet",
                        "workerKeyId", workerKeyId);

                DispatcherClient.PullResult pullResult =
                        dispatcherClient.pullPacket(workerKeyId, pullSignature, signedNonce);

                if (pullResult instanceof DispatcherClient.PullResult.NoWork) {
                    // Reset failure tracking — 204 is a valid response
                    inFailureWindow = false;
                    failureCount = 0;

                    log.info("idle-sleep", "No packets available, sleeping",
                            "idlePollSeconds", config.idlePollSeconds());
                    sleepSeconds(config.idlePollSeconds());
                    continue;
                }

                // Packet assigned
                DispatcherClient.PullResult.PacketAssigned packet =
                        (DispatcherClient.PullResult.PacketAssigned) pullResult;

                log.info("packet-assigned", "Packet assigned, solving",
                        "packetId", packet.packetId(),
                        "jobId", packet.jobId(),
                        "rankFrom", packet.rankFrom(),
                        "rankTo", packet.rankTo());

                // Reset failure tracking on successful pull
                inFailureWindow = false;
                failureCount = 0;

                // Solve
                JobDef jobDef = new JobDef(packet.jobId(), packet.n(), packet.canonicalPhaseDef());
                PacketResult result = PacketSolver.solvePacket(jobDef, packet.rankFrom(), packet.rankTo());

                // Sign
                String base64Signature = resultSigner.signResult(
                        packet.packetId(), packet.jobId(), result, workerKeyId);

                // Submit
                boolean firstResult = dispatcherClient.submitResult(
                        packet.packetId(), packet.jobId(), result, workerKeyId, base64Signature);

                log.info("result-submitted", "Result submitted",
                        "packetId", packet.packetId(),
                        "firstResult", firstResult,
                        "bestRank", result.bestRank(),
                        "permutationsScored", result.permutationsScored());

                // CPU throttle
                long sleepNanos = cpuThrottle.recordWorkAndGetSleepNanos(result.wallClockNanos());
                if (sleepNanos > 0) {
                    sleepNanos(sleepNanos);
                }

            } catch (DispatcherException ex) {
                if (ex.isClientError()) {
                    // 4xx: permanent error — exit immediately (AC6)
                    log.error("dispatcher-4xx",
                            "Dispatcher returned client error — manual intervention required",
                            "statusCode", ex.getStatusCode(),
                            "responseBody", ex.getResponseBody(),
                            "endpoint", ex.getEndpoint());
                    return EXIT_CONFIG;
                }

                // Network error or 5xx: retry with back-off (AC5)
                if (!inFailureWindow) {
                    consecutiveFailureStartNanos = System.nanoTime();
                    inFailureWindow = true;
                }

                long elapsedFailureNanos = System.nanoTime() - consecutiveFailureStartNanos;
                if (elapsedFailureNanos >= MAX_FAILURE_WINDOW_NANOS) {
                    log.warn("failure-timeout",
                            "Continuous failures for 30 minutes — exiting (EX_TEMPFAIL)",
                            "elapsedMinutes", TimeUnit.NANOSECONDS.toMinutes(elapsedFailureNanos));
                    return EXIT_TEMPFAIL;
                }

                long backoffSeconds = backoffForAttempt(failureCount);
                failureCount++;
                log.info("retry", "Dispatcher error, retrying with back-off",
                        "statusCode", ex.getStatusCode(),
                        "backoffSeconds", backoffSeconds,
                        "failureCount", failureCount);
                try {
                    sleepSeconds(backoffSeconds);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("interrupted", "Worker loop interrupted during back-off, shutting down");
                    return 0;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("interrupted", "Worker loop interrupted, shutting down");
                return 0;
            }
        }

        return 0;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    static long backoffForAttempt(int attemptIndex) {
        int idx = Math.min(attemptIndex, BACKOFF_SECONDS.length - 1);
        return BACKOFF_SECONDS[idx];
    }

    private void sleepSeconds(long seconds) throws InterruptedException {
        Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
    }

    private void sleepNanos(long nanos) throws InterruptedException {
        long millis = nanos / 1_000_000L;
        int extraNanos = (int) (nanos % 1_000_000L);
        Thread.sleep(millis, extraNanos);
    }
}
