// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.certificate;

import com.samskivert.mustache.MustacheException;
import de.vvwt.tm.certificate.CertificateAssembler;
import de.vvwt.tm.certificate.CertificatePlacementRow;
import de.vvwt.tm.certificate.CertificateTemplateService;
import de.vvwt.tm.certificate.CertificateTemplateStorageException;
import de.vvwt.tm.tenant.LocationDisplayResolver;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.TournamentNotFoundException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * REST controller for certificate rendering at new URLs (E24S05 — E23 scope-gap closure).
 *
 * <p>NEW functionality: the certificate HTML-rendering endpoints were previously only served by the
 * legacy {@code PrintController} (deleted at E24S07 atomic cutover). This controller delivers them
 * as Q-1a TDD RED-first new code at {@code de.vvwt.tm.web.certificate.*} per DEC-40
 * Primary-Adapter-Isolation.
 *
 * <p>The legacy {@code PrintController} at {@code /print/tournaments/{tid}/certificates/...}
 * continues to serve its original URLs until E24S07 atomic cutover. This controller serves NEW
 * URLs: {@code /certificate/tournaments/{tid}/print/...}.
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>{@code GET /certificate/tournaments/{tid}/print/{teamId}} — single certificate (SVG or HTML
 *       by template format; 400 if template absent; 500 on Mustache failure)
 *   <li>{@code GET /certificate/tournaments/{tid}/print} — all certificates (ZIP for SVG; HTML page
 *       for HTML; same error paths)
 * </ul>
 *
 * <h2>DEC-40 Clause B disposition (AC-DEC-40-CLAUSE-B-N-A)</h2>
 *
 * <p>All 5 return branches produce non-JSON content (HTML view, SVG bytes, ZIP bytes, plaintext
 * errors). DEC-40 Clause B governs Jackson JSON serialization and is N/A for this controller.
 *
 * <h2>DEC-40 Trigger-β (AC-BETA-DOES-NOT-FIRE)</h2>
 *
 * <p>Both endpoints import from {tournament, certificate, tenant} = 2 bounded contexts (tenant
 * excluded per D-16 natural reading). β does NOT fire.
 *
 * <h2>Security (AC-SECURITYCONFIG-CERTIFICATE-PATTERN)</h2>
 *
 * <p>{@code /certificate/**} requires authentication per SecurityConfig (E24S05 addition). No role
 * restriction (authenticated-only per E-6 + empirical SecurityConfig). 401 for unauthenticated
 * requests; 200/404 for authenticated.
 *
 * @see de.vvwt.tm.web.GlobalExceptionHandler
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (Q-1a RED-first)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-38">DEC-38 — @ApplicationModuleTest IT canon</a>
 * @since E24S05
 */
@Controller
@RequestMapping("/certificate/tournaments/{tid}")
public class CertificateRenderController {

    private static final Logger log = LoggerFactory.getLogger(CertificateRenderController.class);

    private final CertificateAssembler certificateAssembler;
    private final CertificateTemplateService certificateTemplateService;
    private final LocationDisplayResolver locationDisplayResolver;
    private final TournamentRepository tournamentRepository;
    private final TeamRepository teamRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final PhaseRepository phaseRepository;
    private final TenantContext tenantContext;
    private final MessageSource messageSource;

    /**
     * Constructor injection — all collaborators are public interface types per DEC-35 + DEC-36.
     *
     * <p>Collaborators enumerated per AC-DEPS-INJECTED (empirically derived from legacy
     * PrintController:137-159 + E24S04 LocationDisplayResolver):
     *
     * <ul>
     *   <li>{@link CertificateAssembler} — certificate module public interface (E23S08)
     *   <li>{@link CertificateTemplateService} — certificate module public interface (E23S06)
     *   <li>{@link LocationDisplayResolver} — tenant module public interface (E24S04)
     *   <li>{@link TournamentRepository} — tournament module public repository
     *   <li>{@link TeamRepository} — tournament module public repository
     *   <li>{@link TeamAvatarRepository} — tournament module public repository
     *   <li>{@link PhaseRepository} — tournament module public repository
     *   <li>{@link TenantContext} — tenant module public interface
     *   <li>{@link MessageSource} — Spring core
     * </ul>
     */
    public CertificateRenderController(
            CertificateAssembler certificateAssembler,
            CertificateTemplateService certificateTemplateService,
            LocationDisplayResolver locationDisplayResolver,
            TournamentRepository tournamentRepository,
            TeamRepository teamRepository,
            TeamAvatarRepository teamAvatarRepository,
            PhaseRepository phaseRepository,
            TenantContext tenantContext,
            MessageSource messageSource) {
        this.certificateAssembler = certificateAssembler;
        this.certificateTemplateService = certificateTemplateService;
        this.locationDisplayResolver = locationDisplayResolver;
        this.tournamentRepository = tournamentRepository;
        this.teamRepository = teamRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.phaseRepository = phaseRepository;
        this.tenantContext = tenantContext;
        this.messageSource = messageSource;
    }

    // =========================================================================
    // AC-URL-SINGLE: GET /certificate/tournaments/{tid}/print/{teamId}
    // =========================================================================

    /**
     * Renders a single certificate for a team (AC-URL-SINGLE).
     *
     * <p>5 branches:
     *
     * <ul>
     *   <li>SVG template → {@code ResponseEntity<byte[]>} with {@code image/svg+xml} + {@code
     *       Content-Disposition: attachment}
     *   <li>HTML template → Mustache view {@code "certificate/print"} + model attribute {@code
     *       singleCertificate}
     *   <li>Template absent → 400 plaintext
     *   <li>No standings → 400 plaintext
     *   <li>MustacheException → 500 plaintext {@code "Mustache rendering error: " +
     *       ex.getMessage()}
     * </ul>
     *
     * <p>Unknown tournament → {@link TournamentNotFoundException} → 404 via {@link
     * de.vvwt.tm.web.GlobalExceptionHandler#handleTournamentNotFound}.
     *
     * @param tid the tournament UUID (tenant-scoped)
     * @param teamId the team UUID
     * @param model Spring MVC model (used for HTML path)
     * @return response entity or view name
     */
    @GetMapping("/print/{teamId}")
    public Object singleCertificate(
            @PathVariable("tid") UUID tid, @PathVariable("teamId") UUID teamId, Model model) {
        Tournament tournament =
                tournamentRepository
                        .findById(tid)
                        .orElseThrow(() -> new TournamentNotFoundException(tid));

        Optional<CertificateTemplateService.TemplateFile> templateOpt =
                certificateTemplateService.retrieveFile(tid);
        if (templateOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            "Keine Urkunden-Vorlage hochgeladen. Bitte laden Sie zuerst eine"
                                    + " Vorlage hoch.");
        }

        Optional<Phase> finalPhaseOpt = certificateAssembler.getFinalPhase(tid);
        if (finalPhaseOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            "Es sind keine Spielergebnisse vorhanden. Bitte spielen Sie zuerst die"
                                    + " Spiele.");
        }

        List<CertificateAssembler.AvatarPlacement> placements =
                certificateAssembler.computePlacementOrder(tid, finalPhaseOpt.get());
        if (placements.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            "Es sind keine Spielergebnisse vorhanden. Bitte spielen Sie zuerst die"
                                    + " Spiele.");
        }

        CertificateAssembler.AvatarPlacement teamPlacement =
                placements.stream()
                        .filter(ap -> teamId.equals(ap.teamId()))
                        .findFirst()
                        .orElseThrow(() -> new TournamentNotFoundException(teamId));

        String locationDisplayName = locationDisplayResolver.resolveLocationDisplayName();

        CertificateTemplateService.TemplateFile templateFile = templateOpt.get();
        String format = templateFile.metadata().format();

        if ("svg".equals(format)) {
            String templateContent = readTemplateContent(templateFile);
            List<CertificatePlacementRow> svgRows =
                    certificateAssembler.buildSvgRows(
                            tournament, List.of(teamPlacement), locationDisplayName);

            try {
                String renderedSvg =
                        certificateAssembler.renderSvgTemplate(templateContent, svgRows.get(0));
                byte[] svgBytes = renderedSvg.getBytes(StandardCharsets.UTF_8);

                String safeTeamName = sanitizeFilename(svgRows.get(0).teamName());
                String filename = teamPlacement.placement() + "-" + safeTeamName + ".svg";

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType("image/svg+xml"));
                headers.set(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"");
                return ResponseEntity.ok().headers(headers).body(svgBytes);

            } catch (MustacheException ex) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Mustache rendering error: " + ex.getMessage());
            }

        } else {
            List<CertificatePlacementRow> htmlRows =
                    certificateAssembler.buildHtmlRows(
                            tournament, List.of(teamPlacement), locationDisplayName);
            CertificatePlacementRow row = htmlRows.get(0);

            model.addAttribute("singleCertificate", certificateAssembler.toMustacheMap(row));
            model.addAttribute("certificates", List.of(certificateAssembler.toMustacheMap(row)));
            return "certificate/print";
        }
    }

    // =========================================================================
    // AC-URL-BATCH: GET /certificate/tournaments/{tid}/print
    // =========================================================================

    /**
     * Renders all certificates for a tournament in batch (AC-URL-BATCH).
     *
     * <p>4 branches:
     *
     * <ul>
     *   <li>SVG template → ZIP archive ({@code application/zip}) with one SVG per team
     *   <li>HTML template → Mustache view {@code "certificate/print-all"} + model attribute {@code
     *       certificates}
     *   <li>Template absent → 400 plaintext
     *   <li>MustacheException → 500 plaintext
     * </ul>
     *
     * @param tid the tournament UUID (tenant-scoped)
     * @param model Spring MVC model (used for HTML path)
     * @return response entity or view name
     */
    @GetMapping("/print")
    public Object allCertificates(@PathVariable("tid") UUID tid, Model model) {
        Tournament tournament =
                tournamentRepository
                        .findById(tid)
                        .orElseThrow(() -> new TournamentNotFoundException(tid));

        Optional<CertificateTemplateService.TemplateFile> templateOpt =
                certificateTemplateService.retrieveFile(tid);
        if (templateOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            "Keine Urkunden-Vorlage hochgeladen. Bitte laden Sie zuerst eine"
                                    + " Vorlage hoch.");
        }

        Optional<Phase> finalPhaseOpt = certificateAssembler.getFinalPhase(tid);
        if (finalPhaseOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            "Es sind keine Spielergebnisse vorhanden. Bitte spielen Sie zuerst die"
                                    + " Spiele.");
        }

        List<CertificateAssembler.AvatarPlacement> placements =
                certificateAssembler.computePlacementOrder(tid, finalPhaseOpt.get());
        if (placements.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            "Es sind keine Spielergebnisse vorhanden. Bitte spielen Sie zuerst die"
                                    + " Spiele.");
        }

        String locationDisplayName = locationDisplayResolver.resolveLocationDisplayName();

        CertificateTemplateService.TemplateFile templateFile = templateOpt.get();
        String format = templateFile.metadata().format();

        if ("svg".equals(format)) {
            String templateContent = readTemplateContent(templateFile);
            List<CertificatePlacementRow> svgRows =
                    certificateAssembler.buildSvgRows(tournament, placements, locationDisplayName);

            try {
                byte[] zipBytes = buildSvgZip(svgRows, templateContent);
                String tournamentName =
                        sanitizeFilename(
                                tournament.getDescription() != null
                                        ? tournament.getDescription()
                                        : "urkunden");
                String zipFilename = tournamentName + "-urkunden.zip";

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType("application/zip"));
                headers.set(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + zipFilename + "\"");
                return ResponseEntity.ok().headers(headers).body(zipBytes);

            } catch (MustacheException ex) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Mustache rendering error: " + ex.getMessage());
            } catch (IOException ex) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("ZIP creation error: " + ex.getMessage());
            }

        } else {
            List<CertificatePlacementRow> htmlRows =
                    certificateAssembler.buildHtmlRows(tournament, placements, locationDisplayName);

            List<Map<String, Object>> certificateMaps = new ArrayList<>();
            for (int i = 0; i < htmlRows.size(); i++) {
                Map<String, Object> certMap =
                        new LinkedHashMap<>(certificateAssembler.toMustacheMap(htmlRows.get(i)));
                certMap.put("showPageBreak", i > 0);
                certificateMaps.add(certMap);
            }

            model.addAttribute("certificates", certificateMaps);
            return "certificate/print-all";
        }
    }

    // =========================================================================
    // Private utilities — freshly authored (not copy-paste from legacy)
    // =========================================================================

    private String readTemplateContent(CertificateTemplateService.TemplateFile templateFile) {
        try (InputStream is = templateFile.inputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new CertificateTemplateStorageException(
                    "Failed to read certificate template: " + ex.getMessage(), ex);
        }
    }

    private byte[] buildSvgZip(List<CertificatePlacementRow> svgRows, String templateContent)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (CertificatePlacementRow row : svgRows) {
                String renderedSvg = certificateAssembler.renderSvgTemplate(templateContent, row);
                byte[] svgBytes = renderedSvg.getBytes(StandardCharsets.UTF_8);

                String paddedPlacement = String.format("%02d", row.placement());
                String safeTeamName = sanitizeFilename(row.teamName());
                String entryName = paddedPlacement + "-" + safeTeamName + ".svg";

                ZipEntry entry = new ZipEntry(entryName);
                entry.setSize(svgBytes.length);
                zos.putNextEntry(entry);
                zos.write(svgBytes);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "team";
        }
        String safe = name.replaceAll("[/\\\\:*?\"<>| \t]", "_").replaceAll("^[. ]+|[. ]+$", "");
        return safe.isBlank() ? "team" : safe;
    }
}
