package de.vvwt.tm.photo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.vvwt.tm.photo.PhotoFileMetadata;
import de.vvwt.tm.photo.PhotoFormatException;
import de.vvwt.tm.photo.PhotoSizeException;
import de.vvwt.tm.photo.PhotoStorageConfig;
import de.vvwt.tm.photo.PhotoStorageService;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Unit tests for {@link DefaultPhotoStorageService} (E12S02, E23S01).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.photo.PhotoStorageServiceImplTest}. Serves as the
 * regression gate for the Q-1b whole-class relocation per DEC-22 §refactor-clause
 * (AC-TESTING-QB-NO-RED). No new RED-first tests are required for the relocation itself; the
 * relocated test suite must remain GREEN.
 *
 * <p>This test class is in the SAME Java package as {@link DefaultPhotoStorageService} ({@code
 * de.vvwt.tm.photo.internal}). Per DEC-36, same-package tests MAY white-box reference the concrete
 * implementation class. The DEC-36 rule for different-package tests applies to cross-module
 * consumers of {@link de.vvwt.tm.photo.PhotoStorageService} (the public interface), not to this
 * same-package regression gate.
 *
 * <p>Verifies validation logic, filesystem storage, and error paths without requiring a Spring
 * context or real database — dependencies are mocked.
 *
 * @see DefaultPhotoStorageService
 * @since E12S02
 */
@DisplayName("DefaultPhotoStorageService unit tests — E12S02 / E23S01")
class DefaultPhotoStorageServiceTest {

    @TempDir Path tempDir;

    private PhotoStorageConfig config;
    private TournamentRepository tournamentRepo;
    private TeamRepository teamRepo;
    private DefaultPhotoStorageService service;

    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final byte[] SAMPLE_JPEG =
            new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};

    @BeforeEach
    void setUp() {
        config = new PhotoStorageConfig();
        config.setDataDir(tempDir.toString());
        config.setMaxSizeBytes(5L * 1024 * 1024); // 5 MB

        tournamentRepo = Mockito.mock(TournamentRepository.class);
        teamRepo = Mockito.mock(TeamRepository.class);

        service = new DefaultPhotoStorageService(config, tournamentRepo, teamRepo);

        // Default: valid tournament and team
        Tournament tournament = Mockito.mock(Tournament.class);
        when(tournamentRepo.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        Team team = new Team();
        team.setId(TEAM_ID);
        team.setTournamentId(TOURNAMENT_ID);
        when(teamRepo.findById(TEAM_ID)).thenReturn(Optional.of(team));
    }

    // =========================================================================
    // AC1 — Upload
    // =========================================================================

    @Test
    @DisplayName("AC1: upload JPEG returns metadata with filename and size")
    void uploadJpegReturnsMetadata() {
        PhotoFileMetadata result =
                service.upload(
                        TOURNAMENT_ID,
                        TEAM_ID,
                        "team.jpg",
                        inputStream(SAMPLE_JPEG),
                        SAMPLE_JPEG.length);

        assertThat(result.filename()).isEqualTo("team.jpg");
        assertThat(result.sizeBytes()).isEqualTo(SAMPLE_JPEG.length);
        assertThat(result.uploadedAt()).isNotNull();
    }

    @Test
    @DisplayName("AC1: upload PNG is accepted")
    void uploadPngIsAccepted() {
        byte[] pngBytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        PhotoFileMetadata result =
                service.upload(
                        TOURNAMENT_ID, TEAM_ID, "team.png", inputStream(pngBytes), pngBytes.length);

        assertThat(result.filename()).isEqualTo("team.png");
    }

    @Test
    @DisplayName("AC1: upload .jpeg extension is accepted")
    void uploadJpegExtensionIsAccepted() {
        PhotoFileMetadata result =
                service.upload(
                        TOURNAMENT_ID,
                        TEAM_ID,
                        "photo.jpeg",
                        inputStream(SAMPLE_JPEG),
                        SAMPLE_JPEG.length);

        assertThat(result.filename()).isEqualTo("photo.jpeg");
    }

    @Test
    @DisplayName("AC1: second upload replaces first photo")
    void uploadReplacesPreviousPhoto() {
        service.upload(
                TOURNAMENT_ID, TEAM_ID, "old.jpg", inputStream(SAMPLE_JPEG), SAMPLE_JPEG.length);
        byte[] newContent = new byte[] {0x01, 0x02};
        PhotoFileMetadata result =
                service.upload(
                        TOURNAMENT_ID,
                        TEAM_ID,
                        "new.jpg",
                        inputStream(newContent),
                        newContent.length);

        assertThat(result.sizeBytes()).isEqualTo(newContent.length);
        // Only one photo should exist after replacement
        assertThat(service.hasPhoto(TOURNAMENT_ID, TEAM_ID)).isTrue();
    }

    // =========================================================================
    // AC7 — Validation
    // =========================================================================

    @Test
    @DisplayName("AC7: non-image filename (.pdf) → PhotoFormatException")
    void rejectNonImageFormat() {
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        TOURNAMENT_ID,
                                        TEAM_ID,
                                        "doc.pdf",
                                        inputStream(SAMPLE_JPEG),
                                        SAMPLE_JPEG.length))
                .isInstanceOf(PhotoFormatException.class)
                .hasMessageContaining("JPEG")
                .hasMessageContaining("PNG");
    }

    @Test
    @DisplayName("AC7: null filename → PhotoFormatException")
    void rejectNullFilename() {
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        TOURNAMENT_ID,
                                        TEAM_ID,
                                        null,
                                        inputStream(SAMPLE_JPEG),
                                        SAMPLE_JPEG.length))
                .isInstanceOf(PhotoFormatException.class);
    }

    @Test
    @DisplayName("AC7: file exceeding 5 MB limit → PhotoSizeException")
    void rejectOversizedFile() {
        long oversizeBytes = 5L * 1024 * 1024 + 1;
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        TOURNAMENT_ID,
                                        TEAM_ID,
                                        "big.jpg",
                                        inputStream(SAMPLE_JPEG),
                                        oversizeBytes))
                .isInstanceOf(PhotoSizeException.class)
                .hasMessageContaining("5 MB");
    }

    @Test
    @DisplayName("AC7: file exactly at 5 MB limit is accepted")
    void acceptFileAtSizeLimit() {
        long exactLimit = 5L * 1024 * 1024;
        PhotoFileMetadata result =
                service.upload(
                        TOURNAMENT_ID, TEAM_ID, "edge.jpg", inputStream(SAMPLE_JPEG), exactLimit);
        // size reported from filesystem — 4 bytes actually written
        assertThat(result).isNotNull();
    }

    // =========================================================================
    // AC8 — Unknown tournament/team → NoSuchElementException
    // =========================================================================

    @Test
    @DisplayName("AC8: unknown tournament → NoSuchElementException")
    void unknownTournamentThrows() {
        UUID unknownTournament = UUID.randomUUID();
        when(tournamentRepo.findById(unknownTournament)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        unknownTournament,
                                        TEAM_ID,
                                        "p.jpg",
                                        inputStream(SAMPLE_JPEG),
                                        SAMPLE_JPEG.length))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("AC8: unknown team → NoSuchElementException")
    void unknownTeamThrows() {
        UUID unknownTeam = UUID.randomUUID();
        when(teamRepo.findById(unknownTeam)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        TOURNAMENT_ID,
                                        unknownTeam,
                                        "p.jpg",
                                        inputStream(SAMPLE_JPEG),
                                        SAMPLE_JPEG.length))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("AC8: team belonging to different tournament → NoSuchElementException")
    void teamBelongingToDifferentTournamentThrows() {
        UUID otherTournament = UUID.randomUUID();
        Tournament otherTmt = Mockito.mock(Tournament.class);
        when(tournamentRepo.findById(otherTournament)).thenReturn(Optional.of(otherTmt));
        // TEAM_ID belongs to TOURNAMENT_ID, not otherTournament

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        otherTournament,
                                        TEAM_ID,
                                        "p.jpg",
                                        inputStream(SAMPLE_JPEG),
                                        SAMPLE_JPEG.length))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // =========================================================================
    // AC2 — Retrieve
    // =========================================================================

    @Test
    @DisplayName("AC2: retrieve after upload returns content and correct MIME type (JPEG)")
    void retrieveAfterUploadJpeg() throws Exception {
        service.upload(
                TOURNAMENT_ID, TEAM_ID, "t.jpg", inputStream(SAMPLE_JPEG), SAMPLE_JPEG.length);

        Optional<PhotoStorageService.PhotoResult> result = service.retrieve(TOURNAMENT_ID, TEAM_ID);

        assertThat(result).isPresent();
        assertThat(result.get().contentType()).isEqualTo("image/jpeg");
        result.get().inputStream().close();
    }

    @Test
    @DisplayName("AC2: retrieve for PNG returns image/png content type")
    void retrievePngHasPngContentType() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        service.upload(TOURNAMENT_ID, TEAM_ID, "t.png", inputStream(png), png.length);

        Optional<PhotoStorageService.PhotoResult> result = service.retrieve(TOURNAMENT_ID, TEAM_ID);

        assertThat(result).isPresent();
        assertThat(result.get().contentType()).isEqualTo("image/png");
        result.get().inputStream().close();
    }

    @Test
    @DisplayName("AC2: retrieve returns empty when no photo uploaded")
    void retrieveEmptyWhenNoPhoto() {
        Optional<PhotoStorageService.PhotoResult> result = service.retrieve(TOURNAMENT_ID, TEAM_ID);
        assertThat(result).isEmpty();
    }

    // =========================================================================
    // AC3 — Delete
    // =========================================================================

    @Test
    @DisplayName("AC3: delete after upload returns true and photo is gone")
    void deleteAfterUploadReturnsTrue() {
        service.upload(
                TOURNAMENT_ID, TEAM_ID, "t.jpg", inputStream(SAMPLE_JPEG), SAMPLE_JPEG.length);

        boolean deleted = service.delete(TOURNAMENT_ID, TEAM_ID);

        assertThat(deleted).isTrue();
        assertThat(service.hasPhoto(TOURNAMENT_ID, TEAM_ID)).isFalse();
    }

    @Test
    @DisplayName("AC3: delete when no photo returns false")
    void deleteWhenNoPhotoReturnsFalse() {
        boolean deleted = service.delete(TOURNAMENT_ID, TEAM_ID);
        assertThat(deleted).isFalse();
    }

    // =========================================================================
    // AC4 — hasPhoto
    // =========================================================================

    @Test
    @DisplayName("AC4: hasPhoto returns false when no photo uploaded")
    void hasPhotoFalseByDefault() {
        assertThat(service.hasPhoto(TOURNAMENT_ID, TEAM_ID)).isFalse();
    }

    @Test
    @DisplayName("AC4: hasPhoto returns true after upload")
    void hasPhotoTrueAfterUpload() {
        service.upload(
                TOURNAMENT_ID, TEAM_ID, "t.jpg", inputStream(SAMPLE_JPEG), SAMPLE_JPEG.length);
        assertThat(service.hasPhoto(TOURNAMENT_ID, TEAM_ID)).isTrue();
    }

    @Test
    @DisplayName("AC4: hasPhoto returns false after delete")
    void hasPhotoFalseAfterDelete() {
        service.upload(
                TOURNAMENT_ID, TEAM_ID, "t.jpg", inputStream(SAMPLE_JPEG), SAMPLE_JPEG.length);
        service.delete(TOURNAMENT_ID, TEAM_ID);
        assertThat(service.hasPhoto(TOURNAMENT_ID, TEAM_ID)).isFalse();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private InputStream inputStream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
