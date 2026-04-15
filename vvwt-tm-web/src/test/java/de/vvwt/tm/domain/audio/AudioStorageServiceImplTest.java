package de.vvwt.tm.domain.audio;

import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.repo.TournamentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AudioStorageServiceImpl}.
 *
 * <p>Story E11S01 — verifies AC1 (upload), AC2 (stream), AC3 (list), AC4 (delete), AC5 (tenant
 * scoping), AC7 (error handling) at the service layer without a running Spring context.
 *
 * @see AudioStorageServiceImpl
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S01.story.md">Story E11S01</a>
 */
@DisplayName("AudioStorageServiceImpl unit tests — E11S01")
class AudioStorageServiceImplTest {

    @TempDir
    Path tempDir;

    private AudioStorageConfig config;
    private TournamentRepository tournamentRepository;
    private AudioStorageServiceImpl service;

    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final byte[] SAMPLE_MP3_BYTES = new byte[]{0x49, 0x44, 0x33, 0x00, 0x00}; // minimal

    @BeforeEach
    void setUp() {
        config = new AudioStorageConfig();
        config.setDataDir(tempDir.toString());

        tournamentRepository = mock(TournamentRepository.class);

        Tournament fakeTournament = new Tournament();
        fakeTournament.setId(TOURNAMENT_ID);

        when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(fakeTournament));
        when(tournamentRepository.findById(any(UUID.class)))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    if (TOURNAMENT_ID.equals(id)) {
                        return Optional.of(fakeTournament);
                    }
                    return Optional.empty();
                });

        service = new AudioStorageServiceImpl(config, tournamentRepository);
    }

    // =========================================================================
    // AC1 — Upload
    // =========================================================================

    @Test
    @DisplayName("AC1: upload stores file and returns metadata")
    void uploadStoresFileAndReturnsMetadata() {
        AudioFileMetadata meta = service.upload(
                TOURNAMENT_ID, AudioCategory.START, "start.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES), SAMPLE_MP3_BYTES.length);

        assertThat(meta.category()).isEqualTo(AudioCategory.START);
        assertThat(meta.filename()).isEqualTo("start.mp3");
        assertThat(meta.sizeBytes()).isEqualTo(SAMPLE_MP3_BYTES.length);
        assertThat(meta.uploadedAt()).isNotNull();

        // Verify file exists on disk
        Path expected = tempDir.resolve(TOURNAMENT_ID.toString()).resolve("start.mp3");
        assertThat(expected).exists();
    }

    @Test
    @DisplayName("AC1: upload replaces existing file for same category")
    void uploadReplacesExistingFile() throws IOException {
        byte[] firstUpload = new byte[]{0x01, 0x02};
        byte[] secondUpload = new byte[]{0x03, 0x04, 0x05};

        service.upload(TOURNAMENT_ID, AudioCategory.START, "first.mp3",
                new ByteArrayInputStream(firstUpload), firstUpload.length);
        service.upload(TOURNAMENT_ID, AudioCategory.START, "second.mp3",
                new ByteArrayInputStream(secondUpload), secondUpload.length);

        Path storedFile = tempDir.resolve(TOURNAMENT_ID.toString()).resolve("start.mp3");
        byte[] storedBytes = Files.readAllBytes(storedFile);
        assertThat(storedBytes).isEqualTo(secondUpload);
    }

    @Test
    @DisplayName("AC1: upload creates parent directory if it does not exist")
    void uploadCreatesParentDirectory() {
        assertThat(tempDir.resolve(TOURNAMENT_ID.toString())).doesNotExist();

        service.upload(TOURNAMENT_ID, AudioCategory.END, "end.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES), SAMPLE_MP3_BYTES.length);

        assertThat(tempDir.resolve(TOURNAMENT_ID.toString()).resolve("end.mp3")).exists();
    }

    // =========================================================================
    // AC2 — Stream
    // =========================================================================

    @Test
    @DisplayName("AC2: stream returns content of uploaded file")
    void streamReturnsUploadedFileContent() throws IOException {
        service.upload(TOURNAMENT_ID, AudioCategory.START, "start.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES), SAMPLE_MP3_BYTES.length);

        Optional<InputStream> streamOpt = service.stream(TOURNAMENT_ID, AudioCategory.START);
        assertThat(streamOpt).isPresent();

        byte[] read = streamOpt.get().readAllBytes();
        assertThat(read).isEqualTo(SAMPLE_MP3_BYTES);
    }

    @Test
    @DisplayName("AC2: stream returns empty Optional when no file uploaded")
    void streamReturnsEmptyForMissingFile() {
        Optional<InputStream> streamOpt = service.stream(TOURNAMENT_ID, AudioCategory.PAUSE);
        assertThat(streamOpt).isEmpty();
    }

    // =========================================================================
    // AC3 — List
    // =========================================================================

    @Test
    @DisplayName("AC3: list returns only uploaded categories")
    void listReturnsOnlyUploadedCategories() {
        service.upload(TOURNAMENT_ID, AudioCategory.START, "start.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES), SAMPLE_MP3_BYTES.length);
        service.upload(TOURNAMENT_ID, AudioCategory.END, "end.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES), SAMPLE_MP3_BYTES.length);

        List<AudioFileMetadata> list = service.list(TOURNAMENT_ID);

        assertThat(list).hasSize(2);
        assertThat(list).extracting(AudioFileMetadata::category)
                .containsExactlyInAnyOrder(AudioCategory.START, AudioCategory.END);
    }

    @Test
    @DisplayName("AC3: list returns empty list when no files uploaded")
    void listReturnsEmptyWhenNoFilesUploaded() {
        List<AudioFileMetadata> list = service.list(TOURNAMENT_ID);
        assertThat(list).isEmpty();
    }

    // =========================================================================
    // AC4 — Delete
    // =========================================================================

    @Test
    @DisplayName("AC4: delete removes file and returns true")
    void deleteRemovesFileAndReturnsTrue() {
        service.upload(TOURNAMENT_ID, AudioCategory.PAUSE, "pause.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES), SAMPLE_MP3_BYTES.length);

        boolean deleted = service.delete(TOURNAMENT_ID, AudioCategory.PAUSE);

        assertThat(deleted).isTrue();
        assertThat(tempDir.resolve(TOURNAMENT_ID.toString()).resolve("pause.mp3")).doesNotExist();
    }

    @Test
    @DisplayName("AC4: delete returns false when file does not exist")
    void deleteReturnsFalseForMissingFile() {
        boolean deleted = service.delete(TOURNAMENT_ID, AudioCategory.PAUSE);
        assertThat(deleted).isFalse();
    }

    // =========================================================================
    // AC5 — Tenant scoping
    // =========================================================================

    @Test
    @DisplayName("AC5: upload throws NoSuchElementException for unknown/cross-tenant tournament")
    void uploadThrowsForUnknownTournament() {
        UUID unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> service.upload(unknownId, AudioCategory.START, "start.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES), SAMPLE_MP3_BYTES.length))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(unknownId.toString());
    }

    @Test
    @DisplayName("AC5: stream throws NoSuchElementException for unknown/cross-tenant tournament")
    void streamThrowsForUnknownTournament() {
        UUID unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> service.stream(unknownId, AudioCategory.START))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("AC5: list throws NoSuchElementException for unknown/cross-tenant tournament")
    void listThrowsForUnknownTournament() {
        UUID unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> service.list(unknownId))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("AC5: delete throws NoSuchElementException for unknown/cross-tenant tournament")
    void deleteThrowsForUnknownTournament() {
        UUID unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> service.delete(unknownId, AudioCategory.START))
                .isInstanceOf(NoSuchElementException.class);
    }

    // =========================================================================
    // AC7 — Error handling: format validation
    // =========================================================================

    @Test
    @DisplayName("AC7: upload rejects non-.mp3 file with AudioFormatException")
    void uploadRejectsNonMp3File() {
        assertThatThrownBy(() -> service.upload(TOURNAMENT_ID, AudioCategory.START, "audio.wav",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES), SAMPLE_MP3_BYTES.length))
                .isInstanceOf(AudioFormatException.class)
                .hasMessageContaining(".mp3");
    }

    @Test
    @DisplayName("AC7: upload rejects null filename with AudioFormatException")
    void uploadRejectsNullFilename() {
        assertThatThrownBy(() -> service.upload(TOURNAMENT_ID, AudioCategory.START, null,
                new ByteArrayInputStream(SAMPLE_MP3_BYTES), SAMPLE_MP3_BYTES.length))
                .isInstanceOf(AudioFormatException.class);
    }

    @Test
    @DisplayName("AC7: upload rejects oversized file with AudioSizeLimitException")
    void uploadRejectsOversizedFile() {
        long oversized = AudioStorageServiceImpl.MAX_UPLOAD_BYTES + 1;

        assertThatThrownBy(() -> service.upload(TOURNAMENT_ID, AudioCategory.START, "big.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES), oversized))
                .isInstanceOf(AudioSizeLimitException.class)
                .hasMessageContaining("too large");
    }

    @Test
    @DisplayName("AC7: upload accepts file at exactly the size limit")
    void uploadAcceptsFileAtSizeLimit() {
        // Should not throw
        service.upload(TOURNAMENT_ID, AudioCategory.START, "exact.mp3",
                new ByteArrayInputStream(SAMPLE_MP3_BYTES),
                AudioStorageServiceImpl.MAX_UPLOAD_BYTES);
    }
}
