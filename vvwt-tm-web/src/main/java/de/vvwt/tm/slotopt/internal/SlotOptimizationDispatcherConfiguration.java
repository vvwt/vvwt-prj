package de.vvwt.tm.slotopt.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.slotopt.worker.identity.WorkerKeyCorruptException;
import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.identity.internal.DefaultWorkerKeyManager;
import de.vvwt.tm.slotopt.DispatcherReachabilityService;
import de.vvwt.tm.slotopt.SlotOptimizationDispatcherClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for Leg 2 dispatcher client and reachability service (E27S03, DEC-49 D-3).
 *
 * <p>Provides:
 *
 * <ul>
 *   <li>{@link WorkerKeyManager} — Ed25519 keypair manager for TM worker identity (reuses {@code
 *       vvwt-slotopt-worker-lib}; no new impl per E27S03 scope boundary).
 *   <li>{@link DispatcherReachabilityService} — HEAD probe for Leg 2 reachability guard.
 *   <li>{@link SlotOptimizationDispatcherClient} — HTTP client for Leg 2 dispatcher submission.
 * </ul>
 *
 * <h2>DEC-11 boundary</h2>
 *
 * <p>This class MUST NOT import any class from {@code vvwt-slotopt-dispatcher}. The Maven Enforcer
 * rule in {@code vvwt-tm-web/pom.xml} (AC-MAVEN-ENFORCER-RULE) makes this build-checked.
 *
 * <h2>Null-URL fast path</h2>
 *
 * <p>When {@code tm.slotopt.dispatcher.url} is null/empty (default), the {@link
 * DefaultDispatcherReachabilityService} returns {@code false} immediately for every call, so {@link
 * RoutingSlotOptimizationClient} always uses Leg 3 without any HTTP overhead.
 *
 * @see DispatcherReachabilityService
 * @see SlotOptimizationDispatcherClient
 * @see RoutingSlotOptimizationClient
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-11.md">DEC-11</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S03.story.md">Story
 *     E27S03</a>
 */
@Configuration
class SlotOptimizationDispatcherConfiguration {

    private static final Logger LOG =
            LoggerFactory.getLogger(SlotOptimizationDispatcherConfiguration.class);

    /**
     * Ed25519 keypair manager for TM worker identity.
     *
     * <p>Key files are stored in {@code tm.slotopt.dispatcher.worker-key-dir} (default: {@code
     * ${user.home}/.tournament-manager/slotopt-keys}). Reuses the {@code DefaultWorkerKeyManager}
     * from {@code vvwt-slotopt-worker-lib} — no new implementation per E27S03 scope boundary.
     */
    @Bean
    WorkerKeyManager slotOptWorkerKeyManager(
            @Value(
                            "${tm.slotopt.dispatcher.worker-key-dir:${user.home}/.tournament-manager/slotopt-keys}")
                    String workerKeyDir)
            throws IOException, WorkerKeyCorruptException {
        Path keyDir = Path.of(workerKeyDir);
        LOG.info(
                "SlotOptimizationDispatcherConfiguration: initializing WorkerKeyManager at {}",
                keyDir);
        return new DefaultWorkerKeyManager(keyDir, "Ed25519", LOG);
    }

    /**
     * Reachability probe service — checks dispatcher availability before Leg 2 submission.
     *
     * <p>Returns {@code false} immediately when {@code tm.slotopt.dispatcher.url} is null/empty
     * (AC-REACHABILITY-NULL-URL-RETURNS-FALSE).
     */
    @Bean
    DispatcherReachabilityService dispatcherReachabilityService(
            @Value("${tm.slotopt.dispatcher.url:}") String dispatcherUrl,
            @Value("${tm.slotopt.dispatcher.reachability-timeout-ms:2000}")
                    long reachabilityTimeoutMs) {
        return new DefaultDispatcherReachabilityService(
                dispatcherUrl.isBlank() ? null : dispatcherUrl, reachabilityTimeoutMs);
    }

    /**
     * HTTP dispatcher client for Leg 2 job submission and polling.
     *
     * <p>The {@code workerId} is a stable UUID derived from the TM instance — in V1, generated
     * fresh per Spring context startup (in-memory registration per restart, per E27S03 out-of-scope
     * note on key persistence).
     */
    @Bean
    SlotOptimizationDispatcherClient slotOptimizationDispatcherClient(
            @Value("${tm.slotopt.dispatcher.url:}") String dispatcherUrl,
            @Value("${tm.slotopt.dispatcher.poll-timeout-ms:300000}") long pollTimeoutMs,
            WorkerKeyManager slotOptWorkerKeyManager,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper) {
        // V1: workerId is a fresh UUID per TM startup (in-memory registration per restart).
        // Key persistence across restarts is out of E27S03 scope per story notes.
        UUID workerId = UUID.randomUUID();
        LOG.info(
                "SlotOptimizationDispatcherConfiguration: slotOptimizationDispatcherClient"
                        + " workerId={}, url={}",
                workerId,
                dispatcherUrl.isBlank() ? "(none — Leg 2 disabled)" : dispatcherUrl);
        return new DefaultSlotOptimizationDispatcherClient(
                dispatcherUrl.isBlank() ? null : dispatcherUrl,
                workerId,
                slotOptWorkerKeyManager,
                eventPublisher,
                objectMapper,
                pollTimeoutMs);
    }
}
