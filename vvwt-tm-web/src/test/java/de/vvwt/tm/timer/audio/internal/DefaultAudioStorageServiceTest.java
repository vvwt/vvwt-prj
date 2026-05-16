// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.timer.audio.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.vvwt.tm.timer.audio.AudioCategory;
import de.vvwt.tm.timer.audio.AudioFileMetadata;
import de.vvwt.tm.timer.audio.AudioFormatException;
import de.vvwt.tm.timer.audio.AudioSizeLimitException;
import de.vvwt.tm.timer.audio.AudioStorageConfig;
import de.vvwt.tm.timer.audio.AudioStorageService;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * RED-first unit tests for {@link DefaultAudioStorageService}.
 *
 * <p>Story E26S02 — 11 service-method-coverage ACs per DEC-22 Iron Law. Tests authored RED-first
 * before implementation exists. Legacy {@code AudioStorageServiceImplTest} (14 tests, all
 * Snapshot-Driven per audit (v)) is DELETED and NOT reused per DEC-41 §3.
 *
 * <p>Same-package white-box access per DEC-36: test class in {@code
 * de.vvwt.tm.timer.audio.internal} may reference {@link DefaultAudioStorageService} directly.
 * Service field typed as {@link AudioStorageService} interface where behavior-under-test is the
 * contract; implementation-specific internals verified via filesystem state.
 *
 * @see DefaultAudioStorageService
 * @see <a href="contexts/artefacts/stories/E26S02.story.md">Story E26S02</a>
 */
@DisplayName("DefaultAudioStorageService unit tests — E26S02")
class DefaultAudioStorageServiceTest {

    @TempDir Path tempDir;

    private AudioStorageConfig config;
    private TournamentRepository tournamentRepository;
    private DefaultAudioStorageService service;

    private static final UUID TOURNAMENT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TOURNAMENT_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final byte[] SAMPLE_MP3_BYTES = new byte[] {0x49, 0x44, 0x33, 0x00, 0x00};

    @BeforeEach
    void setUp() {
        config = new AudioStorageConfig();
        config.setDataDir(tempDir.toString());

        tournamentRepository = Mockito.mock(TournamentRepository.class);

        Tournament fakeTournament = new Tournament();
        fakeTournament.setId(TOURNAMENT_ID);

        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(fakeTournament));
        when(tournamentRepository.findById(any(UUID.class)))
                .thenAnswer(
                        invocation -> {
                            UUID id = invocation.getArgument(0);
                            if (TOURNAMENT_ID.equals(id)) {
                                return Optional.of(fakeTournament);
                            }
                            return Optional.empty();
                        });

        service = new DefaultAudioStorageService(config, tournamentRepository);
    }

    // =========================================================================
    // AC-UPLOAD-MP3-SUCCESS
    // =========================================================================

    @Test
    @DisplayName("AC-UPLOAD-MP3-SUCCESS: upload mp3 returns metadata and persists file")
    void uploadMp3SuccessReturnsMetadataAndPersistsFile() throws IOException {
        InputStream input = new ByteArrayInputStream(SAMPLE_MP3_BYTES);

        AudioFileMetadata meta =
                service.upload(
                        TOURNAMENT_ID,
                        AudioCategory.START,
                        "start.mp3",
                        input,
                        SAMPLE_MP3_BYTES.length);

        assertThat(meta).isNotNull();
        assertThat(meta.category()).isEqualTo(AudioCategory.START);
        assertThat(meta.filename()).isEqualTo("start.mp3");
        assertThat(meta.sizeBytes()).isEqualTo(SAMPLE_MP3_BYTES.length);
        assertThat(meta.uploadedAt()).isNotNull();

        // Verify file persisted on disk at expected path
        Path expectedPath = tempDir.resolve(TOURNAMENT_ID.toString()).resolve("start.mp3");
        assertThat(Files.exists(expectedPath)).isTrue();
        assertThat(Files.readAllBytes(expectedPath)).isEqualTo(SAMPLE_MP3_BYTES);
    }

    @Test
    @DisplayName("AC-UPLOAD-MP3-SUCCESS: upload replaces existing file for same category")
    void uploadReplacesExistingFileForSameCategory() throws IOException {
        byte[] firstBytes = new byte[] {0x01, 0x02};
        byte[] secondBytes = SAMPLE_MP3_BYTES;

        service.upload(
                TOURNAMENT_ID,
                AudioCategory.START,
                "first.mp3",
                new ByteArrayInputStream(firstBytes),
                firstBytes.length);
        service.upload(
                TOURNAMENT_ID,
                AudioCategory.START,
                "second.mp3",
                new ByteArrayInputStream(secondBytes),
                secondBytes.length);

        Path expectedPath = tempDir.resolve(TOURNAMENT_ID.toString()).resolve("start.mp3");
        assertThat(Files.readAllBytes(expectedPath)).isEqualTo(secondBytes);
    }

    // =========================================================================
    // AC-UPLOAD-NON-MP3-FORMAT
    // =========================================================================

    @Test
    @DisplayName("AC-UPLOAD-NON-MP3-FORMAT: non-mp3 filename throws AudioFormatException")
    void uploadNonMp3ThrowsAudioFormatException() {
        InputStream input = new ByteArrayInputStream(SAMPLE_MP3_BYTES);

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        TOURNAMENT_ID,
                                        AudioCategory.START,
                                        "audio.txt",
                                        input,
                                        SAMPLE_MP3_BYTES.length))
                .isInstanceOf(AudioFormatException.class);
    }

    // =========================================================================
    // AC-UPLOAD-EXCEEDS-SIZE-LIMIT
    // =========================================================================

    @Test
    @DisplayName(
            "AC-UPLOAD-EXCEEDS-SIZE-LIMIT: file exceeding max size throws AudioSizeLimitException")
    void uploadExceedsSizeLimitThrowsAudioSizeLimitException() {
        InputStream input = new ByteArrayInputStream(SAMPLE_MP3_BYTES);
        long oversized = DefaultAudioStorageService.MAX_UPLOAD_BYTES + 1;

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        TOURNAMENT_ID,
                                        AudioCategory.START,
                                        "start.mp3",
                                        input,
                                        oversized))
                .isInstanceOf(AudioSizeLimitException.class);
    }

    // =========================================================================
    // AC-UPLOAD-CROSS-TENANT-REJECTED
    // =========================================================================

    @Test
    @DisplayName(
            "AC-UPLOAD-CROSS-TENANT-REJECTED: tournament not in tenant throws"
                    + " NoSuchElementException")
    void uploadTournamentNotInTenantThrowsNoSuchElement() {
        InputStream input = new ByteArrayInputStream(SAMPLE_MP3_BYTES);

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        OTHER_TOURNAMENT_ID,
                                        AudioCategory.START,
                                        "start.mp3",
                                        input,
                                        SAMPLE_MP3_BYTES.length))
                .isInstanceOf(NoSuchElementException.class);
    }

    // =========================================================================
    // AC-STREAM-RETURNS-INPUTSTREAM
    // =========================================================================

    @Test
    @DisplayName(
            "AC-STREAM-RETURNS-INPUTSTREAM: existing file returns non-empty Optional with bytes")
    void streamExistingFileReturnsNonEmptyOptional() throws IOException {
        service.upload(
                TOURNAMENT_ID,
                AudioCategory.END,
                "end.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES),
                SAMPLE_MP3_BYTES.length);

        Optional<InputStream> result = service.stream(TOURNAMENT_ID, AudioCategory.END);

        assertThat(result).isPresent();
        try (InputStream is = result.get()) {
            byte[] bytes = is.readAllBytes();
            assertThat(bytes).isEqualTo(SAMPLE_MP3_BYTES);
        }
    }

    // =========================================================================
    // AC-STREAM-RETURNS-EMPTY-NO-FILE
    // =========================================================================

    @Test
    @DisplayName("AC-STREAM-RETURNS-EMPTY-NO-FILE: missing file returns Optional.empty()")
    void streamMissingFileReturnsEmptyOptional() {
        Optional<InputStream> result = service.stream(TOURNAMENT_ID, AudioCategory.PAUSE);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // AC-LIST-ALL-CATEGORIES
    // =========================================================================

    @Test
    @DisplayName(
            "AC-LIST-ALL-CATEGORIES: list with files for all 3 categories returns 3-element list")
    void listWithAllCategoriesReturns3Elements() {
        service.upload(
                TOURNAMENT_ID,
                AudioCategory.START,
                "start.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES),
                SAMPLE_MP3_BYTES.length);
        service.upload(
                TOURNAMENT_ID,
                AudioCategory.END,
                "end.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES),
                SAMPLE_MP3_BYTES.length);
        service.upload(
                TOURNAMENT_ID,
                AudioCategory.PAUSE,
                "pause.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES),
                SAMPLE_MP3_BYTES.length);

        List<AudioFileMetadata> result = service.list(TOURNAMENT_ID);

        assertThat(result).hasSize(3);
        assertThat(result)
                .extracting(AudioFileMetadata::category)
                .containsExactlyInAnyOrder(
                        AudioCategory.START, AudioCategory.END, AudioCategory.PAUSE);
    }

    // =========================================================================
    // AC-LIST-EMPTY-WHEN-NO-FILES
    // =========================================================================

    @Test
    @DisplayName("AC-LIST-EMPTY-WHEN-NO-FILES: no uploaded files returns empty list")
    void listWithNoFilesReturnsEmptyList() {
        List<AudioFileMetadata> result = service.list(TOURNAMENT_ID);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // AC-DELETE-RETURNS-TRUE-WHEN-FILE-EXISTS
    // =========================================================================

    @Test
    @DisplayName(
            "AC-DELETE-RETURNS-TRUE-WHEN-FILE-EXISTS: delete existing file returns true and removes"
                    + " it")
    void deleteExistingFileReturnsTrueAndRemovesFile() {
        service.upload(
                TOURNAMENT_ID,
                AudioCategory.START,
                "start.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES),
                SAMPLE_MP3_BYTES.length);

        boolean result = service.delete(TOURNAMENT_ID, AudioCategory.START);

        assertThat(result).isTrue();
        assertThat(service.stream(TOURNAMENT_ID, AudioCategory.START)).isEmpty();
    }

    // =========================================================================
    // AC-DELETE-RETURNS-FALSE-WHEN-NO-FILE
    // =========================================================================

    @Test
    @DisplayName("AC-DELETE-RETURNS-FALSE-WHEN-NO-FILE: delete missing file returns false")
    void deleteMissingFileReturnsFalse() {
        boolean result = service.delete(TOURNAMENT_ID, AudioCategory.PAUSE);

        assertThat(result).isFalse();
    }

    // =========================================================================
    // AC-CROSS-TENANT-ISOLATION
    // =========================================================================

    @Test
    @DisplayName("AC-CROSS-TENANT-ISOLATION: tournament not visible from different tenant context")
    void crossTenantIsolationThrowsNoSuchElement() {
        // OTHER_TOURNAMENT_ID not in active tenant (tournamentRepo returns empty)
        assertThatThrownBy(() -> service.list(OTHER_TOURNAMENT_ID))
                .isInstanceOf(NoSuchElementException.class);

        assertThatThrownBy(() -> service.stream(OTHER_TOURNAMENT_ID, AudioCategory.START))
                .isInstanceOf(NoSuchElementException.class);

        assertThatThrownBy(() -> service.delete(OTHER_TOURNAMENT_ID, AudioCategory.START))
                .isInstanceOf(NoSuchElementException.class);
    }
}
