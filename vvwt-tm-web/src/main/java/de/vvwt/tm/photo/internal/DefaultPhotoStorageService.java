package de.vvwt.tm.photo.internal;

import de.vvwt.tm.photo.PhotoFileMetadata;
import de.vvwt.tm.photo.PhotoFormatException;
import de.vvwt.tm.photo.PhotoSizeException;
import de.vvwt.tm.photo.PhotoStorageConfig;
import de.vvwt.tm.photo.PhotoStorageException;
import de.vvwt.tm.photo.PhotoStorageService;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Filesystem-backed implementation of {@link PhotoStorageService} (E36S01 Q-1a TDD rebuild).
 *
 * <p>Rebuilt from deleted Q-1b artefact at the same canonical FQN
 * ({@code de.vvwt.tm.photo.internal}) per Brief D-7 Option γ. All method signatures, constructor
 * parameters (including {@code @Qualifier("photoModuleStorageConfig")}), and behavioral semantics
 * are preserved verbatim per AC-C3-SIGNATURE-PRESERVATION.
 *
 * <h2>Storage layout</h2>
 *
 * <p>Photos are stored at {@code {dataDir}/{tournamentId}/{teamId}.{ext}}. Both path components
 * are UUIDs — no path traversal is possible. The extension is derived from the original filename
 * (see {@link #resolveExtension}).
 *
 * <h2>Tenant scoping (DEC-5, DEC-17)</h2>
 *
 * <p>Every method that touches the filesystem first validates tournament and team existence via
 * {@link TournamentRepository#findById} (tenant-scoped) and {@link TeamRepository#findById}
 * (tenant-scoped). A missing result means "not found OR belongs to a different tenant" — both
 * produce {@link NoSuchElementException} → HTTP 404 (no tenant enumeration).
 *
 * <h2>File format validation</h2>
 *
 * <p>Upload rejects files whose original filename does not end with {@code .jpg}, {@code .jpeg},
 * or {@code .png} (case-insensitive). No deep content inspection.
 *
 * <h2>Size limit</h2>
 *
 * <p>Upload rejects files larger than {@link PhotoStorageConfig#getMaxSizeBytes()} (default 5 MB).
 *
 * <p>Historical provenance: originally E12S02 as {@code PhotoStorageServiceImpl};
 * renamed + relocated to this module by E23S01 (Q-1b);
 * rebuilt Q-1a RED-first by E36S01 per DEC-22 Iron Law + DEC-41 §3 hierarchy clause (1).
 *
 * @see PhotoStorageService
 * @see PhotoStorageConfig
 * @since E36S01
 */
@Primary
@Service
public class DefaultPhotoStorageService implements PhotoStorageService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPhotoStorageService.class);

    /** Accepted photo extensions (lowercase). */
    private static final List<String> ACCEPTED_EXTENSIONS = List.of(".jpg", ".jpeg", ".png");

    private final PhotoStorageConfig config;
    private final TournamentRepository tournamentRepository;
    private final TeamRepository teamRepository;

    public DefaultPhotoStorageService(
            @Qualifier("photoModuleStorageConfig") PhotoStorageConfig config,
            TournamentRepository tournamentRepository,
            TeamRepository teamRepository) {
        this.config = config;
        this.tournamentRepository = tournamentRepository;
        this.teamRepository = teamRepository;
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    @Override
    public PhotoFileMetadata upload(
            UUID tournamentId,
            UUID teamId,
            String filename,
            InputStream inputStream,
            long sizeBytes) {
        requireTournamentInTenant(tournamentId);
        requireTeamInTournament(tournamentId, teamId);
        validateFormat(filename);
        validateSize(sizeBytes, filename);

        String ext = resolveExtension(filename);
        Path targetFile = photoFilePath(tournamentId, teamId, ext);
        ensureParentDirectory(targetFile);

        // If a previous photo with a different extension exists, delete it first.
        deleteExistingPhoto(tournamentId, teamId);

        try {
            Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new PhotoStorageException(
                    "Failed to store photo for tournament="
                            + tournamentId
                            + " team="
                            + teamId
                            + ": "
                            + ex.getMessage(),
                    ex);
        }

        long actualSize = readSize(targetFile);
        Instant uploadedAt = readLastModified(targetFile);

        log.info(
                "[tm-photo] Stored photo: tournament={} team={} filename={} size={}",
                tournamentId,
                teamId,
                filename,
                actualSize);

        return new PhotoFileMetadata(filename, actualSize, uploadedAt);
    }

    // -------------------------------------------------------------------------
    // Retrieve
    // -------------------------------------------------------------------------

    @Override
    public Optional<PhotoResult> retrieve(UUID tournamentId, UUID teamId) {
        requireTournamentInTenant(tournamentId);
        requireTeamInTournament(tournamentId, teamId);

        Optional<Path> maybePath = findPhotoPath(tournamentId, teamId);
        if (maybePath.isEmpty()) {
            return Optional.empty();
        }

        Path file = maybePath.get();
        String contentType = mimeTypeFromPath(file);
        long size = readSize(file);
        Instant uploadedAt = readLastModified(file);
        PhotoFileMetadata metadata =
                new PhotoFileMetadata(file.getFileName().toString(), size, uploadedAt);

        try {
            InputStream stream = Files.newInputStream(file);
            return Optional.of(new PhotoResult(stream, contentType, metadata));
        } catch (IOException ex) {
            throw new PhotoStorageException(
                    "Failed to open photo stream for tournament="
                            + tournamentId
                            + " team="
                            + teamId
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    @Override
    public boolean delete(UUID tournamentId, UUID teamId) {
        requireTournamentInTenant(tournamentId);
        requireTeamInTournament(tournamentId, teamId);

        Optional<Path> maybePath = findPhotoPath(tournamentId, teamId);
        if (maybePath.isEmpty()) {
            return false;
        }

        Path file = maybePath.get();
        try {
            Files.delete(file);
            log.info("[tm-photo] Deleted photo: tournament={} team={}", tournamentId, teamId);
            return true;
        } catch (IOException ex) {
            throw new PhotoStorageException(
                    "Failed to delete photo for tournament="
                            + tournamentId
                            + " team="
                            + teamId
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    // -------------------------------------------------------------------------
    // hasPhoto (no tournament/team validation — caller already validated)
    // -------------------------------------------------------------------------

    @Override
    public boolean hasPhoto(UUID tournamentId, UUID teamId) {
        return findPhotoPath(tournamentId, teamId).isPresent();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the canonical filesystem path for a team photo with the given extension.
     *
     * <p>Path is {@code {dataDir}/{tournamentId}/{teamId}.{ext}}. Both UUID and extension are safe
     * path components — no path traversal possible.
     */
    private Path photoFilePath(UUID tournamentId, UUID teamId, String ext) {
        return Path.of(config.getDataDir())
                .resolve(tournamentId.toString())
                .resolve(teamId.toString() + ext);
    }

    /**
     * Scans the tournament directory for any existing photo file for the given team.
     *
     * <p>A team can have at most one photo; the extension may vary (.jpg, .jpeg, .png).
     * Returns the first matching file found.
     */
    private Optional<Path> findPhotoPath(UUID tournamentId, UUID teamId) {
        Path dir = Path.of(config.getDataDir()).resolve(tournamentId.toString());
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String teamIdStr = teamId.toString();
        for (String ext : ACCEPTED_EXTENSIONS) {
            Path candidate = dir.resolve(teamIdStr + ext);
            if (Files.exists(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Deletes any existing photo for the given team (regardless of extension).
     *
     * <p>Called before upload to ensure only one photo per team per tournament exists, even if the
     * extension changes (e.g. replacing a .jpg with a .png).
     */
    private void deleteExistingPhoto(UUID tournamentId, UUID teamId) {
        Optional<Path> maybePath = findPhotoPath(tournamentId, teamId);
        if (maybePath.isPresent()) {
            Path existing = maybePath.get();
            try {
                Files.delete(existing);
                log.debug("[tm-photo] Removed old photo before upload: {}", existing);
            } catch (IOException ex) {
                throw new PhotoStorageException(
                        "Failed to remove existing photo for tournament="
                                + tournamentId
                                + " team="
                                + teamId
                                + " before upload: "
                                + ex.getMessage(),
                        ex);
            }
        }
    }

    /**
     * Ensures the tournament photo directory exists, creating it if necessary.
     *
     * @throws PhotoStorageException if directory creation fails
     */
    private void ensureParentDirectory(Path file) {
        Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ex) {
                throw new PhotoStorageException(
                        "Failed to create photo storage directory '"
                                + parent
                                + "': "
                                + ex.getMessage(),
                        ex);
            }
        }
    }

    /**
     * Validates that the original filename ends with an accepted image extension.
     *
     * <p>Only .jpg, .jpeg, and .png are accepted (case-insensitive).
     *
     * @throws PhotoFormatException if the extension is not accepted
     */
    private void validateFormat(String filename) {
        if (filename == null) {
            throw new PhotoFormatException(
                    "Photo filename is required. Only JPEG and PNG images are accepted.");
        }
        String lower = filename.toLowerCase();
        boolean accepted =
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
        if (!accepted) {
            throw new PhotoFormatException(
                    "Unsupported image format. Only JPEG (.jpg, .jpeg) and PNG (.png) files are"
                            + " accepted. Received: "
                            + filename);
        }
    }

    /**
     * Validates the declared file size against the configured limit.
     *
     * <p>Files exceeding the configured limit (default 5 MB) are rejected.
     *
     * @throws PhotoSizeException if the size exceeds the limit
     */
    private void validateSize(long sizeBytes, String filename) {
        long limit = config.getMaxSizeBytes();
        if (sizeBytes > limit) {
            throw new PhotoSizeException(
                    "Photo file '"
                            + filename
                            + "' is too large ("
                            + sizeBytes
                            + " bytes). "
                            + "Maximum allowed size is "
                            + limit
                            + " bytes ("
                            + (limit / 1024 / 1024)
                            + " MB).");
        }
    }

    /**
     * Resolves the file extension from the original filename.
     *
     * <p>Returns the normalized lowercase extension including the leading dot.
     */
    private String resolveExtension(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpeg")) return ".jpeg";
        if (lower.endsWith(".jpg")) return ".jpg";
        return ".png";
    }

    /**
     * Derives the MIME type from the file extension of the given path.
     *
     * @return {@code image/jpeg} or {@code image/png}
     */
    private String mimeTypeFromPath(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            return "image/png";
        }
        // .jpg and .jpeg both map to image/jpeg
        return "image/jpeg";
    }

    /**
     * Validates that the tournament exists and belongs to the active tenant.
     *
     * <p>Uses {@link TournamentRepository#findById} which is tenant-scoped. Returns empty if the
     * tournament does not exist OR belongs to a different tenant — both produce 404.
     *
     * @throws NoSuchElementException if tournament not found or not in active tenant
     */
    private void requireTournamentInTenant(UUID tournamentId) {
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));
    }

    /**
     * Validates that the team exists and belongs to the given tournament (and active tenant).
     *
     * <p>Uses {@link TeamRepository#findById} which is tenant-scoped. Additionally verifies the
     * team belongs to the given tournament.
     *
     * @throws NoSuchElementException if team not found, wrong tenant, or wrong tournament
     */
    private void requireTeamInTournament(UUID tournamentId, UUID teamId) {
        Team team =
                teamRepository
                        .findById(teamId)
                        .orElseThrow(
                                () -> new NoSuchElementException("Team not found: " + teamId));
        if (!tournamentId.equals(team.getTournamentId())) {
            throw new NoSuchElementException(
                    "Team " + teamId + " does not belong to tournament " + tournamentId);
        }
    }

    /**
     * Reads the size of a file.
     *
     * @throws PhotoStorageException if reading the size fails
     */
    private long readSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            throw new PhotoStorageException(
                    "Failed to read file size for '" + file + "': " + ex.getMessage(), ex);
        }
    }

    /**
     * Reads the last-modified timestamp of a file.
     *
     * @throws PhotoStorageException if reading the timestamp fails
     */
    private Instant readLastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException ex) {
            throw new PhotoStorageException(
                    "Failed to read last-modified time for '" + file + "': " + ex.getMessage(), ex);
        }
    }
}
