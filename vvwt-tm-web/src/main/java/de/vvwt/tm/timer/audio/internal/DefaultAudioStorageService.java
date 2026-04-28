package de.vvwt.tm.timer.audio.internal;

import de.vvwt.tm.timer.audio.AudioCategory;
import de.vvwt.tm.timer.audio.AudioFileMetadata;
import de.vvwt.tm.timer.audio.AudioFormatException;
import de.vvwt.tm.timer.audio.AudioSizeLimitException;
import de.vvwt.tm.timer.audio.AudioStorageConfig;
import de.vvwt.tm.timer.audio.AudioStorageException;
import de.vvwt.tm.timer.audio.AudioStorageService;
import de.vvwt.tm.tournament.TournamentRepository;
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
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.audio.internal.DefaultAudioStorageService} per DEC-35
 * naming canon ({@code Default*Service}, no {@code Impl} suffix; impl in {@code .internal} per
 * DEC-21 module layout). Corrects the legacy DEC-35 violation ({@code AudioStorageServiceImpl}).
 *
 * <p>Files are stored at {@code {dataDir}/audio/{tournamentId}/{category}.mp3} per E11S01 AC6 /
 * DEC-15. No database table is used — persistence is purely filesystem-based.
 *
 * <h2>Tenant scoping (DEC-5, DEC-17)</h2>
 *
 * <p>Every method that touches the filesystem first calls {@link
 * TournamentRepository#findById(Object)}, which is tenant-scoped: it returns {@link
 * Optional#empty()} if the tournament does not exist OR belongs to a different tenant. A missing
 * result throws {@link NoSuchElementException} → HTTP 404 (no tenant enumeration).
 *
 * <h2>File format validation</h2>
 *
 * <p>Upload rejects files whose declared original filename does not end with {@code .mp3}
 * (case-insensitive). No deep content inspection (magic bytes) — V1 simplicity per legacy.
 *
 * <h2>DEC-22 Iron Law compliance</h2>
 *
 * <p>Fresh Q-1a authoring per DEC-22 TDD Iron Law. All 14 legacy {@code
 * AudioStorageServiceImplTest} tests were Snapshot-Driven per audit (v) aggregate verdict — ZERO
 * reused per DEC-41 §3. Fresh RED-first tests: {@code DefaultAudioStorageServiceTest} (11 methods,
 * E26S02).
 *
 * @see AudioStorageService
 * @see AudioStorageConfig
 * @see <a href="contexts/artefacts/stories/E26S02.story.md">Story E26S02</a>
 */
@Service
public class DefaultAudioStorageService implements AudioStorageService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAudioStorageService.class);

    /** Maximum allowed upload size in bytes (10 MB default — preserves legacy constant). */
    public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    private final AudioStorageConfig config;
    private final TournamentRepository tournamentRepository;

    public DefaultAudioStorageService(
            AudioStorageConfig config, TournamentRepository tournamentRepository) {
        this.config = config;
        this.tournamentRepository = tournamentRepository;
    }

    // -------------------------------------------------------------------------
    // Upload
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
    // Stream
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
    // List
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
                        new AudioFileMetadata(
                                category, categoryFileName(category), size, uploadedAt));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Delete
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
     * tournamentId} (UUID) and {@code category} (enum) are safe path components — no path traversal
     * is possible.
     */
    private Path audioFilePath(UUID tournamentId, AudioCategory category) {
        return Path.of(config.getDataDir())
                .resolve(tournamentId.toString())
                .resolve(categoryFileName(category));
    }

    /**
     * Returns the filename component for a category, e.g. {@code "start.mp3"}.
     *
     * <p>Used internally for filesystem path construction. {@link AudioCategory} does not expose
     * this helper at the public enum level (E26S01 stub did not add it).
     */
    private String categoryFileName(AudioCategory category) {
        return category.name().toLowerCase() + ".mp3";
    }

    /**
     * Ensures that the tournament exists and belongs to the active tenant.
     *
     * <p>DEC-5, DEC-17: {@link TournamentRepository#findById(Object)} is tenant-scoped. Returns
     * empty if the tournament is not found OR belongs to a different tenant. Throws {@link
     * NoSuchElementException} in both cases → 404 (no tenant enumeration).
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
     * <p>Non-.mp3 upload → {@link AudioFormatException} → HTTP 415.
     */
    private void validateMp3Extension(String filename) {
        if (filename == null || !filename.toLowerCase().endsWith(".mp3")) {
            throw new AudioFormatException(
                    "Unsupported audio format. Only .mp3 files are accepted. Received: "
                            + filename);
        }
    }

    /**
     * Validates the declared file size against the configured limit.
     *
     * <p>File exceeds {@link #MAX_UPLOAD_BYTES} → {@link AudioSizeLimitException} → HTTP 413.
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
