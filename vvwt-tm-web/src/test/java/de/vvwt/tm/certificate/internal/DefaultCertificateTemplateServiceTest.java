package de.vvwt.tm.certificate.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.certificate.CertificateTemplateFormatException;
import de.vvwt.tm.certificate.CertificateTemplateMetadata;
import de.vvwt.tm.certificate.CertificateTemplateRepository;
import de.vvwt.tm.certificate.CertificateTemplateService;
import de.vvwt.tm.certificate.CertificateTemplateSizeException;
import de.vvwt.tm.certificate.CertificateTemplateStorageConfig;
import de.vvwt.tm.certificate.CertificateTemplateVariable;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
 * Unit tests for {@link DefaultCertificateTemplateService} (E12S04, E23S06).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.certificate.CertificateTemplateServiceImplTest} to
 * this package as part of E23S06 (Q-1b whole-class relocation per DEC-22 §refactor-clause). Test
 * assertions are byte-equivalent to the legacy test; no RED-first tests authored in this story
 * (AC-TESTING-QB-NO-RED).
 *
 * <p>Per DEC-36: this test class lives in the SAME package ({@code
 * de.vvwt.tm.certificate.internal}) as {@link DefaultCertificateTemplateService}, so it MAY
 * reference the implementation class directly (same-package white-box access). Cross-package
 * dependencies ({@link TournamentRepository}) are referenced via their public interface type per
 * DEC-36. {@link CertificateTemplateRepository} is in the same {@code .internal} package —
 * white-box access permitted.
 *
 * @see DefaultCertificateTemplateService
 * @see de.vvwt.tm.certificate.CertificateTemplateService
 */
@DisplayName("DefaultCertificateTemplateService unit tests — E12S04/E23S06")
class DefaultCertificateTemplateServiceTest {

    @TempDir Path tempDir;

    private CertificateTemplateStorageConfig config;
    private TournamentRepository tournamentRepo;
    private CertificateTemplateRepository templateRepo;
    private DefaultCertificateTemplateService service;

    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final byte[] SAMPLE_HTML = "<html><body>{{teamName}}</body></html>".getBytes();
    private static final byte[] SAMPLE_SVG =
            "<svg xmlns='http://www.w3.org/2000/svg'><text>{{placement}}</text></svg>".getBytes();

    @BeforeEach
    void setUp() {
        config = new CertificateTemplateStorageConfig();
        config.setDataDir(tempDir.toString());
        config.setMaxSizeBytes(2L * 1024 * 1024); // 2 MB

        // TournamentRepository is a public interface in tournament.* — reference via interface per
        // DEC-36 (cross-package mock)
        tournamentRepo = Mockito.mock(TournamentRepository.class);
        // CertificateTemplateRepository is in certificate.internal.* — same package, white-box ok
        templateRepo = Mockito.mock(CertificateTemplateRepository.class);

        service = new DefaultCertificateTemplateService(config, tournamentRepo, templateRepo);

        // Default: tournament exists and belongs to active tenant
        Tournament tournament = Mockito.mock(Tournament.class);
        when(tournamentRepo.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        // Default: no existing template
        when(templateRepo.findByTournamentId(TOURNAMENT_ID)).thenReturn(Optional.empty());
    }

    // =========================================================================
    // AC1 — Upload HTML template
    // =========================================================================

    @Test
    @DisplayName("AC1: Upload HTML template stores file and returns metadata")
    void uploadHtmlTemplate_storesFileAndReturnsMetadata() {
        CertificateTemplateMetadata result =
                service.upload(
                        TOURNAMENT_ID,
                        "my-certificate.html",
                        new ByteArrayInputStream(SAMPLE_HTML),
                        SAMPLE_HTML.length);

        assertThat(result.tournamentId()).isEqualTo(TOURNAMENT_ID);
        assertThat(result.filename()).isEqualTo("my-certificate.html");
        assertThat(result.format()).isEqualTo("html");
        assertThat(result.fileSizeBytes()).isEqualTo(SAMPLE_HTML.length);
        assertThat(result.uploadedAt()).isNotNull();

        // File must exist on filesystem
        Path storedFile =
                tempDir.resolve(TOURNAMENT_ID.toString()).resolve("certificate-template.html");
        assertThat(storedFile).exists();

        // Repository must be called with the metadata
        verify(templateRepo).upsert(any(CertificateTemplateMetadata.class));
    }

    // =========================================================================
    // AC1 — Upload SVG template
    // =========================================================================

    @Test
    @DisplayName("AC1: Upload SVG template stores file and returns metadata")
    void uploadSvgTemplate_storesFileAndReturnsMetadata() {
        CertificateTemplateMetadata result =
                service.upload(
                        TOURNAMENT_ID,
                        "certificate.svg",
                        new ByteArrayInputStream(SAMPLE_SVG),
                        SAMPLE_SVG.length);

        assertThat(result.format()).isEqualTo("svg");
        assertThat(result.filename()).isEqualTo("certificate.svg");

        Path storedFile =
                tempDir.resolve(TOURNAMENT_ID.toString()).resolve("certificate-template.svg");
        assertThat(storedFile).exists();
    }

    // =========================================================================
    // AC4 — Replace existing template
    // =========================================================================

    @Test
    @DisplayName("AC4: Second upload replaces the existing HTML template")
    void uploadSecondTime_replacesExistingTemplate() throws Exception {
        // Upload initial HTML
        service.upload(
                TOURNAMENT_ID,
                "v1.html",
                new ByteArrayInputStream(SAMPLE_HTML),
                SAMPLE_HTML.length);

        // Upload replacement
        byte[] updatedContent = "<html><body>Updated {{teamName}}</body></html>".getBytes();
        CertificateTemplateMetadata result =
                service.upload(
                        TOURNAMENT_ID,
                        "v2.html",
                        new ByteArrayInputStream(updatedContent),
                        updatedContent.length);

        assertThat(result.filename()).isEqualTo("v2.html");
        assertThat(result.fileSizeBytes()).isEqualTo(updatedContent.length);

        // Only one file should exist
        Path htmlFile =
                tempDir.resolve(TOURNAMENT_ID.toString()).resolve("certificate-template.html");
        assertThat(htmlFile).exists();

        // Content must be updated
        assertThat(Files.readAllBytes(htmlFile)).isEqualTo(updatedContent);
    }

    @Test
    @DisplayName("AC4: Replacing HTML with SVG deletes the old HTML file")
    void uploadSvgReplacingHtml_deletesHtmlFile() throws Exception {
        // Upload initial HTML (directly write file to simulate existing)
        Path htmlFile =
                tempDir.resolve(TOURNAMENT_ID.toString()).resolve("certificate-template.html");
        Files.createDirectories(htmlFile.getParent());
        Files.write(htmlFile, SAMPLE_HTML);

        // Mock that repository knows about the HTML
        CertificateTemplateMetadata existingMeta =
                new CertificateTemplateMetadata(
                        TOURNAMENT_ID, "old.html", "html", Instant.now(), SAMPLE_HTML.length);
        when(templateRepo.findByTournamentId(TOURNAMENT_ID)).thenReturn(Optional.of(existingMeta));

        // Now upload SVG
        service.upload(
                TOURNAMENT_ID, "new.svg", new ByteArrayInputStream(SAMPLE_SVG), SAMPLE_SVG.length);

        // HTML file must be deleted
        assertThat(htmlFile).doesNotExist();

        // SVG file must exist
        Path svgFile =
                tempDir.resolve(TOURNAMENT_ID.toString()).resolve("certificate-template.svg");
        assertThat(svgFile).exists();
    }

    // =========================================================================
    // AC7 — Format validation
    // =========================================================================

    @Test
    @DisplayName("AC7: Upload .pdf returns CertificateTemplateFormatException")
    void uploadPdf_throwsFormatException() {
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        TOURNAMENT_ID,
                                        "cert.pdf",
                                        new ByteArrayInputStream(SAMPLE_HTML),
                                        SAMPLE_HTML.length))
                .isInstanceOf(CertificateTemplateFormatException.class)
                .hasMessageContaining(".html")
                .hasMessageContaining(".svg");

        verify(templateRepo, never()).upsert(any());
    }

    @Test
    @DisplayName("AC7: Upload null filename throws CertificateTemplateFormatException")
    void uploadNullFilename_throwsFormatException() {
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        TOURNAMENT_ID,
                                        null,
                                        new ByteArrayInputStream(SAMPLE_HTML),
                                        SAMPLE_HTML.length))
                .isInstanceOf(CertificateTemplateFormatException.class);
    }

    @Test
    @DisplayName(
            "AC7: Upload SVG file with HTML extension does not fail format check (extension-based,"
                    + " V1)")
    void uploadHtmlExtension_acceptsAnyNonEmptyContent() {
        // V1: format validation is extension-based, not deep content inspection
        // HTML extension + any non-empty content is accepted
        byte[] content = "not really html but non-empty".getBytes();
        CertificateTemplateMetadata result =
                service.upload(
                        TOURNAMENT_ID,
                        "whatever.html",
                        new ByteArrayInputStream(content),
                        content.length);
        assertThat(result.format()).isEqualTo("html");
    }

    @Test
    @DisplayName("AC7: Upload SVG with invalid content throws CertificateTemplateFormatException")
    void uploadSvgWithInvalidContent_throwsFormatException() {
        byte[] invalidSvg = "This is not an SVG file at all".getBytes();
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        TOURNAMENT_ID,
                                        "bad.svg",
                                        new ByteArrayInputStream(invalidSvg),
                                        invalidSvg.length))
                .isInstanceOf(CertificateTemplateFormatException.class)
                .hasMessageContaining("valid SVG");
    }

    @Test
    @DisplayName("AC7: SVG starting with <?xml is accepted")
    void uploadSvgWithXmlDeclaration_isAccepted() {
        byte[] xmlSvg = "<?xml version='1.0'?><svg><text>{{placement}}</text></svg>".getBytes();
        CertificateTemplateMetadata result =
                service.upload(
                        TOURNAMENT_ID, "cert.svg", new ByteArrayInputStream(xmlSvg), xmlSvg.length);
        assertThat(result.format()).isEqualTo("svg");
    }

    @Test
    @DisplayName("AC7: Empty file throws CertificateTemplateFormatException")
    void uploadEmptyFile_throwsFormatException() {
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        TOURNAMENT_ID,
                                        "empty.html",
                                        new ByteArrayInputStream(new byte[0]),
                                        0))
                .isInstanceOf(CertificateTemplateFormatException.class)
                .hasMessageContaining("empty");
    }

    // =========================================================================
    // AC7 — Size validation
    // =========================================================================

    @Test
    @DisplayName("AC7: File exceeding 2 MB limit throws CertificateTemplateSizeException")
    void uploadOversizedFile_throwsSizeException() {
        long oversizedBytes = 2L * 1024 * 1024 + 1; // 1 byte over 2 MB
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        TOURNAMENT_ID,
                                        "big.html",
                                        new ByteArrayInputStream(SAMPLE_HTML),
                                        oversizedBytes))
                .isInstanceOf(CertificateTemplateSizeException.class)
                .hasMessageContaining("too large");
    }

    @Test
    @DisplayName("AC7: File at exactly the size limit is accepted")
    void uploadFileAtExactLimit_isAccepted() {
        // sizeBytes == maxSizeBytes is OK (not strictly greater than)
        long exactLimit = 2L * 1024 * 1024;
        CertificateTemplateMetadata result =
                service.upload(
                        TOURNAMENT_ID,
                        "limit.html",
                        new ByteArrayInputStream(SAMPLE_HTML),
                        exactLimit);
        assertThat(result).isNotNull();
    }

    // =========================================================================
    // AC2 — Retrieve file
    // =========================================================================

    @Test
    @DisplayName("AC2: Retrieve returns inputStream and correct content-type for HTML")
    void retrieveHtmlFile_returnsCorrectContentType() throws Exception {
        // Upload first
        service.upload(
                TOURNAMENT_ID,
                "cert.html",
                new ByteArrayInputStream(SAMPLE_HTML),
                SAMPLE_HTML.length);

        CertificateTemplateMetadata meta =
                new CertificateTemplateMetadata(
                        TOURNAMENT_ID, "cert.html", "html", Instant.now(), SAMPLE_HTML.length);
        when(templateRepo.findByTournamentId(TOURNAMENT_ID)).thenReturn(Optional.of(meta));

        Optional<CertificateTemplateService.TemplateFile> result =
                service.retrieveFile(TOURNAMENT_ID);

        assertThat(result).isPresent();
        assertThat(result.get().contentType())
                .isEqualTo(DefaultCertificateTemplateService.CONTENT_TYPE_HTML);
        result.get().inputStream().close();
    }

    @Test
    @DisplayName("AC2: Retrieve returns image/svg+xml for SVG")
    void retrieveSvgFile_returnsSvgContentType() throws Exception {
        service.upload(
                TOURNAMENT_ID, "cert.svg", new ByteArrayInputStream(SAMPLE_SVG), SAMPLE_SVG.length);

        CertificateTemplateMetadata meta =
                new CertificateTemplateMetadata(
                        TOURNAMENT_ID, "cert.svg", "svg", Instant.now(), SAMPLE_SVG.length);
        when(templateRepo.findByTournamentId(TOURNAMENT_ID)).thenReturn(Optional.of(meta));

        Optional<CertificateTemplateService.TemplateFile> result =
                service.retrieveFile(TOURNAMENT_ID);

        assertThat(result).isPresent();
        assertThat(result.get().contentType())
                .isEqualTo(DefaultCertificateTemplateService.CONTENT_TYPE_SVG);
        result.get().inputStream().close();
    }

    @Test
    @DisplayName("AC2: Retrieve returns empty when no template uploaded")
    void retrieveFile_returnsEmptyWhenNoTemplate() {
        when(templateRepo.findByTournamentId(TOURNAMENT_ID)).thenReturn(Optional.empty());

        Optional<CertificateTemplateService.TemplateFile> result =
                service.retrieveFile(TOURNAMENT_ID);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // AC3 — Retrieve metadata
    // =========================================================================

    @Test
    @DisplayName("AC3: Retrieve metadata returns stored metadata")
    void retrieveMetadata_returnsMetadataFromRepository() {
        CertificateTemplateMetadata expectedMeta =
                new CertificateTemplateMetadata(
                        TOURNAMENT_ID, "cert.html", "html", Instant.now(), 42L);
        when(templateRepo.findByTournamentId(TOURNAMENT_ID)).thenReturn(Optional.of(expectedMeta));

        Optional<CertificateTemplateMetadata> result = service.retrieveMetadata(TOURNAMENT_ID);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(expectedMeta);
    }

    @Test
    @DisplayName("AC3: Retrieve metadata returns empty when no template uploaded")
    void retrieveMetadata_returnsEmptyWhenNoTemplate() {
        when(templateRepo.findByTournamentId(TOURNAMENT_ID)).thenReturn(Optional.empty());

        Optional<CertificateTemplateMetadata> result = service.retrieveMetadata(TOURNAMENT_ID);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // AC5 — Delete
    // =========================================================================

    @Test
    @DisplayName("AC5: Delete after upload returns true and removes file")
    void delete_afterUpload_returnsTrueAndRemovesFile() throws Exception {
        service.upload(
                TOURNAMENT_ID,
                "cert.html",
                new ByteArrayInputStream(SAMPLE_HTML),
                SAMPLE_HTML.length);

        CertificateTemplateMetadata meta =
                new CertificateTemplateMetadata(
                        TOURNAMENT_ID, "cert.html", "html", Instant.now(), SAMPLE_HTML.length);
        when(templateRepo.findByTournamentId(TOURNAMENT_ID)).thenReturn(Optional.of(meta));
        when(templateRepo.deleteByTournamentId(TOURNAMENT_ID)).thenReturn(true);

        boolean deleted = service.delete(TOURNAMENT_ID);

        assertThat(deleted).isTrue();

        Path storedFile =
                tempDir.resolve(TOURNAMENT_ID.toString()).resolve("certificate-template.html");
        assertThat(storedFile).doesNotExist();
    }

    @Test
    @DisplayName("AC5: Delete when no template returns false")
    void delete_whenNoTemplate_returnsFalse() {
        when(templateRepo.findByTournamentId(TOURNAMENT_ID)).thenReturn(Optional.empty());

        boolean deleted = service.delete(TOURNAMENT_ID);

        assertThat(deleted).isFalse();
        verify(templateRepo, never()).deleteByTournamentId(any());
    }

    // =========================================================================
    // AC6 — Variables
    // =========================================================================

    @Test
    @DisplayName("AC6: listVariables returns all 6 variables")
    void listVariables_returnsAllSixVariables() {
        List<CertificateTemplateVariable> variables = service.listVariables();

        assertThat(variables).hasSize(6);
        assertThat(variables)
                .extracting(CertificateTemplateVariable::name)
                .containsExactly(
                        "placement", "teamName", "teamPhoto", "tournamentName", "date", "location");
    }

    @Test
    @DisplayName("AC6: All variables have non-empty name, type, and example")
    void listVariables_allVariablesHaveRequiredFields() {
        List<CertificateTemplateVariable> variables = service.listVariables();

        for (CertificateTemplateVariable v : variables) {
            assertThat(v.name()).isNotBlank();
            assertThat(v.type()).isNotBlank();
            assertThat(v.example()).isNotBlank();
        }
    }

    // =========================================================================
    // AC8/AC9 — Tournament scope
    // =========================================================================

    @Test
    @DisplayName("AC8: Upload with unknown tournament throws NoSuchElementException")
    void upload_unknownTournament_throwsNoSuchElement() {
        UUID unknownId = UUID.randomUUID();
        when(tournamentRepo.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        unknownId,
                                        "cert.html",
                                        new ByteArrayInputStream(SAMPLE_HTML),
                                        SAMPLE_HTML.length))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(unknownId.toString());
    }

    @Test
    @DisplayName("AC8: Retrieve file with unknown tournament throws NoSuchElementException")
    void retrieveFile_unknownTournament_throwsNoSuchElement() {
        UUID unknownId = UUID.randomUUID();
        when(tournamentRepo.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retrieveFile(unknownId))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("AC8: Delete with unknown tournament throws NoSuchElementException")
    void delete_unknownTournament_throwsNoSuchElement() {
        UUID unknownId = UUID.randomUUID();
        when(tournamentRepo.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(unknownId))
                .isInstanceOf(NoSuchElementException.class);
    }
}
