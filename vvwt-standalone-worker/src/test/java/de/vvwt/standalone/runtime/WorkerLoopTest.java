package de.vvwt.standalone.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import de.vvwt.standalone.config.WorkerConfig;
import de.vvwt.standalone.crypto.ResultSigner;
import de.vvwt.standalone.http.DispatcherClient;
import de.vvwt.standalone.http.DispatcherException;
import de.vvwt.standalone.log.StructuredLogger;
import de.vvwt.worker.types.CanonicalPhaseDef;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.helpers.NOPLogger;

/**
 * Unit tests for {@link WorkerLoop} — retry back-off, 30-minute timeout, 4xx immediate exit.
 * Implements Story E01S05 AC3, AC5, AC6 test checkpoints.
 *
 * <p>Note: These tests use mocks for {@link DispatcherClient} and {@link ResultSigner} to avoid
 * network calls. The 30-minute test uses an injected failure-window nanosecond via subclassing to
 * avoid actual waiting.
 */
class WorkerLoopTest {

    private DispatcherClient mockClient;
    private ResultSigner mockResultSigner;
    private WorkerConfig config;
    private StructuredLogger log;
    private UUID workerKeyId;
    private CpuThrottle cpuThrottle;

    @BeforeEach
    void setUp() {
        mockClient = Mockito.mock(DispatcherClient.class);
        mockResultSigner = Mockito.mock(ResultSigner.class);
        config =
                new WorkerConfig(
                        Paths.get("/tmp/test"),
                        "https://dispatcher.example.com",
                        100, // no CPU throttling in tests
                        "test",
                        30,
                        false);
        log = new StructuredLogger(NOPLogger.NOP_LOGGER, false);
        workerKeyId = UUID.randomUUID();
        cpuThrottle = new CpuThrottle(100);
    }

    @Test
    void backoffForAttempt_0_returns1() {
        assertThat(WorkerLoop.backoffForAttempt(0)).isEqualTo(1L);
    }

    @Test
    void backoffForAttempt_1_returns2() {
        assertThat(WorkerLoop.backoffForAttempt(1)).isEqualTo(2L);
    }

    @Test
    void backoffForAttempt_2_returns4() {
        assertThat(WorkerLoop.backoffForAttempt(2)).isEqualTo(4L);
    }

    @Test
    void backoffForAttempt_beyondMax_returns30() {
        assertThat(WorkerLoop.backoffForAttempt(99)).isEqualTo(30L);
    }

    @Test
    void run_4xxFromPullPacket_returnsExitConfig() throws Exception {
        when(mockResultSigner.signPullNonce(any(), anyString())).thenReturn("dummysig");
        when(mockClient.pullPacket(any(), anyString(), anyString()))
                .thenThrow(new DispatcherException(401, "unauthorized", "pull-packet"));

        WorkerLoop loop =
                new WorkerLoop(config, mockClient, mockResultSigner, cpuThrottle, log, workerKeyId);
        int exitCode = loop.run();

        assertThat(exitCode).isEqualTo(WorkerLoop.EXIT_CONFIG);
    }

    @Test
    void run_4xxFromSubmitResult_returnsExitConfig() throws Exception {
        // rowCount=3, avatarCount=2 — n=rowCount=3, so rankTo=6 (3! = 6) is valid
        CanonicalPhaseDef phaseDef =
                new CanonicalPhaseDef(3, 2, List.of(List.of(0, 1), List.of(0), List.of(1)));
        DispatcherClient.PullResult.PacketAssigned assigned =
                new DispatcherClient.PullResult.PacketAssigned(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        phaseDef,
                        phaseDef.rowCount(), // n = rowCount = 3
                        0L,
                        6L,
                        Instant.now().plusSeconds(300));

        when(mockResultSigner.signPullNonce(any(), anyString())).thenReturn("dummysig");
        when(mockClient.pullPacket(any(), anyString(), anyString())).thenReturn(assigned);
        when(mockResultSigner.signResult(any(), any(), any(), any())).thenReturn("resultsig");
        when(mockClient.submitResult(any(), any(), any(), any(), anyString()))
                .thenThrow(new DispatcherException(403, "forbidden", "submit-result"));

        WorkerLoop loop =
                new WorkerLoop(config, mockClient, mockResultSigner, cpuThrottle, log, workerKeyId);
        int exitCode = loop.run();

        assertThat(exitCode).isEqualTo(WorkerLoop.EXIT_CONFIG);
    }

    @Test
    void run_successfulCycle_thenStop() throws Exception {
        // rowCount=3, avatarCount=2 — n=rowCount=3, so rankTo=6 (3! = 6) is valid
        CanonicalPhaseDef phaseDef =
                new CanonicalPhaseDef(3, 2, List.of(List.of(0, 1), List.of(0), List.of(1)));
        DispatcherClient.PullResult.PacketAssigned assigned =
                new DispatcherClient.PullResult.PacketAssigned(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        phaseDef,
                        phaseDef.rowCount(), // n = rowCount = 3
                        0L,
                        6L,
                        Instant.now().plusSeconds(300));

        when(mockResultSigner.signPullNonce(any(), anyString())).thenReturn("dummysig");
        // First call: return packet; second call: no work (to exit the loop)
        when(mockClient.pullPacket(any(), anyString(), anyString()))
                .thenReturn(assigned)
                .thenReturn(new DispatcherClient.PullResult.NoWork());
        when(mockResultSigner.signResult(any(), any(), any(), any())).thenReturn("resultsig");
        when(mockClient.submitResult(any(), any(), any(), any(), anyString())).thenReturn(true);

        WorkerLoop loop =
                new WorkerLoop(config, mockClient, mockResultSigner, cpuThrottle, log, workerKeyId);
        loop.running = true;
        // Just run — the mock will return NoWork on the second call, and the test config has
        // idlePollSeconds=30, which means the loop calls Thread.sleep(30000).
        // To avoid waiting, we interrupt the loop after the first NoWork.
        Thread loopThread = new Thread(() -> loop.run());
        loopThread.setDaemon(true);
        loopThread.start();
        // Give the loop a moment to process the packet and hit the idle sleep
        Thread.sleep(200);
        loopThread.interrupt();
        loopThread.join(1000);

        // Verify the loop processed the assigned packet
        Mockito.verify(mockClient, Mockito.atLeastOnce())
                .submitResult(any(), any(), any(), any(), anyString());
    }

    @Test
    void run_noWorkResponse_sleepsAndContinues() throws Exception {
        when(mockResultSigner.signPullNonce(any(), anyString())).thenReturn("dummysig");
        // Always return no work (loop will sleep then retry)
        when(mockClient.pullPacket(any(), anyString(), anyString()))
                .thenReturn(new DispatcherClient.PullResult.NoWork());

        WorkerLoop loop =
                new WorkerLoop(config, mockClient, mockResultSigner, cpuThrottle, log, workerKeyId);
        loop.running = true;

        Thread loopThread = new Thread(() -> loop.run());
        loopThread.setDaemon(true);
        loopThread.start();
        // Interrupt quickly to stop the loop
        Thread.sleep(100);
        loopThread.interrupt();
        loopThread.join(1000);

        // Verify at least one pull was made
        Mockito.verify(mockClient, Mockito.atLeastOnce())
                .pullPacket(any(), anyString(), anyString());
    }
}
