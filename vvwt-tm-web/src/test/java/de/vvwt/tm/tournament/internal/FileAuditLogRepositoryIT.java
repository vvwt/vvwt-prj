// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.AuditLogEntry;
import de.vvwt.tm.tournament.AuditLogRepository;
import de.vvwt.tm.tournament.SetState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for file-based {@link AuditLogRepository} implementation (E55S13).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link DefaultAuditLogRepository} ({@code FileAuditLogRepository}
 * at RED-commit time) did not exist at commit time — satisfying DEC-22 Iron Law
 * (AC-GOVERNANCE-DEC-22-RED-FIRST, AC-TEST-RED-FIRST-WRITE-PERSISTED, AC-TEST-RED-FIRST-READ-API).
 *
 * <h2>DEC-26 analogue compliance (AC-GOVERNANCE-DEC-26-ANALOGUE)</h2>
 *
 * <ol>
 *   <li>No schema-from-migration dependency (file-based; no H2 table) — N/A.
 *   <li>Independent persistence verifier: write-path tests verify written rows via {@code
 *       Files.readAllLines(...)} + {@code ObjectMapper.readTree(...)} — NOT via the DAO's own
 *       {@code findBy*} methods.
 *   <li>Read/write decoupling: read-path tests insert fixture data via {@code Files.write(...)}
 *       (DEC-26 Rule 3 analogue) — NOT by calling {@code save(...)}.
 * </ol>
 *
 * <h2>Module scope (AC-GOVERNANCE-DEC-38-IT-FRAMEWORK)</h2>
 *
 * <p>Uses {@code @ApplicationModuleTest(mode = ALL_DEPENDENCIES)} targeting the {@code tournament}
 * bounded-context module per DEC-38 Clause A. {@code webEnvironment = NONE} (no HTTP needed).
 * {@link PlatformTransactionManager} is available within {@code ALL_DEPENDENCIES} mode — satisfies
 * afterCommit-test prerequisites.
 *
 * @see AuditLogRepository
 * @see DefaultAuditLogRepository
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (Rule 2+3 analogues)</a>
 * @see <a href="DEC-38">DEC-38 — @ApplicationModuleTest canon for tournament module</a>
 * @see <a href="E55S13">E55S13 — AC-TEST-RED-FIRST-WRITE-PERSISTED, AC-TEST-RED-FIRST-READ-API,
 *     AC-TEST-PER-TOURNAMENT-SEPARATION, AC-TEST-AFTERCOMMIT-COMMIT-SUCCESS,
 *     AC-TEST-AFTERCOMMIT-ROLLBACK-SKIPS, AC-TEST-CONCURRENT-WRITE-SAMETOURNAMENT,
 *     AC-TEST-RESTART-RECOVERY</a>
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class FileAuditLogRepositoryIT {

    // Static temp dir — created once per JVM, before Spring context initialization.
    // @TempDir on static fields is injected after @DynamicPropertySource resolution with
    // @ApplicationModuleTest, causing NPE. Manual static init avoids the ordering issue.
    static final Path sharedTempDir;

    static {
        try {
            sharedTempDir = Files.createTempDirectory("FileAuditLogRepositoryIT-");
            sharedTempDir.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to create temp dir for FileAuditLogRepositoryIT", e);
        }
    }

    @DynamicPropertySource
    static void auditLogDataDir(DynamicPropertyRegistry registry) {
        registry.add("tm.audit-log.data-dir", () -> sharedTempDir.toString());
    }

    @Autowired
    @Qualifier("tmAuditLogRepository")
    private AuditLogRepository auditLogRepository;

    @Autowired private PlatformTransactionManager transactionManager;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired
    @Qualifier("tenantRoutingContext")
    private TenantContext tenantContext;

    private UUID tenantId;
    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        tenantId = tenantBinder.bindDefaultTenant();
        txTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        tenantBinder.unbind();
    }

    // -----------------------------------------------------------------------
    // Helper: create a minimal AuditLogEntry for the given tournamentId
    // -----------------------------------------------------------------------

    private AuditLogEntry entry(UUID tournamentId) {
        return new AuditLogEntry(
                tournamentId,
                UUID.randomUUID(),
                UUID.randomUUID(), // matchId
                0, // setIndex
                null, // team1PointsOld
                null, // team2PointsOld
                25, // team1PointsNew
                10, // team2PointsNew
                null, // setStateOld
                SetState.WINNER1.getLegacyCode(), // setStateNew
                "admin",
                "test reason",
                LocalDateTime.of(2026, 5, 14, 10, 0, 0),
                "ADMIN",
                null);
    }

    private Path auditFile(UUID tournamentId) {
        return sharedTempDir
                .resolve("tenants")
                .resolve(tenantId.toString())
                .resolve("audit-log")
                .resolve(tournamentId.toString())
                .resolve("audit.jsonl");
    }

    /** Drain the writer thread by submitting a no-op and waiting. */
    private void drainWriter() throws Exception {
        // Access the writer executor via reflection-free approach: submit a sync task
        // DefaultAuditLogRepository exposes no direct drain API per design.
        // Use Thread.sleep as a conservative drain strategy (writer is single-thread).
        Thread.sleep(200);
    }

    // -----------------------------------------------------------------------
    // AC-TEST-RED-FIRST-WRITE-PERSISTED
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TEST-RED-FIRST-WRITE-PERSISTED: save() → JSONL row appears in file")
    void writePersisted_saveResultsInJsonlRow() throws Exception {
        UUID tournamentId = UUID.randomUUID();
        AuditLogEntry e = entry(tournamentId);

        txTemplate.executeWithoutResult(status -> auditLogRepository.save(e));

        drainWriter();

        Path file = auditFile(tournamentId);
        assertThat(file).exists();

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);

        // Independent persistence verifier: parse raw JSONL, NOT via DAO read methods (DEC-26 Rule
        // 2)
        Map<String, Object> parsed =
                objectMapper.readValue(lines.get(0), new TypeReference<Map<String, Object>>() {});
        assertThat(parsed.get("id").toString()).isEqualTo(e.getId().toString());
        assertThat(parsed.get("matchId").toString()).isEqualTo(e.getMatchId().toString());
        assertThat(parsed.get("tournamentId").toString()).isEqualTo(tournamentId.toString());
        assertThat(parsed.get("team1PointsNew")).isEqualTo(25);
        assertThat(parsed.get("team2PointsNew")).isEqualTo(10);
        assertThat(parsed.get("actorId")).isEqualTo("admin");
    }

    // -----------------------------------------------------------------------
    // AC-TEST-PER-TOURNAMENT-SEPARATION
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TEST-PER-TOURNAMENT-SEPARATION: two tournamentIds → two separate files")
    void perTournamentSeparation_twoTournamentsLandInSeparateFiles() throws Exception {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        AuditLogEntry e1 = entry(t1);
        AuditLogEntry e2 = entry(t2);

        txTemplate.executeWithoutResult(
                status -> {
                    auditLogRepository.save(e1);
                    auditLogRepository.save(e2);
                });

        drainWriter();

        Path file1 = auditFile(t1);
        Path file2 = auditFile(t2);

        assertThat(file1).exists();
        assertThat(file2).exists();
        assertThat(file1).isNotEqualTo(file2);

        List<String> lines1 = Files.readAllLines(file1);
        List<String> lines2 = Files.readAllLines(file2);

        assertThat(lines1).hasSize(1);
        assertThat(lines2).hasSize(1);

        Map<String, Object> parsed1 =
                objectMapper.readValue(lines1.get(0), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> parsed2 =
                objectMapper.readValue(lines2.get(0), new TypeReference<Map<String, Object>>() {});

        // Each file contains only the corresponding tournament's entry
        assertThat(parsed1.get("tournamentId").toString()).isEqualTo(t1.toString());
        assertThat(parsed2.get("tournamentId").toString()).isEqualTo(t2.toString());
        assertThat(parsed1.get("id").toString()).isEqualTo(e1.getId().toString());
        assertThat(parsed2.get("id").toString()).isEqualTo(e2.getId().toString());
    }

    // -----------------------------------------------------------------------
    // AC-TEST-AFTERCOMMIT-COMMIT-SUCCESS
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TEST-AFTERCOMMIT-COMMIT-SUCCESS: TX commits → audit row written to file")
    void afterCommit_commitSuccess_rowWritten() throws Exception {
        UUID tournamentId = UUID.randomUUID();
        AuditLogEntry e = entry(tournamentId);

        txTemplate.executeWithoutResult(status -> auditLogRepository.save(e));

        drainWriter();

        Path file = auditFile(tournamentId);
        assertThat(file).exists();
        assertThat(Files.readAllLines(file)).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // AC-TEST-AFTERCOMMIT-ROLLBACK-SKIPS
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TEST-AFTERCOMMIT-ROLLBACK-SKIPS: TX rolls back → no audit row in file")
    void afterCommit_rollback_noRowWritten() throws Exception {
        UUID tournamentId = UUID.randomUUID();
        AuditLogEntry e = entry(tournamentId);

        txTemplate.executeWithoutResult(
                status -> {
                    auditLogRepository.save(e);
                    status.setRollbackOnly(); // Force rollback
                });

        drainWriter();

        Path file = auditFile(tournamentId);
        // File must either not exist or be empty (no write occurred because afterCommit didn't
        // fire)
        if (Files.exists(file)) {
            assertThat(Files.readAllLines(file)).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // AC-TEST-CONCURRENT-WRITE-SAMETOURNAMENT
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TEST-CONCURRENT-WRITE-SAMETOURNAMENT: 2 threads × 50 writes → 100 valid rows")
    void concurrentWriteSameTournament_100RowsAllValidJson() throws Exception {
        UUID tournamentId = UUID.randomUUID();
        int threadsCount = 2;
        int writesPerThread = 50;

        ExecutorService pool = Executors.newFixedThreadPool(threadsCount);
        CountDownLatch latch = new CountDownLatch(threadsCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threadsCount; t++) {
            final int threadIdx = t;
            futures.add(
                    pool.submit(
                            () -> {
                                latch.countDown();
                                try {
                                    latch.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                // Each thread binds its own tenant context (ThreadLocal-based)
                                TenantContext.Scope scope = tenantContext.bind(tenantId);
                                try {
                                    for (int i = 0; i < writesPerThread; i++) {
                                        final int idx = i;
                                        txTemplate.executeWithoutResult(
                                                status ->
                                                        auditLogRepository.save(
                                                                new AuditLogEntry(
                                                                        tournamentId,
                                                                        UUID.randomUUID(),
                                                                        UUID.randomUUID(),
                                                                        threadIdx * 100 + idx,
                                                                        null,
                                                                        null,
                                                                        threadIdx,
                                                                        idx,
                                                                        null,
                                                                        SetState.WINNER1
                                                                                .getLegacyCode(),
                                                                        "t" + threadIdx,
                                                                        "i" + idx,
                                                                        LocalDateTime.now(),
                                                                        "ADMIN",
                                                                        null)));
                                    }
                                } finally {
                                    scope.close();
                                }
                            }));
        }

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // Drain writer thread (all 100 writes)
        Thread.sleep(500);

        Path file = auditFile(tournamentId);
        assertThat(file).exists();

        List<String> lines = Files.readAllLines(file);
        // Filter blank lines
        List<String> nonBlank = lines.stream().filter(l -> !l.isBlank()).toList();

        assertThat(nonBlank).hasSize(100);

        // Each line must be valid JSON (AC: presence + correctness, not strict ordering)
        for (String line : nonBlank) {
            Map<String, Object> parsed =
                    objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
            assertThat(parsed).containsKey("tournamentId");
            assertThat(parsed).containsKey("id");
            assertThat(parsed.get("tournamentId").toString()).isEqualTo(tournamentId.toString());
        }
    }

    // -----------------------------------------------------------------------
    // AC-TEST-RESTART-RECOVERY
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "AC-TEST-RESTART-RECOVERY: pre-existing file content preserved on fresh bean"
                    + " instantiation")
    void restartRecovery_preExistingFilePreserved() throws Exception {
        UUID tournamentId = UUID.randomUUID();

        // Write "old content" directly (simulating prior JVM session)
        Path file = auditFile(tournamentId);
        Files.createDirectories(file.getParent());
        String oldContent = "{\"id\":\"old-entry\",\"tournamentId\":\"" + tournamentId + "\"}\n";
        Files.writeString(file, oldContent);

        // Now save via the repository (lazy-open should append, not truncate)
        AuditLogEntry newEntry = entry(tournamentId);
        txTemplate.executeWithoutResult(status -> auditLogRepository.save(newEntry));

        drainWriter();

        List<String> lines = Files.readAllLines(file);
        List<String> nonBlank = lines.stream().filter(l -> !l.isBlank()).toList();

        // Old content preserved + new entry appended
        assertThat(nonBlank).hasSizeGreaterThanOrEqualTo(2);
        // First line is the old content
        assertThat(nonBlank.get(0)).contains("old-entry");
        // At least one subsequent line contains the new entry id
        boolean found =
                nonBlank.stream().skip(1).anyMatch(l -> l.contains(newEntry.getId().toString()));
        assertThat(found).isTrue();
    }
}
