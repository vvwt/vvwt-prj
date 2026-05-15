package de.vvwt.tm.tournament.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.AuditLogConfig;
import de.vvwt.tm.tournament.AuditLogEntry;
import de.vvwt.tm.tournament.AuditLogRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * File-based, JSONL append-only implementation of {@link AuditLogRepository} (E55S13).
 *
 * <p>Replaces the former H2-backed {@code DefaultAuditLogRepository} (deleted at E55S13 atomic
 * cutover). Belt-and-suspenders defense against H2 MVStore-class data-loss for the audit trail
 * (DEC-14 §requirement (a) — correction-traceability is the single-most-important durability
 * obligation).
 *
 * <h2>Write semantics</h2>
 *
 * <p>Writes are registered via {@link TransactionSynchronizationManager} {@code afterCommit()}
 * hook. The actual file IO is performed asynchronously on a single writer thread (off the request
 * path). If the surrounding Spring transaction rolls back, {@code afterCommit()} does NOT fire and
 * no audit row is written (per Brief T-9 — D-9 contract). No inner Spring transaction is opened in
 * the writer thread (FILE IO only, structurally distinct from the E55 silent-rollback bug-class).
 *
 * <h2>File layout</h2>
 *
 * <pre>
 * {@code <data-dir>/tenants/<tenant>/audit-log/<tournament-id>/audit.jsonl}
 * </pre>
 *
 * <h2>Persistence guarantee</h2>
 *
 * <p>Per-write {@code FileChannel.force(true)} (fsync) in the writer thread ensures durability.
 * Writer-thread failure modes: queue overflow → WARN + DROP; IO error → WARN + DROP (writer
 * continues); JVM shutdown → @PreDestroy drains up to 30 s then shuts down.
 *
 * <h2>File permissions (POSIX)</h2>
 *
 * <p>On POSIX filesystems: directories are created {@code 0700} (owner rwx only); files are created
 * {@code 0600} (owner rw only). On Windows: default ACL inherited from parent directory.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The single-thread writer executor serializes all appends through a bounded queue (capacity
 * 1024). The {@link FileChannel} is accessed exclusively from the writer thread — no
 * synchronization on the channel itself is needed. The channel cache ({@link ConcurrentHashMap}) is
 * thread-safe for concurrent lookups from the writer thread and from {@link #findBy*} reads.
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-14 — file-based carve-out (2026-05-14 amendment); persistence via FileChannel+Jackson
 *   <li>DEC-21/DEC-22 — TDD RED-first; renamed from {@code FileAuditLogRepository} at atomic
 *       cutover per AC-IMPL-LEGACY-REMOVAL
 *   <li>DEC-35 — implements public interface {@link AuditLogRepository};
 *       {@code @Repository("tmAuditLogRepository")} (Naming canon: Default{Foo}Repository)
 *   <li>DEC-58 — universal interface mandate
 * </ul>
 *
 * @see AuditLogRepository
 * @see AuditLogConfig
 * @see TenantTournamentKey
 * @see <a href="E55S13">E55S13 — AC-IMPL-NEW-FILEAUDITLOGREPOSITORY</a>
 */
@Repository("tmAuditLogRepository")
public class DefaultAuditLogRepository implements AuditLogRepository {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuditLogRepository.class);

    private static final String WRITER_THREAD_NAME = "audit-log-writer";
    private static final int QUEUE_CAPACITY = 1024;
    private static final String AUDIT_JSONL_FILENAME = "audit.jsonl";

    private static final boolean IS_POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIR_PERMISSIONS =
            IS_POSIX ? PosixFilePermissions.fromString("rwx------") : Set.of();
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            IS_POSIX ? PosixFilePermissions.fromString("rw-------") : Set.of();

    private final AuditLogConfig auditLogConfig;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    /** Single-thread writer executor with bounded queue (capacity 1024). */
    private final ExecutorService writerExecutor;

    /** Cache of open FileChannels, keyed by (tenantId, tournamentId). Lazily populated. */
    private final ConcurrentHashMap<TenantTournamentKey, FileChannel> channelCache =
            new ConcurrentHashMap<>();

    public DefaultAuditLogRepository(
            AuditLogConfig auditLogConfig, TenantContext tenantContext, ObjectMapper objectMapper) {
        this.auditLogConfig = auditLogConfig;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.writerExecutor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        queue,
                        r -> {
                            Thread t = new Thread(r, WRITER_THREAD_NAME);
                            t.setDaemon(false);
                            return t;
                        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Registers an {@code afterCommit()} synchronization on the current Spring transaction. The
     * actual file IO is enqueued to the writer thread after commit. On rollback: no write occurs.
     *
     * <p>Captures the tenant ID on the calling thread (the request thread) because the writer
     * thread does not share the caller's {@code ThreadLocal} binding (per DEC-37 async-propagation
     * surface documentation in {@link TenantContext}).
     *
     * @throws IllegalStateException if no Spring transaction is active
     */
    @Override
    public AuditLogEntry save(AuditLogEntry entry) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "audit-log save requires an active @Transactional context; "
                            + "tests must use TransactionTemplate or @Transactional test method "
                            + "(AC-IMPL-TX-AFTERCOMMIT-WIRING, E55S13)");
        }
        // Capture tenant on the calling (request) thread before crossing to async boundary
        final UUID capturedTenantId = tenantContext.current();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        enqueueWrite(capturedTenantId, entry);
                    }
                });
        return entry;
    }

    /**
     * Enqueues a write task to the writer thread. On queue overflow: WARN log + DROP (no block).
     *
     * <p>Uses {@link ThreadPoolExecutor} with bounded queue — rejection is signalled by the thread
     * pool's {@code RejectedExecutionException} when the queue is full. We use offer-based
     * submission via a wrapper to avoid blocking on {@code put(...)}.
     */
    private void enqueueWrite(UUID tenantId, AuditLogEntry entry) {
        // Submit to the bounded executor; if queue is full, the executor's
        // CallerRunsPolicy or AbortPolicy fires — we wrap to detect queue overflow.
        LinkedBlockingQueue<?> queue =
                (LinkedBlockingQueue<?>) ((ThreadPoolExecutor) writerExecutor).getQueue();
        if (queue.remainingCapacity() == 0) {
            log.warn(
                    "[audit-log] queue-full tournament={} match={} setIndex={} — audit row DROPPED",
                    entry.getTournamentId(),
                    entry.getMatchId(),
                    entry.getSetIndex());
            return;
        }
        writerExecutor.submit(() -> doWrite(tenantId, entry));
    }

    /**
     * Performs the actual file IO (runs on the writer thread). Resolves the path, lazily opens the
     * FileChannel, serializes the entry as JSON, writes + fsyncs.
     *
     * <p>No Spring transaction is opened in this method (FILE IO only — per Brief T-9).
     */
    private void doWrite(UUID tenantId, AuditLogEntry entry) {
        try {
            TenantTournamentKey key = new TenantTournamentKey(tenantId, entry.getTournamentId());
            FileChannel channel = channelCache.computeIfAbsent(key, k -> openChannel(k));
            if (channel == null) {
                return; // openChannel already logged the error
            }

            byte[] jsonBytes = objectMapper.writeValueAsBytes(toMap(entry));
            // Write JSON line + newline as a single buffer
            ByteBuffer buffer = ByteBuffer.allocate(jsonBytes.length + 1);
            buffer.put(jsonBytes);
            buffer.put((byte) '\n');
            buffer.flip();

            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);

        } catch (IOException e) {
            log.warn(
                    "[audit-log] write-failed tournament={} match={} setIndex={} cause={}",
                    entry.getTournamentId(),
                    entry.getMatchId(),
                    entry.getSetIndex(),
                    e.toString());
        } catch (Exception e) {
            log.warn(
                    "[audit-log] unexpected-error tournament={} match={} setIndex={} cause={}",
                    entry.getTournamentId(),
                    entry.getMatchId(),
                    entry.getSetIndex(),
                    e.toString());
        }
    }

    /**
     * Lazily opens (or creates) the FileChannel for the given key. Called from the writer thread.
     *
     * @return the FileChannel, or null if an IO error occurred (already logged)
     */
    private FileChannel openChannel(TenantTournamentKey key) {
        try {
            Path dir = resolveDir(key.tenantId(), key.tournamentId());
            createDirectoryOwnerOnly(dir);
            Path file = dir.resolve(AUDIT_JSONL_FILENAME);
            if (!Files.exists(file)) {
                createFileOwnerOnly(file);
            }
            return FileChannel.open(file, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        } catch (IOException e) {
            log.warn(
                    "[audit-log] open-failed tenantId={} tournamentId={} cause={}",
                    key.tenantId(),
                    key.tournamentId(),
                    e.toString());
            return null;
        }
    }

    private void createDirectoryOwnerOnly(Path dir) throws IOException {
        if (IS_POSIX) {
            FileAttribute<Set<PosixFilePermission>> attr =
                    PosixFilePermissions.asFileAttribute(DIR_PERMISSIONS);
            Files.createDirectories(dir, attr);
        } else {
            Files.createDirectories(dir);
        }
    }

    private void createFileOwnerOnly(Path file) throws IOException {
        if (IS_POSIX) {
            FileAttribute<Set<PosixFilePermission>> attr =
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS);
            Files.createFile(file, attr);
        } else {
            Files.createFile(file);
        }
    }

    /**
     * Resolves the directory path for a given (tenantId, tournamentId) pair.
     *
     * <p>Layout: {@code <data-dir>/tenants/<tenant>/audit-log/<tournament-id>}. No user-input flows
     * into the path — tenantId and tournamentId are server-side UUIDs (AC-SEC-NO-USER-INPUT-
     * IN-FILE-PATH).
     */
    private Path resolveDir(UUID tenantId, UUID tournamentId) {
        return Path.of(auditLogConfig.getDataDir())
                .resolve("tenants")
                .resolve(tenantId.toString())
                .resolve("audit-log")
                .resolve(tournamentId.toString());
    }

    /**
     * Graceful shutdown: drain writer queue (up to 30 s), then close all cached FileChannels.
     *
     * <p>Invoked by Spring on application shutdown (AC-IMPL-WRITER-THREAD-LIFECYCLE,
     * AC-ERROR-HANDLING-JVM-SHUTDOWN-FLUSH).
     */
    @PreDestroy
    public void shutdown() {
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                int remaining = ((ThreadPoolExecutor) writerExecutor).getQueue().size();
                log.warn("[audit-log] shutdown-timeout queue-depth={}", remaining);
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writerExecutor.shutdownNow();
        }
        channelCache.forEach(
                (key, channel) -> {
                    try {
                        channel.force(true);
                        channel.close();
                    } catch (IOException e) {
                        log.warn(
                                "[audit-log] close-failed tenantId={} tournamentId={} cause={}",
                                key.tenantId(),
                                key.tournamentId(),
                                e.toString());
                    }
                });
        channelCache.clear();
    }

    // -------------------------------------------------------------------------
    // JSON serialization helpers
    // -------------------------------------------------------------------------

    /**
     * Converts an {@link AuditLogEntry} to a Map for Jackson serialization. Uses explicit field
     * mapping to ensure stable JSON field order (LinkedHashMap preserves insertion order).
     *
     * <p>POSIX-vs-Windows note: no user-input in field values that form file paths
     * (AC-SEC-NO-USER-INPUT-IN-FILE-PATH). tournamentId and tenantId are server-generated UUIDs. On
     * POSIX filesystems: files created 0600, dirs created 0700
     * (AC-SEC-FILE-PERMISSIONS-RESTRICTIVE). On Windows: default ACL from parent directory.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(AuditLogEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tournamentId", e.getTournamentId() == null ? null : e.getTournamentId().toString());
        m.put("id", e.getId() == null ? null : e.getId().toString());
        m.put("matchId", e.getMatchId() == null ? null : e.getMatchId().toString());
        m.put("setIndex", e.getSetIndex());
        m.put("team1PointsOld", e.getTeam1PointsOld());
        m.put("team2PointsOld", e.getTeam2PointsOld());
        m.put("team1PointsNew", e.getTeam1PointsNew());
        m.put("team2PointsNew", e.getTeam2PointsNew());
        m.put("setStateOld", e.getSetStateOld());
        m.put("setStateNew", e.getSetStateNew());
        m.put("actorId", e.getActorId());
        m.put("reason", e.getReason());
        m.put("changedAt", e.getChangedAt() == null ? null : e.getChangedAt().toString());
        m.put("sourceType", e.getSourceType());
        m.put("sourceDeviceId", e.getSourceDeviceId());
        return m;
    }
}
