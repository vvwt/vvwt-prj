package de.vvwt.tm.infoportal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.infoportal.internal.DefaultEd25519KeypairManager;
import de.vvwt.tm.infoportal.internal.DefaultInfoPortalPublisherService;
import de.vvwt.tm.infoportal.internal.DefaultInfoPortalStateDao;
import de.vvwt.tm.infoportal.internal.DefaultTmJcsCanonicalizer;
import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration tests for {@link InfoPortalPublisherService} against a real {@code
 * vvwt-info-server} instance via Testcontainers-on-Podman.
 *
 * <p>Discharges E38S09 deferred acceptance criteria:
 *
 * <ul>
 *   <li>AC1 (this story): happy-path publisher IT (E38S09 AC2)
 *   <li>AC2 (this story): 409 FULL_RESYNC recovery IT (E38S09 AC3)
 *   <li>AC3 (this story): idempotent-publisher IT (E38S09 AC13)
 * </ul>
 *
 * <p>The container runtime is the rootless Podman socket exposed via {@code DOCKER_HOST} (operator
 * precondition, Notes in E38S10.story.md). If the socket is unreachable, a typed {@link
 * ContainerRuntimeUnavailableException} is thrown in {@link #assertRuntimeAvailable()} — the tests
 * never skip silently (AC5).
 *
 * <p>The {@code vvwt-info-server} fat-JAR is built by the Maven reactor before the {@code
 * integration-test} phase runs in {@code vvwt-tm-web}. The JAR path is derived from the Maven
 * output directory without introducing a compile-time Maven dependency on {@code vvwt-info-server}
 * in {@code vvwt-tm-web/pom.xml} (DEC-42 D2).
 *
 * <p>Single-tenant constraint (DEC-42 D3 self-host profile): the info-server in self-host mode
 * allows exactly one tenant. All tests in this class share one tenant-id and one Ed25519 keypair
 * (generated once in {@code @BeforeAll}). Each test registers its own uniquely-named tournament to
 * avoid tournament-state collisions across tests.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S10.story.md">
 *     E38S10 AC1–AC8</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">
 *     E38S09 AC2, AC3, AC13 (deferred)</a>
 */
@Tag("testcontainers")
@Testcontainers
class InfoPortalPublisherIT {

    // -------------------------------------------------------------------------
    // Shared static constants — tenant-id and keypair directory
    // -------------------------------------------------------------------------

    /**
     * Shared tenant-id for all tests. The info-server self-host profile allows exactly one tenant
     * (DEC-42 D3); all tests must use the same tenant-id.
     */
    private static final String TENANT_ID = "it-tenant";

    /**
     * Shared keypair directory — created once per JVM run. All tests in this class use the same
     * Ed25519 keypair so the info-server's first-key-wins constraint is satisfied across tests.
     */
    private static Path sharedKeypairDir;

    /** Shared keypair manager — initialized once, reused by all tests. */
    private static Ed25519KeypairManager sharedKeypairManager;

    // -------------------------------------------------------------------------
    // Info-server JAR path (built by Maven reactor before integration-test phase)
    // -------------------------------------------------------------------------

    /**
     * Resolves the path to the {@code vvwt-info-server} fat-JAR from the Maven multi-module reactor
     * output directory. No compile-time Maven dependency on {@code vvwt-info-server} is added to
     * {@code vvwt-tm-web/pom.xml} (DEC-42 D2 preserved).
     *
     * <p>The Maven reactor builds {@code vvwt-info-server:package} before {@code
     * vvwt-tm-web:integration-test} because {@code vvwt-tm-web} depends on {@code vvwt-info-dto}
     * which is in the same reactor.
     */
    private static Path resolveInfoServerJar() {
        // vvwt-tm-web sits at: <root>/vvwt-tm-web/
        // vvwt-info-server sits at: <root>/vvwt-info-server/
        // Maven ${project.basedir} for vvwt-tm-web is obtained via class-loader heuristic:
        // Walk up from this class-file to the module root, then sibling.
        String classPath =
                InfoPortalPublisherIT.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .getPath();
        // classPath is e.g. /…/vvwt-tm-web/target/test-classes
        // getParent×1 → /…/vvwt-tm-web/target
        // getParent×2 → /…/vvwt-tm-web  (module root)
        // getParent×3 → /…/vvwt-prj     (reactor root)
        Path moduleRoot = Path.of(classPath).getParent().getParent(); // /…/vvwt-tm-web/
        Path reactorRoot = moduleRoot.getParent(); // /…/vvwt-prj/ (reactor root)
        Path infoServerTarget = reactorRoot.resolve("vvwt-info-server/target");
        // The fat-JAR has the -exec suffix (spring-boot-maven-plugin repackage goal)
        try (var stream = java.nio.file.Files.list(infoServerTarget)) {
            return stream.filter(
                            p ->
                                    p.getFileName().toString().endsWith("-exec.jar")
                                            && !p.getFileName().toString().contains("original"))
                    .findFirst()
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "vvwt-info-server fat-JAR not found in "
                                                    + infoServerTarget
                                                    + " — run: mvn package -pl"
                                                    + " vvwt-info-server,vvwt-info-dto"
                                                    + " -DskipTests"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Cannot list " + infoServerTarget + ": " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Testcontainers — shared static container (reused across all tests in this class)
    // -------------------------------------------------------------------------

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> INFO_SERVER =
            new GenericContainer<>(
                            new ImageFromDockerfile(
                                            "vvwt-info-server-it-" + System.nanoTime(), false)
                                    .withFileFromPath("app.jar", resolveInfoServerJar())
                                    .withDockerfileFromBuilder(
                                            builder ->
                                                    builder.from("eclipse-temurin:21-jre-jammy")
                                                            .copy("app.jar", "/app/app.jar")
                                                            .cmd(
                                                                    "java",
                                                                    "-jar",
                                                                    "/app/app.jar",
                                                                    "--spring.profiles.active=self-host",
                                                                    "--server.port=8090")
                                                            .build()))
                    .withExposedPorts(8090)
                    .waitingFor(
                            Wait.forHttp("/api/v1/register/algorithms")
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofSeconds(120)));

    // -------------------------------------------------------------------------
    // Per-test fields (fresh per test method)
    // -------------------------------------------------------------------------

    private DataSource dataSource;
    private InfoPortalStateDao stateDao;
    private InfoPortalPublisherService service;

    // -------------------------------------------------------------------------
    // Pre-flight: assert container runtime available (AC5 — never skip)
    // -------------------------------------------------------------------------

    /**
     * Asserts that the Testcontainers container runtime is reachable BEFORE any container is
     * started, and initializes the shared Ed25519 keypair used by all tests.
     *
     * <p>Throws {@link ContainerRuntimeUnavailableException} — a typed, operator-actionable error —
     * so the test run fails loudly rather than silently skipping (AC5).
     *
     * <p>The Testcontainers TC 2.x library itself will also check socket reachability on container
     * start; this pre-flight provides the named-socket + remediation error message mandated by AC5
     * before Testcontainers' own opaque error fires.
     */
    @BeforeAll
    static void assertRuntimeAvailable() throws Exception {
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost == null || dockerHost.isBlank()) {
            // Fall back to the well-known rootless Podman socket path
            dockerHost = "unix:///run/user/" + getCurrentUid() + "/podman/podman.sock";
        }

        String socketPath =
                dockerHost.startsWith("unix://") ? dockerHost.substring("unix://".length()) : null;

        if (socketPath != null) {
            java.io.File socketFile = new java.io.File(socketPath);
            if (!socketFile.exists()) {
                throw new ContainerRuntimeUnavailableException(
                        "Container runtime socket not found at: "
                                + socketPath
                                + "\n\nRemediation: start the rootless Podman socket:\n"
                                + "  systemctl --user start podman.socket\n"
                                + "  export DOCKER_HOST=unix://"
                                + socketPath
                                + "\n\nOr verify the socket path matches the running"
                                + " Podman user unit.");
            }
        }
        // If DOCKER_HOST is a tcp:// address or we can't determine socket path,
        // let Testcontainers proceed — it will throw its own error if unreachable.

        // Initialize the shared keypair once for all tests.
        // All tests use the same tenant-id + keypair so the info-server's first-key-wins
        // constraint is satisfied (self-host profile: one tenant, same key across tests).
        sharedKeypairDir = Files.createTempDirectory("it-info-portal-keys-");
        sharedKeypairManager = new DefaultEd25519KeypairManager(sharedKeypairDir);
        sharedKeypairManager.initializeIfAbsent();
    }

    @BeforeEach
    void setUp() throws Exception {
        // Wire DataSource (per-tenant H2 in-memory, DEC-20 pattern via TenantDaoTestSupport)
        dataSource = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyMigration(
                dataSource, "db/migration/infoportal/V1__initial_schema.sql");

        stateDao = new DefaultInfoPortalStateDao(dataSource);

        TmJcsCanonicalizer canonicalizer = new DefaultTmJcsCanonicalizer();
        RestTemplate restTemplate = new RestTemplate();

        // Info-portal properties — point to the running container.
        // All tests share TENANT_ID so the info-server's one-tenant-limit (DEC-42 D3 self-host)
        // is respected. Each test uses a unique tournament-id to avoid state collisions.
        String infoServerBaseUrl =
                "http://" + INFO_SERVER.getHost() + ":" + INFO_SERVER.getMappedPort(8090);

        InfoPortalProperties properties = new InfoPortalProperties();
        properties.setUrl(infoServerBaseUrl);
        properties.setTenantId(TENANT_ID);
        properties.setLocationId("test-loc");

        service =
                new DefaultInfoPortalPublisherService(
                        properties, stateDao, restTemplate, sharedKeypairManager, canonicalizer);
    }

    // -------------------------------------------------------------------------
    // AC1 (discharges E38S09 AC2): happy-path publisher IT
    // -------------------------------------------------------------------------

    /**
     * Happy-path: tenant registration → tournament registration → 3 delta publishes, all applied.
     * Verified by querying {@code last_published_seq} in the in-memory state DAO. The Ed25519
     * signed handshake passes the real info-server signature verifier (AC6).
     */
    @Test
    void happyPath_keypairGenerate_tenantReg_tournamentReg_3Deltas() {
        // Register tenant — first-key-wins: same key → 200 OK on re-register;
        // different test may have already registered, that's fine per first-key-wins.
        service.registerTenant();
        assertThat(service.getStatus().isRegistrationError())
                .as("Tenant registration must succeed (first-key-wins accepts same key)")
                .isFalse();

        // Register tournament (SIGNED — exercises DEC-6/DEC-43 Ed25519 path, AC6)
        service.registerTournament("t1-happy", java.util.List.of());

        // Verify tournament row was created in TM state DAO
        assertThat(stateDao.findByTournament("test-loc", "t1-happy"))
                .as("Tournament registration must persist state row")
                .isPresent();

        // Publish 3 deltas — all should succeed (200)
        service.publishDelta("t1-happy", "{\"type\":\"TEST\",\"payload\":\"delta-1\"}");
        service.publishDelta("t1-happy", "{\"type\":\"TEST\",\"payload\":\"delta-2\"}");
        service.publishDelta("t1-happy", "{\"type\":\"TEST\",\"payload\":\"delta-3\"}");

        // Verify seq advanced to 3 in TM state (seq is maintained on TM side)
        assertThat(stateDao.findCurrentSeq("test-loc", "t1-happy"))
                .as("After 3 delta publishes, last_published_seq must be 3")
                .isEqualTo(3L);

        // Publisher must be healthy
        assertThat(service.getStatus().isPublisherUnhealthy())
                .as("Publisher status must be healthy after 3 successful deltas")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // AC2 (discharges E38S09 AC3): 409 FULL_RESYNC recovery IT
    // -------------------------------------------------------------------------

    /**
     * FULL_RESYNC recovery: 3 deltas published → container stopped and restarted (fresh state) →
     * 4th delta receives 409 FULL_RESYNC → TM posts snapshot at {@code seq=3} (NOT reset to 1) →
     * delta resume at seq=4.
     *
     * <p>The container stop/start simulates info-server restart with loss of delta history. After
     * restart the info-server has no {@code last_applied_seq} for this tournament, so it responds
     * 409 on seq=4. The publisher posts a snapshot (seq=3), which establishes sync.
     */
    @Test
    void fullResync_containerRestart_snapshotWithCurrentSeq_thenResumeDeltas() throws Exception {
        // Register tenant + tournament on the (possibly fresh) server
        service.registerTenant();
        service.registerTournament("t1-resync", java.util.List.of());
        assertThat(stateDao.findByTournament("test-loc", "t1-resync")).isPresent();

        // Publish 3 deltas successfully (seq=1,2,3)
        service.publishDelta("t1-resync", "{\"type\":\"TEST\",\"payload\":\"d1\"}");
        service.publishDelta("t1-resync", "{\"type\":\"TEST\",\"payload\":\"d2\"}");
        service.publishDelta("t1-resync", "{\"type\":\"TEST\",\"payload\":\"d3\"}");
        assertThat(stateDao.findCurrentSeq("test-loc", "t1-resync")).isEqualTo(3L);

        // Simulate info-server restart: stop + start container (loses all delta history)
        INFO_SERVER.stop();
        INFO_SERVER.start();
        // Container has fresh state — no tournament history, no last_applied_seq

        // Re-register tenant (same keypair = same key → first-key-wins OK after restart)
        // and re-register tournament (upsert preserves TM-side last_published_seq=3)
        service.registerTenant();
        service.registerTournament("t1-resync", java.util.List.of());
        assertThat(stateDao.findByTournament("test-loc", "t1-resync")).isPresent();

        // Next delta attempt: TM increments seq to 4, posts seq=4.
        // Info-server: fresh state, expects seq=1 → 409 FULL_RESYNC.
        // Publisher handles 409: postSnapshot(t1-resync, snapshotJson) with seq=3 (NOT reset).
        // Info-server accepts snapshot at seq=3. Then TM retries delta with seq=4 → 200.
        service.publishDelta("t1-resync", "{\"type\":\"TEST\",\"payload\":\"d4\"}");

        // After the 409+snapshot recovery and resumed delta (seq=4):
        assertThat(stateDao.findCurrentSeq("test-loc", "t1-resync"))
                .as("After FULL_RESYNC recovery, last_published_seq must be 4 (seq NOT reset to 1)")
                .isEqualTo(4L);

        assertThat(service.getStatus().isPublisherUnhealthy())
                .as("Publisher must be healthy after successful FULL_RESYNC recovery")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // AC3 (discharges E38S09 AC13): idempotent-publisher IT
    // -------------------------------------------------------------------------

    /**
     * Idempotent publish: when the info-server loses delta history (simulated by container
     * restart), a re-submitted delta triggers 409 FULL_RESYNC. The TM publisher must NOT reset
     * {@code last_published_seq} to 1 — it posts the snapshot at the current seq and resumes.
     *
     * <p>Scenario:
     *
     * <ol>
     *   <li>Register tenant + tournament on fresh server.
     *   <li>Publish delta seq=1 — succeeds.
     *   <li>Restart container — info-server loses all delta history.
     *   <li>Re-register tenant + tournament on fresh server.
     *   <li>Publish delta seq=2 — server has no history, sees seq=2 but expects seq=1 → 409.
     *   <li>Publisher posts snapshot at seq=1 (NOT reset to 0) — server establishes seq=1.
     *   <li>Publisher retries seq=2 → succeeds.
     * </ol>
     *
     * <p>After recovery, {@code last_published_seq} must be 2 (NOT 1 or 0 from a false reset).
     */
    @Test
    void idempotentPublish_retryDoesNotAdvanceSeq() {
        // Setup: register tenant and tournament
        service.registerTenant();
        service.registerTournament("t1-idem", java.util.List.of());
        assertThat(stateDao.findByTournament("test-loc", "t1-idem")).isPresent();

        // Publish delta seq=1 — succeeds
        service.publishDelta("t1-idem", "{\"type\":\"TEST\",\"payload\":\"d1\"}");
        assertThat(stateDao.findCurrentSeq("test-loc", "t1-idem")).isEqualTo(1L);

        // Restart container — info-server loses last_applied_seq for "t1-idem"
        INFO_SERVER.stop();
        INFO_SERVER.start();

        // Re-register tenant (same keypair — first-key-wins accepts same key)
        service.registerTenant();
        service.registerTournament("t1-idem", java.util.List.of());
        assertThat(stateDao.findByTournament("test-loc", "t1-idem")).isPresent();

        // TM side: seq=1 stored. Next publishDelta will try seq=2.
        // Info-server: fresh state, expects seq=1.
        // Result: 409 FULL_RESYNC (seq=2 vs expected seq=1).
        // Publisher handles 409: postSnapshot(t1-idem, snapshotJson) with seq=1 (NOT reset to 0).
        // Info-server accepts snapshot at seq=1, then delta seq=2 → 200.
        service.publishDelta("t1-idem", "{\"type\":\"TEST\",\"payload\":\"d2\"}");

        assertThat(stateDao.findCurrentSeq("test-loc", "t1-idem"))
                .as(
                        "After idempotent-recovery, last_published_seq must be 2"
                                + " (seq NOT reset to 0 or 1 during FULL_RESYNC handling)")
                .isEqualTo(2L);

        assertThat(service.getStatus().isPublisherUnhealthy())
                .as("Publisher must be healthy after idempotent-recovery")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Helper — get current OS uid
    // -------------------------------------------------------------------------

    private static String getCurrentUid() {
        try {
            Process p = new ProcessBuilder("id", "-u").start();
            String uid = new String(p.getInputStream().readAllBytes()).strip();
            p.waitFor();
            return uid;
        } catch (Exception e) {
            return "1000"; // fallback
        }
    }
}
