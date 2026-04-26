package de.vvwt.tm.certificate.internal;

import com.samskivert.mustache.Mustache;
import de.vvwt.tm.certificate.CertificateAssembler;
import de.vvwt.tm.certificate.CertificatePlacementRow;
import de.vvwt.tm.photo.PhotoStorageService;
import de.vvwt.tm.photo.PhotoUrlBuilder;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Q-1a TDD-rebuilt implementation of {@link CertificateAssembler} (E36S05, DEC-22 Iron Law).
 *
 * <p>The legacy Q-1b impl ({@code de.vvwt.tm.certificate.internal.DefaultCertificateAssembler} from
 * E23S08) was deleted at E36S05 commit {@code c40db76} and this class was authored RED-first under
 * DEC-22 reconstruction-in-place discipline. The canonical FQN is preserved per D-7 Option γ so
 * that consumer imports ({@code CertificateRenderController} via the interface, Spring context via
 * bean type) require no adjustment.
 *
 * <p>Implements the {@link CertificateAssembler} public interface (6 methods + nested {@code
 * AvatarPlacement} record) extracted in E23S08. Constructor injection preserves the original
 * 6-argument signature verbatim per AC-CONSTRUCTOR-INJECTION-PRESERVED (C-3 gate).
 *
 * <h2>Placement calculation (DEC-33)</h2>
 *
 * <p>The "final phase" is the phase with the highest {@code sequenceNumber} for the tournament.
 * Within that phase: (a) load all {@link TeamAvatar}s, (b) for each avatar load its {@link
 * TeamAvatarRating}, (c) sort by {@link TeamAvatarRating#compareTo} (DEC-33: points DESC,
 * setQuotient DESC, ballQuotient DESC, isWithoutAssessment last), (d) assign 1-based ordinals.
 *
 * <h2>Photo embedding (E12S06 AC6)</h2>
 *
 * <ul>
 *   <li>SVG path: {@code teamPhoto} = base64 data URI ({@code data:image/jpeg;base64,...})
 *   <li>HTML path: {@code teamPhoto} = relative API URL via {@link PhotoUrlBuilder}
 *   <li>No photo: {@code teamPhoto} = empty string
 * </ul>
 *
 * <h2>Mustache rendering (E12S01 AC6)</h2>
 *
 * <p>All certificate Mustache rendering uses {@code escapeHTML(false)} and {@code
 * defaultValue("")}. The {@code escapeHTML(false)} setting is CRITICAL — it preserves base64 {@code
 * ==} padding in data URIs (HTML escaping would corrupt them to {@code &#x3D;&#x3D;}).
 *
 * @see CertificateAssembler
 * @see CertificatePlacementRow
 * @since E36S05
 */
@Service
public class DefaultCertificateAssembler implements CertificateAssembler {

    private static final Logger log = LoggerFactory.getLogger(DefaultCertificateAssembler.class);

    /** Formatter for the {{date}} template variable — locale German (e.g., "15. April 2026"). */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("d. MMMM yyyy", java.util.Locale.GERMAN);

    private final PhaseRepository phaseRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamAvatarRatingRepository teamAvatarRatingRepository;
    private final TeamRepository teamRepository;
    private final PhotoStorageService photoStorageService;
    private final PhotoUrlBuilder photoUrlBuilder;

    /**
     * 6-argument constructor — preserved verbatim per AC-CONSTRUCTOR-INJECTION-PRESERVED (C-3
     * gate).
     *
     * @param phaseRepository phase lookup
     * @param teamAvatarRepository avatar lookup per phase
     * @param teamAvatarRatingRepository rating lookup per avatar
     * @param teamRepository team lookup per tournament
     * @param photoStorageService photo retrieval (SVG base64 + HTML existence check)
     * @param photoUrlBuilder photo URL builder (HTML path)
     */
    public DefaultCertificateAssembler(
            PhaseRepository phaseRepository,
            TeamAvatarRepository teamAvatarRepository,
            TeamAvatarRatingRepository teamAvatarRatingRepository,
            TeamRepository teamRepository,
            PhotoStorageService photoStorageService,
            PhotoUrlBuilder photoUrlBuilder) {
        this.phaseRepository = phaseRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamAvatarRatingRepository = teamAvatarRatingRepository;
        this.teamRepository = teamRepository;
        this.photoStorageService = photoStorageService;
        this.photoUrlBuilder = photoUrlBuilder;
    }

    // -------------------------------------------------------------------------
    // Phase lookup
    // -------------------------------------------------------------------------

    @Override
    public Optional<Phase> getFinalPhase(UUID tournamentId) {
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        return phases.stream().max(Comparator.comparingInt(Phase::getSequenceNumber));
    }

    // -------------------------------------------------------------------------
    // Placement computation (DEC-33)
    // -------------------------------------------------------------------------

    @Override
    public List<AvatarPlacement> computePlacementOrder(UUID tournamentId, Phase finalPhase) {
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(finalPhase.getId());
        if (avatars.isEmpty()) {
            return Collections.emptyList();
        }

        // Build map avatarId → rating; skip avatars with no rating (match not played yet)
        Map<UUID, TeamAvatarRating> ratingByAvatarId = new HashMap<>();
        for (TeamAvatar avatar : avatars) {
            teamAvatarRatingRepository
                    .findById(avatar.getId())
                    .ifPresent(rating -> ratingByAvatarId.put(avatar.getId(), rating));
        }

        if (ratingByAvatarId.isEmpty()) {
            // No ratings exist — no matches played yet
            return Collections.emptyList();
        }

        // Sort avatars by DEC-33 rating order (only those with ratings)
        List<TeamAvatar> rankedAvatars = new ArrayList<>();
        for (TeamAvatar avatar : avatars) {
            if (ratingByAvatarId.containsKey(avatar.getId())) {
                rankedAvatars.add(avatar);
            }
        }
        rankedAvatars.sort(
                (a, b) ->
                        ratingByAvatarId.get(a.getId()).compareTo(ratingByAvatarId.get(b.getId())));

        // Assign 1-based placement ordinals
        List<AvatarPlacement> result = new ArrayList<>();
        for (int i = 0; i < rankedAvatars.size(); i++) {
            TeamAvatar avatar = rankedAvatars.get(i);
            result.add(new AvatarPlacement(i + 1, avatar.getTeamId(), avatar.getId()));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Row building (E12S06 AC6 — template variables)
    // -------------------------------------------------------------------------

    @Override
    public List<CertificatePlacementRow> buildSvgRows(
            Tournament tournament, List<AvatarPlacement> placements, String locationDisplayName) {
        List<Team> teams = teamRepository.findByTournamentId(tournament.getId());
        Map<UUID, Team> teamById = buildTeamMap(teams);

        String tournamentName =
                tournament.getDescription() != null ? tournament.getDescription() : "";
        String dateStr = buildDateString(tournament);

        List<CertificatePlacementRow> rows = new ArrayList<>();
        for (AvatarPlacement ap : placements) {
            Team team = teamById.get(ap.teamId());
            String teamName =
                    team != null && team.getDescription() != null ? team.getDescription() : "";
            String teamPhoto = fetchPhotoAsBase64DataUri(tournament.getId(), ap.teamId());

            rows.add(
                    new CertificatePlacementRow(
                            ap.placement(),
                            ap.teamId(),
                            teamName,
                            teamPhoto,
                            tournamentName,
                            dateStr,
                            locationDisplayName));
        }
        return rows;
    }

    @Override
    public List<CertificatePlacementRow> buildHtmlRows(
            Tournament tournament, List<AvatarPlacement> placements, String locationDisplayName) {
        List<Team> teams = teamRepository.findByTournamentId(tournament.getId());
        Map<UUID, Team> teamById = buildTeamMap(teams);

        String tournamentName =
                tournament.getDescription() != null ? tournament.getDescription() : "";
        String dateStr = buildDateString(tournament);

        List<CertificatePlacementRow> rows = new ArrayList<>();
        for (AvatarPlacement ap : placements) {
            Team team = teamById.get(ap.teamId());
            String teamName =
                    team != null && team.getDescription() != null ? team.getDescription() : "";
            String teamPhoto = buildPhotoUrl(tournament.getId(), ap.teamId());

            rows.add(
                    new CertificatePlacementRow(
                            ap.placement(),
                            ap.teamId(),
                            teamName,
                            teamPhoto,
                            tournamentName,
                            dateStr,
                            locationDisplayName));
        }
        return rows;
    }

    // -------------------------------------------------------------------------
    // Mustache rendering (E12S01 AC6)
    // -------------------------------------------------------------------------

    @Override
    public String renderSvgTemplate(String templateContent, CertificatePlacementRow row) {
        Mustache.Compiler compiler =
                Mustache.compiler()
                        .escapeHTML(
                                false) // CRITICAL: preserves base64 "==" in data URIs (E12S01 AC6)
                        .defaultValue(""); // lenient: missing keys render as empty string
        com.samskivert.mustache.Template template = compiler.compile(templateContent);
        StringWriter writer = new StringWriter();
        template.execute(buildMustacheMap(row), writer);
        return writer.toString();
    }

    // -------------------------------------------------------------------------
    // Model building for HTML
    // -------------------------------------------------------------------------

    @Override
    public Map<String, Object> toMustacheMap(CertificatePlacementRow row) {
        return buildMustacheMap(row);
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (accessible by same-package tests per DEC-36)
    // -------------------------------------------------------------------------

    /**
     * Fetches the team photo and encodes it as a base64 data URI for SVG embedding (E12S06 AC6).
     *
     * <p>Returns an empty string if no photo exists or if an I/O error occurs during reading.
     *
     * @param tournamentId the tournament UUID
     * @param teamId the team UUID
     * @return base64 data URI (e.g., {@code data:image/jpeg;base64,...}) or empty string
     */
    String fetchPhotoAsBase64DataUri(UUID tournamentId, UUID teamId) {
        Optional<PhotoStorageService.PhotoResult> photoResult =
                photoStorageService.retrieve(tournamentId, teamId);
        if (photoResult.isEmpty()) {
            return "";
        }

        PhotoStorageService.PhotoResult result = photoResult.get();
        try (InputStream is = result.inputStream()) {
            byte[] photoBytes = is.readAllBytes();
            String base64 = Base64.getEncoder().encodeToString(photoBytes);
            String contentType = result.contentType();
            return "data:" + contentType + ";base64," + base64;
        } catch (IOException ex) {
            log.warn(
                    "[tm-cert] Failed to read photo for team={} tournament={}: {}",
                    teamId,
                    tournamentId,
                    ex.getMessage());
            return "";
        }
    }

    /**
     * Returns the relative API URL for a team's photo (E12S06 AC6 — HTML path).
     *
     * <p>Returns an empty string if no photo exists for this team.
     *
     * @param tournamentId the tournament UUID
     * @param teamId the team UUID
     * @return relative URL string or empty string
     */
    String buildPhotoUrl(UUID tournamentId, UUID teamId) {
        if (!photoStorageService.hasPhoto(tournamentId, teamId)) {
            return "";
        }
        return photoUrlBuilder.buildTeamPhotoUrl(tournamentId, teamId);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> buildMustacheMap(CertificatePlacementRow row) {
        Map<String, Object> map = new HashMap<>();
        map.put("placement", String.valueOf(row.placement()));
        map.put("teamName", row.teamName());
        map.put("teamPhoto", row.teamPhoto());
        map.put("tournamentName", row.tournamentName());
        map.put("date", row.date());
        map.put("location", row.location());
        // Convenience boolean for conditional photo rendering in HTML templates
        map.put("hasPhoto", !row.teamPhoto().isEmpty());
        return map;
    }

    private String buildDateString(Tournament tournament) {
        return tournament.getAppointment() != null
                ? tournament.getAppointment().toLocalDate().format(DATE_FORMATTER)
                : "";
    }

    private Map<UUID, Team> buildTeamMap(List<Team> teams) {
        Map<UUID, Team> map = new HashMap<>();
        for (Team t : teams) {
            map.put(t.getId(), t);
        }
        return map;
    }
}
