// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
import de.vvwt.tm.certificate.CertificateTemplateStorageException;
import de.vvwt.tm.certificate.CertificateTemplateVariable;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * RED-first TDD test corpus for {@link DefaultCertificateTemplateService} (E36S04).
 *
 * <p>Authored under DEC-22 Iron Law Q-1a RED-first discipline — every test in this file was written
 * against absent production code (RED baseline = delete commit {@code 0c3d9c9}), then turned GREEN
 * by implementing the production code (DEC-41 §3 clause (1) MANDATORY).
 *
 * <p>Pre-existing {@code DefaultCertificateTemplateServiceTest} (24 methods, 100% Snapshot-Driven
 * per DEC-41 audit) was deleted in the same RED-baseline commit and is NOT reused per DEC-41 §3
 * clause (3) FORBIDDEN. This fresh corpus covers all behavioral domains of the deleted test.
 *
 * <p>Per DEC-36: this test class lives in the SAME package ({@code
 * de.vvwt.tm.certificate.internal}) as {@link DefaultCertificateTemplateService}, so it MAY
 * reference the implementation class directly (same-package white-box access). Cross-package
 * dependencies ({@link TournamentRepository}) are referenced via their public interface type per
 * DEC-36.
 *
 * @see DefaultCertificateTemplateService
 * @see de.vvwt.tm.certificate.CertificateTemplateService
 */
@DisplayName("DefaultCertificateTemplateService — Q-1a TDD corpus (E36S04)")
class DefaultCertificateTemplateServiceTest {

    @TempDir Path tempDir;

    private CertificateTemplateStorageConfig config;
    private TournamentRepository tournamentRepo;
    private CertificateTemplateRepository templateRepo;
    private de.vvwt.tm.photo.PhotoStorageConfig photoConfig;
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

        // E71S02: global default crop ratio for effective-ratio fallback
        photoConfig = new de.vvwt.tm.photo.PhotoStorageConfig();
        photoConfig.setCropAspectRatioWidth(11);
        photoConfig.setCropAspectRatioHeight(5);

        // TournamentRepository is a public interface in tournament.* — reference via interface per
        // DEC-36 (cross-package mock)
        tournamentRepo = Mockito.mock(TournamentRepository.class);
        // CertificateTemplateRepository is in certificate.* public package — same module access
        templateRepo = Mockito.mock(CertificateTemplateRepository.class);

        service =
                new DefaultCertificateTemplateService(
                        config, tournamentRepo, templateRepo, photoConfig);

        // Default: tournament exists and belongs to active tenant
        Tournament tournament = Mockito.mock(Tournament.class);
        when(tournamentRepo.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        // Default: no existing template
        when(templateRepo.findByTournamentId(TOURNAMENT_ID)).thenReturn(Optional.empty());
    }

    // =========================================================================
    // CertificateTemplateMetadata record — signature preservation
    // (AC-RECORD-FIELDS-PRESERVED-METADATA)
    // =========================================================================

    @Nested
    @DisplayName("CertificateTemplateMetadata record")
    class MetadataRecordTests {

        @Test
        @DisplayName(
                "Record constructor preserves all 7 fields: tournamentId, filename, format,"
                        + " uploadedAt, fileSizeBytes, photoAspectRatioWidth,"
                        + " photoAspectRatioHeight (E71S02)")
        void metadataRecord_constructorPreservesAllFields() {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            CertificateTemplateMetadata meta =
                    new CertificateTemplateMetadata(id, "cert.html", "html", now, 42L, null, null);

            assertThat(meta.tournamentId()).isEqualTo(id);
            assertThat(meta.filename()).isEqualTo("cert.html");
            assertThat(meta.format()).isEqualTo("html");
            assertThat(meta.uploadedAt()).isEqualTo(now);
            assertThat(meta.fileSizeBytes()).isEqualTo(42L);
            assertThat(meta.photoAspectRatioWidth()).isNull();
            assertThat(meta.photoAspectRatioHeight()).isNull();
        }

        @Test
        @DisplayName("Record equality: two instances with same field values are equal")
        void metadataRecord_equalityByFields() {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            CertificateTemplateMetadata a =
                    new CertificateTemplateMetadata(id, "f.html", "html", now, 10L, null, null);
            CertificateTemplateMetadata b =
                    new CertificateTemplateMetadata(id, "f.html", "html", now, 10L, null, null);
            assertThat(a).isEqualTo(b);
        }
    }

    // =========================================================================
    // CertificateTemplateVariable record — signature preservation
    // (AC-RECORD-FIELDS-PRESERVED-VARIABLE)
    // =========================================================================

    @Nested
    @DisplayName("CertificateTemplateVariable record")
    class VariableRecordTests {

        @Test
        @DisplayName("Record constructor preserves all 3 fields: name, type, example")
        void variableRecord_constructorPreservesAllFields() {
            CertificateTemplateVariable v =
                    new CertificateTemplateVariable("placement", "String", "1");
            assertThat(v.name()).isEqualTo("placement");
            assertThat(v.type()).isEqualTo("String");
            assertThat(v.example()).isEqualTo("1");
        }
    }

    // =========================================================================
    // Exception constructors — AC-EXCEPTION-CONSTRUCTORS-PRESERVED
    // =========================================================================

    @Nested
    @DisplayName("Exception constructor signatures")
    class ExceptionTests {

        @Test
        @DisplayName(
                "CertificateTemplateFormatException(String) is a RuntimeException with message")
        void formatException_singleStringConstructor() {
            CertificateTemplateFormatException ex =
                    new CertificateTemplateFormatException("bad format");
            assertThat(ex).isInstanceOf(RuntimeException.class);
            assertThat(ex.getMessage()).isEqualTo("bad format");
        }

        @Test
        @DisplayName("CertificateTemplateSizeException(String) is a RuntimeException with message")
        void sizeException_singleStringConstructor() {
            CertificateTemplateSizeException ex = new CertificateTemplateSizeException("too large");
            assertThat(ex).isInstanceOf(RuntimeException.class);
            assertThat(ex.getMessage()).isEqualTo("too large");
        }

        @Test
        @DisplayName("CertificateTemplateStorageException(String, Throwable) preserves cause")
        void storageException_twoArgConstructor() {
            Throwable cause = new IOException("disk error");
            CertificateTemplateStorageException ex =
                    new CertificateTemplateStorageException("storage failed", cause);
            assertThat(ex).isInstanceOf(RuntimeException.class);
            assertThat(ex.getMessage()).isEqualTo("storage failed");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    // =========================================================================
    // CertificateTemplateStorageConfig — AC-CONFIG-BINDING-PRESERVED
    // =========================================================================

    @Nested
    @DisplayName("CertificateTemplateStorageConfig bean and properties")
    class StorageConfigTests {

        @Test
        @DisplayName("Config getters and setters round-trip: dataDir")
        void config_dataDirGetterSetterRoundTrip() {
            CertificateTemplateStorageConfig cfg = new CertificateTemplateStorageConfig();
            cfg.setDataDir("/some/path");
            assertThat(cfg.getDataDir()).isEqualTo("/some/path");
        }

        @Test
        @DisplayName("Config default maxSizeBytes is 2 MB (2097152 bytes)")
        void config_defaultMaxSizeBytesIs2MB() {
            CertificateTemplateStorageConfig cfg = new CertificateTemplateStorageConfig();
            assertThat(cfg.getMaxSizeBytes()).isEqualTo(2L * 1024 * 1024);
        }

        @Test
        @DisplayName("Config maxSizeBytes getter/setter round-trip")
        void config_maxSizeBytesGetterSetterRoundTrip() {
            CertificateTemplateStorageConfig cfg = new CertificateTemplateStorageConfig();
            cfg.setMaxSizeBytes(5L * 1024 * 1024);
            assertThat(cfg.getMaxSizeBytes()).isEqualTo(5L * 1024 * 1024);
        }
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

        Path storedFile =
                tempDir.resolve(TOURNAMENT_ID.toString()).resolve("certificate-template.html");
        assertThat(storedFile).exists();

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
        service.upload(
                TOURNAMENT_ID,
                "v1.html",
                new ByteArrayInputStream(SAMPLE_HTML),
                SAMPLE_HTML.length);

        byte[] updatedContent = "<html><body>Updated {{teamName}}</body></html>".getBytes();
        CertificateTemplateMetadata result =
                service.upload(
                        TOURNAMENT_ID,
                        "v2.html",
                        new ByteArrayInputStream(updatedContent),
                        updatedContent.length);

        assertThat(result.filename()).isEqualTo("v2.html");
        assertThat(result.fileSizeBytes()).isEqualTo(updatedContent.length);

        Path htmlFile =
                tempDir.resolve(TOURNAMENT_ID.toString()).resolve("certificate-template.html");
        assertThat(htmlFile).exists();
        assertThat(Files.readAllBytes(htmlFile)).isEqualTo(updatedContent);
    }

    @Test
    @DisplayName("AC4: Replacing HTML with SVG deletes the old HTML file")
    void uploadSvgReplacingHtml_deletesHtmlFile() throws Exception {
        Path htmlFile =
                tempDir.resolve(TOURNAMENT_ID.toString()).resolve("certificate-template.html");
        Files.createDirectories(htmlFile.getParent());
        Files.write(htmlFile, SAMPLE_HTML);

        CertificateTemplateMetadata existingMeta =
                new CertificateTemplateMetadata(
                        TOURNAMENT_ID,
                        "old.html",
                        "html",
                        Instant.now(),
                        SAMPLE_HTML.length,
                        null,
                        null);
        when(templateRepo.findByTournamentId(TOURNAMENT_ID)).thenReturn(Optional.of(existingMeta));

        service.upload(
                TOURNAMENT_ID, "new.svg", new ByteArrayInputStream(SAMPLE_SVG), SAMPLE_SVG.length);

        assertThat(htmlFile).doesNotExist();

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
            "AC7: HTML extension with any non-empty content is accepted (extension-based"
                    + " validation, V1)")
    void uploadHtmlExtension_acceptsAnyNonEmptyContent() {
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
        long oversizedBytes = 2L * 1024 * 1024 + 1;
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
        service.upload(
                TOURNAMENT_ID,
                "cert.html",
                new ByteArrayInputStream(SAMPLE_HTML),
                SAMPLE_HTML.length);

        CertificateTemplateMetadata meta =
                new CertificateTemplateMetadata(
                        TOURNAMENT_ID,
                        "cert.html",
                        "html",
                        Instant.now(),
                        SAMPLE_HTML.length,
                        null,
                        null);
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
                        TOURNAMENT_ID,
                        "cert.svg",
                        "svg",
                        Instant.now(),
                        SAMPLE_SVG.length,
                        null,
                        null);
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
                        TOURNAMENT_ID, "cert.html", "html", Instant.now(), 42L, null, null);
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
                        TOURNAMENT_ID,
                        "cert.html",
                        "html",
                        Instant.now(),
                        SAMPLE_HTML.length,
                        null,
                        null);
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
    @DisplayName(
            "AC6 (E46S03): listVariables returns exactly 13 tom_-prefixed variables"
                    + " (AC-VARIABLES-LIST-COUNT-EXACTLY-13, AC-VARIABLES-LIST-13-PREFIXED)")
    void listVariables_returnsAllThirteenPrefixedVariables() {
        List<CertificateTemplateVariable> variables = service.listVariables();

        // AC-VARIABLES-LIST-COUNT-EXACTLY-13
        assertThat(variables).hasSize(13);

        // AC-VARIABLES-LIST-13-PREFIXED: all entries have tom_-prefixed names
        assertThat(variables)
                .extracting(CertificateTemplateVariable::name)
                .allSatisfy(name -> assertThat(name).startsWith("tom_"));

        // AC-VARIABLES-LIST-NO-UNPREFIXED: no legacy unprefixed names
        assertThat(variables)
                .extracting(CertificateTemplateVariable::name)
                .doesNotContain(
                        "placement", "teamName", "teamPhoto", "tournamentName", "date", "location");

        // Spot-check canonical names are present
        assertThat(variables)
                .extracting(CertificateTemplateVariable::name)
                .contains(
                        "tom_placement",
                        "tom_team_name",
                        "tom_team_photo",
                        "tom_tournament_name",
                        "tom_date",
                        "tom_location",
                        "tom_organizer",
                        "tom_label_certificate",
                        "tom_label_place",
                        "tom_label_achieved_by",
                        "tom_label_team_photo",
                        "tom_label_generated_by",
                        "tom_label_on");
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

    // =========================================================================
    // E71S02 — Effective aspect ratio resolution (AC2, AC3, AC5)
    // =========================================================================

    @Nested
    @DisplayName("E71S02 — retrieveEffectiveAspectRatio")
    class EffectiveAspectRatioTests {

        @Test
        @DisplayName("AC2: returns per-template override when override is set")
        void retrieveEffectiveAspectRatio_returnsOverride_whenOverrideSet() {
            CertificateTemplateMetadata metaWithRatio =
                    new CertificateTemplateMetadata(
                            TOURNAMENT_ID, "t.html", "html", java.time.Instant.now(), 512L, 4, 3);
            when(templateRepo.findByTournamentId(TOURNAMENT_ID))
                    .thenReturn(Optional.of(metaWithRatio));

            de.vvwt.tm.certificate.AspectRatio result =
                    service.retrieveEffectiveAspectRatio(TOURNAMENT_ID);

            assertThat(result.width()).isEqualTo(4);
            assertThat(result.height()).isEqualTo(3);
        }

        @Test
        @DisplayName("AC2: returns global default when no override is set on template")
        void retrieveEffectiveAspectRatio_returnsGlobalDefault_whenNoOverride() {
            CertificateTemplateMetadata metaNoRatio =
                    new CertificateTemplateMetadata(
                            TOURNAMENT_ID,
                            "t.html",
                            "html",
                            java.time.Instant.now(),
                            512L,
                            null,
                            null);
            when(templateRepo.findByTournamentId(TOURNAMENT_ID))
                    .thenReturn(Optional.of(metaNoRatio));

            de.vvwt.tm.certificate.AspectRatio result =
                    service.retrieveEffectiveAspectRatio(TOURNAMENT_ID);

            // Global default from config = 11:5
            assertThat(result.width()).isEqualTo(11);
            assertThat(result.height()).isEqualTo(5);
        }

        @Test
        @DisplayName("AC2: returns global default when no template exists")
        void retrieveEffectiveAspectRatio_returnsGlobalDefault_whenNoTemplate() {
            when(templateRepo.findByTournamentId(TOURNAMENT_ID)).thenReturn(Optional.empty());

            de.vvwt.tm.certificate.AspectRatio result =
                    service.retrieveEffectiveAspectRatio(TOURNAMENT_ID);

            assertThat(result.width()).isEqualTo(11);
            assertThat(result.height()).isEqualTo(5);
        }
    }

    // =========================================================================
    // E71S02 — Upload with ratio override (AC1, AC3)
    // =========================================================================

    @Nested
    @DisplayName("E71S02 — Upload with ratio override")
    class UploadWithRatioTests {

        @Test
        @DisplayName("AC1: upload with valid ratio override persists ratio in metadata")
        void upload_withValidRatioOverride_persistsRatioInMetadata(@TempDir java.nio.file.Path dir)
                throws Exception {
            config.setDataDir(dir.toString());
            service =
                    new DefaultCertificateTemplateService(
                            config,
                            tournamentRepo,
                            templateRepo,
                            photoStorageConfigWithDefaults(11, 5));
            byte[] html = "<html><body>cert</body></html>".getBytes();
            java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(html);

            CertificateTemplateMetadata result =
                    service.upload(TOURNAMENT_ID, "cert.html", stream, html.length, 4, 3);

            assertThat(result.photoAspectRatioWidth()).isEqualTo(4);
            assertThat(result.photoAspectRatioHeight()).isEqualTo(3);
        }

        @Test
        @DisplayName(
                "AC3: upload with ratio 0:0 (zero height) throws"
                        + " CertificateTemplateFormatException")
        void upload_withZeroRatio_throwsFormatException(@TempDir java.nio.file.Path dir) {
            config.setDataDir(dir.toString());
            service =
                    new DefaultCertificateTemplateService(
                            config,
                            tournamentRepo,
                            templateRepo,
                            photoStorageConfigWithDefaults(11, 5));
            byte[] html = "<html/>".getBytes();
            java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(html);

            assertThatThrownBy(
                            () ->
                                    service.upload(
                                            TOURNAMENT_ID, "cert.html", stream, html.length, 0, 0))
                    .isInstanceOf(CertificateTemplateFormatException.class);
        }

        @Test
        @DisplayName(
                "AC3: upload with negative ratio width throws CertificateTemplateFormatException")
        void upload_withNegativeRatioWidth_throwsFormatException(@TempDir java.nio.file.Path dir) {
            config.setDataDir(dir.toString());
            service =
                    new DefaultCertificateTemplateService(
                            config,
                            tournamentRepo,
                            templateRepo,
                            photoStorageConfigWithDefaults(11, 5));
            byte[] html = "<html/>".getBytes();
            java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(html);

            assertThatThrownBy(
                            () ->
                                    service.upload(
                                            TOURNAMENT_ID, "cert.html", stream, html.length, -1, 5))
                    .isInstanceOf(CertificateTemplateFormatException.class);
        }

        @Test
        @DisplayName("AC1: upload with null ratio persists no override (null in metadata)")
        void upload_withNullRatio_persistsNullRatio(@TempDir java.nio.file.Path dir)
                throws Exception {
            config.setDataDir(dir.toString());
            service =
                    new DefaultCertificateTemplateService(
                            config,
                            tournamentRepo,
                            templateRepo,
                            photoStorageConfigWithDefaults(11, 5));
            byte[] html = "<html><body>cert</body></html>".getBytes();
            java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(html);

            CertificateTemplateMetadata result =
                    service.upload(TOURNAMENT_ID, "cert.html", stream, html.length, null, null);

            assertThat(result.photoAspectRatioWidth()).isNull();
            assertThat(result.photoAspectRatioHeight()).isNull();
        }

        private de.vvwt.tm.photo.PhotoStorageConfig photoStorageConfigWithDefaults(
                int width, int height) {
            de.vvwt.tm.photo.PhotoStorageConfig cfg = new de.vvwt.tm.photo.PhotoStorageConfig();
            cfg.setCropAspectRatioWidth(width);
            cfg.setCropAspectRatioHeight(height);
            return cfg;
        }
    }
}
