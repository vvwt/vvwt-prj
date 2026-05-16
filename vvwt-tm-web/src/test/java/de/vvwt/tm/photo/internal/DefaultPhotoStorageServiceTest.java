// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.photo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.vvwt.tm.photo.PhotoFileMetadata;
import de.vvwt.tm.photo.PhotoFormatException;
import de.vvwt.tm.photo.PhotoSizeException;
import de.vvwt.tm.photo.PhotoStorageConfig;
import de.vvwt.tm.photo.PhotoStorageException;
import de.vvwt.tm.photo.PhotoStorageService;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Q-1a TDD RED-first unit tests for {@link DefaultPhotoStorageService} (E36S01).
 *
 * <p>This test suite replaces the deleted Snapshot-Driven {@code DefaultPhotoStorageServiceTest}
 * (E23S01). Per DEC-41 §3 hierarchy clause (1): all tests are authored RED-first against absent
 * implementation code. The delete commit {@code 922ab1d} established the global RED baseline — this
 * file was written while the implementation classes were absent.
 *
 * <p>Per DEC-36, this test class is in the SAME Java package as {@link DefaultPhotoStorageService}
 * ({@code de.vvwt.tm.photo.internal}). Same-package tests MAY white-box reference the concrete
 * implementation class — no cross-package DEC-36 constraint applies here.
 *
 * <p>Tests cover: upload/retrieve/delete/hasPhoto/format-validation/size-validation/ unknown-entity
 * paths (21 test methods) per AC-DEC41-FRESH-RED-FIRST-TESTS.
 *
 * @see DefaultPhotoStorageService
 * @since E36S01
 */
@DisplayName("DefaultPhotoStorageService — Q-1a TDD unit tests (E36S01)")
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
        config.setMaxSizeBytes(5L * 1024 * 1024);

        tournamentRepo = Mockito.mock(TournamentRepository.class);
        teamRepo = Mockito.mock(TeamRepository.class);

        service = new DefaultPhotoStorageService(config, tournamentRepo, teamRepo);

        Tournament tournament = Mockito.mock(Tournament.class);
        when(tournamentRepo.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        Team team = new Team();
        team.setId(TEAM_ID);
        team.setTournamentId(TOURNAMENT_ID);
        when(teamRepo.findById(TEAM_ID)).thenReturn(Optional.of(team));
    }

    // =========================================================================
    // PhotoFileMetadata record — field order, types, names
    // =========================================================================

    @Test
    @DisplayName("PhotoFileMetadata record has (filename, sizeBytes, uploadedAt) constructor")
    void photoFileMetadataRecordConstructor() {
        // AC-RECORD-FIELDS-PRESERVED: canonical constructor verbatim
        java.time.Instant now = java.time.Instant.now();
        PhotoFileMetadata meta = new PhotoFileMetadata("photo.jpg", 1024L, now);
        assertThat(meta.filename()).isEqualTo("photo.jpg");
        assertThat(meta.sizeBytes()).isEqualTo(1024L);
        assertThat(meta.uploadedAt()).isEqualTo(now);
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
        assertThat(service.hasPhoto(TOURNAMENT_ID, TEAM_ID)).isTrue();
    }

    // =========================================================================
    // AC7 — Format and size validation
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
        assertThat(result).isNotNull();
    }

    // =========================================================================
    // AC8 — Unknown tournament / team → NoSuchElementException
    // =========================================================================

    @Test
    @DisplayName("AC8: unknown tournament → NoSuchElementException on upload")
    void unknownTournamentThrowsOnUpload() {
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
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("AC8: unknown team → NoSuchElementException on upload")
    void unknownTeamThrowsOnUpload() {
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
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("AC8: team belonging to different tournament → NoSuchElementException")
    void teamBelongingToDifferentTournamentThrows() {
        UUID otherTournament = UUID.randomUUID();
        Tournament otherTmt = Mockito.mock(Tournament.class);
        when(tournamentRepo.findById(otherTournament)).thenReturn(Optional.of(otherTmt));

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        otherTournament,
                                        TEAM_ID,
                                        "p.jpg",
                                        inputStream(SAMPLE_JPEG),
                                        SAMPLE_JPEG.length))
                .isInstanceOf(NoSuchElementException.class);
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

    @Test
    @DisplayName("AC2: retrieve returns metadata with filename and size")
    void retrieveReturnsMetadata() throws Exception {
        service.upload(
                TOURNAMENT_ID, TEAM_ID, "t.jpg", inputStream(SAMPLE_JPEG), SAMPLE_JPEG.length);

        Optional<PhotoStorageService.PhotoResult> result = service.retrieve(TOURNAMENT_ID, TEAM_ID);

        assertThat(result).isPresent();
        assertThat(result.get().metadata()).isNotNull();
        assertThat(result.get().metadata().sizeBytes()).isEqualTo(SAMPLE_JPEG.length);
        result.get().inputStream().close();
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
    // AC-EXCEPTION-CONSTRUCTORS-PRESERVED
    // =========================================================================

    @Test
    @DisplayName("PhotoStorageException: two-arg constructor (message, cause) compiles and works")
    void photoStorageExceptionTwoArgConstructor() {
        Throwable cause = new RuntimeException("io error");
        PhotoStorageException ex = new PhotoStorageException("failed", cause);
        assertThat(ex.getMessage()).isEqualTo("failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("PhotoStorageException: single-arg constructor (message) compiles and works")
    void photoStorageExceptionSingleArgConstructor() {
        PhotoStorageException ex = new PhotoStorageException("failed");
        assertThat(ex.getMessage()).isEqualTo("failed");
        assertThat(ex.getCause()).isNull();
    }

    // =========================================================================
    // AC-CONFIG-BINDING-PRESERVED
    // =========================================================================

    @Test
    @DisplayName("PhotoStorageConfig: getter/setter pairs and defaults preserved")
    void photoStorageConfigGetterSetterPreserved() {
        PhotoStorageConfig cfg = new PhotoStorageConfig();
        // Default maxSizeBytes = 5 MB
        assertThat(cfg.getMaxSizeBytes()).isEqualTo(5L * 1024 * 1024);
        // setDataDir / getDataDir round-trip
        cfg.setDataDir("/tmp/photos");
        assertThat(cfg.getDataDir()).isEqualTo("/tmp/photos");
        // setMaxSizeBytes / getMaxSizeBytes round-trip
        cfg.setMaxSizeBytes(10_000_000L);
        assertThat(cfg.getMaxSizeBytes()).isEqualTo(10_000_000L);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private InputStream inputStream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
