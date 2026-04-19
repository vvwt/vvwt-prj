package de.vvwt.tm.domain.audio;

import de.vvwt.tm.domain.repo.TournamentRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Filesystem-backed implementation of {@link AudioStorageService}.
 *
 * <p>Story E11S01 — AC1–AC7. Files are stored at {@code
 * {dataDir}/audio/{tournamentId}/{category}.mp3} per AC6 / DEC-15.
 *
 * <h2>Tenant scoping (AC5, DEC-5, DEC-17)</h2>
 *
 * <p>Every method that touches the filesystem first calls {@link
 * TournamentRepository#findById(Object)}, which is tenant-scoped: it returns {@link
 * Optional#empty()} if the tournament does not exist OR belongs to a different tenant. A missing
 * result results in {@link NoSuchElementException} → HTTP 404 (no tenant enumeration).
 *
 * <h2>No DB schema (DEC-14)</h2>
 *
 * <p>Audio metadata is derived from the filesystem on each list operation. No H2 table or Flyway
 * migration is needed for this story.
 *
 * <h2>File format validation (AC7)</h2>
 *
 * <p>Upload rejects files whose declared original filename does not end with {@code .mp3}
 * (case-insensitive). No deep content inspection (magic bytes) — keeping V1 simple. The
 * configurable size limit is enforced by Spring's multipart filter (10 MB default); this class
 * provides an additional guard via the {@code sizeBytes} parameter.
 *
 * @see AudioStorageService
 * @see AudioStorageConfig
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S01.story.md">Story
 *     E11S01</a>
 */
@Service
public class AudioStorageServiceImpl implements AudioStorageService {

    private static final Logger log = LoggerFactory.getLogger(AudioStorageServiceImpl.class);

    /** Maximum allowed upload size in bytes (10 MB default — AC7). */
    static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    private final AudioStorageConfig config;
    private final TournamentRepository tournamentRepository;

    public AudioStorageServiceImpl(
            AudioStorageConfig config, TournamentRepository tournamentRepository) {
        this.config = config;
        this.tournamentRepository = tournamentRepository;
    }

    // -------------------------------------------------------------------------
    // AC1 — Upload
    // -------------------------------------------------------------------------

    @Override
    public AudioFileMetadata upload(
            UUID tournamentId,
            AudioCategory category,
            String filename,
            InputStream inputStream,
            long sizeBytes) {
        requireTournamentInTenant(tournamentId);
        validateMp3Extension(filename);
        validateSize(sizeBytes, filename);

        Path targetFile = audioFilePath(tournamentId, category);
        ensureParentDirectory(targetFile);

        try {
            Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new AudioStorageException(
                    "Failed to store audio file for tournament="
                            + tournamentId
                            + " category="
                            + category
                            + ": "
                            + ex.getMessage(),
                    ex);
        }

        long actualSize = readSize(targetFile);
        Instant uploadedAt = readLastModified(targetFile);

        log.info(
                "[tm-audio] Stored audio file: tournament={} category={} filename={} size={}",
                tournamentId,
                category,
                filename,
                actualSize);

        return new AudioFileMetadata(category, filename, actualSize, uploadedAt);
    }

    // -------------------------------------------------------------------------
    // AC2 — Stream
    // -------------------------------------------------------------------------

    @Override
    public Optional<InputStream> stream(UUID tournamentId, AudioCategory category) {
        requireTournamentInTenant(tournamentId);

        Path file = audioFilePath(tournamentId, category);
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        try {
            return Optional.of(Files.newInputStream(file));
        } catch (IOException ex) {
            throw new AudioStorageException(
                    "Failed to open audio stream for tournament="
                            + tournamentId
                            + " category="
                            + category
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    // -------------------------------------------------------------------------
    // AC3 — List
    // -------------------------------------------------------------------------

    @Override
    public List<AudioFileMetadata> list(UUID tournamentId) {
        requireTournamentInTenant(tournamentId);

        List<AudioFileMetadata> result = new ArrayList<>();
        for (AudioCategory category : AudioCategory.values()) {
            Path file = audioFilePath(tournamentId, category);
            if (Files.exists(file)) {
                long size = readSize(file);
                Instant uploadedAt = readLastModified(file);
                // Filename stored on disk is always {category}.mp3 — the original client
                // filename is not persisted (filesystem path is the source of truth per AC6).
                result.add(
                        new AudioFileMetadata(category, category.toFileName(), size, uploadedAt));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // AC4 — Delete
    // -------------------------------------------------------------------------

    @Override
    public boolean delete(UUID tournamentId, AudioCategory category) {
        requireTournamentInTenant(tournamentId);

        Path file = audioFilePath(tournamentId, category);
        if (!Files.exists(file)) {
            return false;
        }

        try {
            Files.delete(file);
            log.info(
                    "[tm-audio] Deleted audio file: tournament={} category={}",
                    tournamentId,
                    category);
            return true;
        } catch (IOException ex) {
            throw new AudioStorageException(
                    "Failed to delete audio file for tournament="
                            + tournamentId
                            + " category="
                            + category
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the filesystem path for an audio file.
     *
     * <p>AC6: structure is {@code {dataDir}/audio/{tournamentId}/{category}.mp3}. Both {@code
     * tournamentId} (UUID) and {@code category} (enum name) are safe path components — no path
     * traversal is possible.
     *
     * @param tournamentId the tournament UUID
     * @param category the audio category
     * @return the absolute path to the audio file
     */
    private Path audioFilePath(UUID tournamentId, AudioCategory category) {
        return Path.of(config.getDataDir())
                .resolve(tournamentId.toString())
                .resolve(category.toFileName());
    }

    /**
     * Ensures that the tournament exists and belongs to the active tenant.
     *
     * <p>AC5, DEC-5, DEC-17: uses {@link TournamentRepository#findById(Object)} which is
     * tenant-scoped. Returns empty if the tournament is not found OR belongs to a different tenant.
     * In both cases we throw {@link NoSuchElementException} → 404 (no tenant enumeration).
     *
     * @param tournamentId the tournament UUID to validate
     * @throws NoSuchElementException if tournament not found or not in active tenant
     */
    private void requireTournamentInTenant(UUID tournamentId) {
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));
    }

    /**
     * Validates that the original filename ends with {@code .mp3} (case-insensitive).
     *
     * <p>AC7: Non-.mp3 upload → 415. Simple extension check — no deep content inspection.
     *
     * @param filename the original client-provided filename
     * @throws AudioFormatException if the filename does not end with {@code .mp3}
     */
    private void validateMp3Extension(String filename) {
        if (filename == null || !filename.toLowerCase().endsWith(".mp3")) {
            throw new AudioFormatException(
                    "Unsupported audio format. Only .mp3 files are accepted. "
                            + "Received: "
                            + filename);
        }
    }

    /**
     * Validates the declared file size against the configured limit.
     *
     * <p>AC7: File exceeds configurable size limit (default 10 MB) → 413. The Spring multipart
     * filter enforces the limit at the servlet layer as well; this guard provides defense-in-depth
     * at the service layer.
     *
     * @param sizeBytes the declared file size in bytes
     * @param filename the original filename (for the error message)
     * @throws AudioSizeLimitException if {@code sizeBytes} exceeds {@link #MAX_UPLOAD_BYTES}
     */
    private void validateSize(long sizeBytes, String filename) {
        if (sizeBytes > MAX_UPLOAD_BYTES) {
            throw new AudioSizeLimitException(
                    "Audio file '"
                            + filename
                            + "' is too large ("
                            + sizeBytes
                            + " bytes). Maximum allowed size is "
                            + MAX_UPLOAD_BYTES
                            + " bytes (10 MB).");
        }
    }

    /**
     * Creates the parent directory of the target file if it does not already exist.
     *
     * @param file the target file path
     * @throws AudioStorageException if directory creation fails
     */
    private void ensureParentDirectory(Path file) {
        Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ex) {
                throw new AudioStorageException(
                        "Failed to create audio storage directory '"
                                + parent
                                + "': "
                                + ex.getMessage(),
                        ex);
            }
        }
    }

    /**
     * Reads the size of a file from the filesystem.
     *
     * @param file the file path
     * @return the file size in bytes
     * @throws AudioStorageException if reading the size fails
     */
    private long readSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            throw new AudioStorageException(
                    "Failed to read file size for '" + file + "': " + ex.getMessage(), ex);
        }
    }

    /**
     * Reads the last-modified timestamp of a file from the filesystem.
     *
     * @param file the file path
     * @return the last-modified instant
     * @throws AudioStorageException if reading the timestamp fails
     */
    private Instant readLastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException ex) {
            throw new AudioStorageException(
                    "Failed to read last-modified time for '" + file + "': " + ex.getMessage(), ex);
        }
    }
}
