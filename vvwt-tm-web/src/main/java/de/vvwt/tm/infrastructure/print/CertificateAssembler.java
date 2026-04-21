package de.vvwt.tm.infrastructure.print;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import de.vvwt.tm.domain.photo.PhotoStorageService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Assembler for certificate placement data and Mustache rendering (E12S06).
 *
 * <p>Analogous to {@link LaufzettelAssembler}: collects data from domain repositories and produces
 * either rendered SVG bytes or Mustache model maps for HTML rendering.
 *
 * <h2>Placement calculation (AC5, D-33)</h2>
 *
 * <p>The "final phase" is the phase with the highest {@code sequenceNumber} for the tournament.
 * Within that phase: (a) load all {@link TeamAvatar}s, (b) for each avatar load its {@link
 * TeamAvatarRating}, (c) sort by {@link TeamAvatarRating#compareTo} (D-33: points DESC, setQuotient
 * DESC, ballQuotient DESC, isWithoutAssessment last), (d) assign 1-based ordinals.
 *
 * <h2>Photo embedding (AC6)</h2>
 *
 * <ul>
 *   <li>SVG path: {@code teamPhoto} = base64 data URI ({@code data:image/jpeg;base64,...})
 *   <li>HTML path: {@code teamPhoto} = relative API URL ({@code
 *       /api/tournaments/{tId}/teams/{teamId}/photo})
 *   <li>No photo: {@code teamPhoto} = empty string (template must handle absence gracefully)
 * </ul>
 *
 * <h2>Mustache rendering (E12S01 AC6)</h2>
 *
 * <p>All certificate Mustache rendering uses:
 *
 * <ul>
 *   <li>{@code escapeHTML(false)} — preserves base64 data URIs ({@code ==} must not become {@code
 *       &#x3D;&#x3D;})
 *   <li>{@code defaultValue("")} — lenient mode; missing keys render as empty string
 * </ul>
 *
 * <h2>Tenant scoping (DEC-5, AC11)</h2>
 *
 * <p>All repository calls are tenant-scoped. Tournament and team existence are validated by the
 * calling controller before invoking this assembler.
 *
 * @see LaufzettelAssembler
 * @see CertificatePlacementRow
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S06.story.md">Story
 *     E12S06</a>
 */
@Service
public class CertificateAssembler {

    private static final Logger log = LoggerFactory.getLogger(CertificateAssembler.class);

    /** Formatter for the {{date}} template variable (AC6). */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("d. MMMM yyyy", java.util.Locale.GERMAN);

    private final PhaseRepository phaseRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamAvatarRatingRepository teamAvatarRatingRepository;
    private final TeamRepository teamRepository;
    private final PhotoStorageService photoStorageService;
    private final JdbcTemplate jdbcTemplate;

    public CertificateAssembler(
            PhaseRepository phaseRepository,
            TeamAvatarRepository teamAvatarRepository,
            TeamAvatarRatingRepository teamAvatarRatingRepository,
            TeamRepository teamRepository,
            PhotoStorageService photoStorageService,
            JdbcTemplate jdbcTemplate) {
        this.phaseRepository = phaseRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamAvatarRatingRepository = teamAvatarRatingRepository;
        this.teamRepository = teamRepository;
        this.photoStorageService = photoStorageService;
        this.jdbcTemplate = jdbcTemplate;
    }

    // -------------------------------------------------------------------------
    // Phase lookup
    // -------------------------------------------------------------------------

    /**
     * Returns the final phase (highest sequenceNumber) for the given tournament, or empty if the
     * tournament has no phases.
     *
     * @param tournamentId the tournament UUID (tenant-scoped)
     * @return the final phase, or empty if no phases exist
     */
    public Optional<Phase> getFinalPhase(UUID tournamentId) {
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        return phases.stream().max(Comparator.comparingInt(Phase::getSequenceNumber));
    }

    // -------------------------------------------------------------------------
    // Placement computation (AC5, D-33)
    // -------------------------------------------------------------------------

    /**
     * Computes the certificate placement list for the given final phase.
     *
     * <p>Returns an empty list if the phase has no TeamAvatarRatings — this signals AC8 (no
     * standings: no matches have been played yet).
     *
     * <p>Placement is 1-based. {@code isWithoutAssessment} teams rank after normal teams per D-26,
     * as implemented in {@link TeamAvatarRating#compareTo}.
     *
     * @param tournamentId the tournament UUID
     * @param finalPhase the phase to compute standings from
     * @return ordered list of (avatarId, teamId, rating) tuples, placement=index+1
     */
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
            // No ratings exist — no matches played (AC8)
            return Collections.emptyList();
        }

        // Sort avatars by D-33 rating order (only those with ratings)
        List<TeamAvatar> rankedAvatars = new ArrayList<>();
        for (TeamAvatar avatar : avatars) {
            if (ratingByAvatarId.containsKey(avatar.getId())) {
                rankedAvatars.add(avatar);
            }
        }
        rankedAvatars.sort(
                (a, b) ->
                        ratingByAvatarId.get(a.getId()).compareTo(ratingByAvatarId.get(b.getId())));

        // Assign 1-based placement
        List<AvatarPlacement> result = new ArrayList<>();
        for (int i = 0; i < rankedAvatars.size(); i++) {
            TeamAvatar avatar = rankedAvatars.get(i);
            result.add(new AvatarPlacement(i + 1, avatar.getTeamId(), avatar.getId()));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Row building (AC6 — template variables)
    // -------------------------------------------------------------------------

    /**
     * Builds {@link CertificatePlacementRow} list for SVG certificate rendering.
     *
     * <p>SVG path: {@code teamPhoto} is a base64 data URI embedded inline.
     *
     * @param tournament the tournament entity
     * @param placements the ordered placement list from {@link #computePlacementOrder}
     * @param locationDisplayName the location display name ({{location}} variable)
     * @return ordered list of placement rows with base64-encoded photo values
     */
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

    /**
     * Builds {@link CertificatePlacementRow} list for HTML certificate rendering.
     *
     * <p>HTML path: {@code teamPhoto} is a relative URL to the photo API endpoint.
     *
     * @param tournament the tournament entity
     * @param placements the ordered placement list from {@link #computePlacementOrder}
     * @param locationDisplayName the location display name
     * @return ordered list of placement rows with photo URL values
     */
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
    // Mustache rendering (AC1, AC3 — SVG path)
    // -------------------------------------------------------------------------

    /**
     * Renders a certificate SVG template for a single team placement row.
     *
     * <p>Uses jmustache with {@code escapeHTML(false)} and {@code defaultValue("")} per E12S01 AC6
     * finding (prevents base64 data URI corruption).
     *
     * @param templateContent the raw Mustache template string (SVG content)
     * @param row the placement row with all template variable values
     * @return the rendered SVG as a UTF-8 string
     * @throws MustacheException if the template is malformed
     */
    public String renderSvgTemplate(String templateContent, CertificatePlacementRow row) {
        Mustache.Compiler compiler =
                Mustache.compiler()
                        .escapeHTML(
                                false) // CRITICAL: preserve base64 "==" in data URIs (E12S01 AC6)
                        .defaultValue(""); // lenient: missing keys render as empty string
        com.samskivert.mustache.Template template = compiler.compile(templateContent);
        StringWriter writer = new StringWriter();
        template.execute(buildMustacheMap(row), writer);
        return writer.toString();
    }

    // -------------------------------------------------------------------------
    // Model building for HTML (AC2, AC4 — HTML path)
    // -------------------------------------------------------------------------

    /**
     * Converts a {@link CertificatePlacementRow} to a jmustache-compatible attribute map.
     *
     * <p>All 6 D-4 template variables are present as keys so jmustache strict mode does not throw
     * on missing keys.
     *
     * @param row the placement row
     * @return attribute map for Mustache model
     */
    public Map<String, Object> toMustacheMap(CertificatePlacementRow row) {
        return buildMustacheMap(row);
    }

    // -------------------------------------------------------------------------
    // Location lookup
    // -------------------------------------------------------------------------

    /**
     * Returns the display name of the default location for the current tenant.
     *
     * <p>DEC-5: the default tenant has exactly one location. The locations table is queried
     * directly via JdbcTemplate — no Location domain entity exists yet.
     *
     * @param tenantId the active tenant UUID
     * @return the location display name, or an empty string if not found
     */
    public String resolveLocationDisplayName(UUID tenantId) {
        List<String> names =
                jdbcTemplate.query(
                        "SELECT display_name FROM locations WHERE tenant_id = ? LIMIT 1",
                        (rs, rowNum) -> rs.getString(1),
                        tenantId);
        return names.isEmpty() ? "" : names.get(0);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Fetches the team photo and encodes it as a base64 data URI for SVG embedding (AC6).
     *
     * <p>Returns an empty string if no photo exists or if an I/O error occurs during reading. The
     * template must handle an empty {@code {{teamPhoto}}} gracefully (per E12S01 AC6 findings).
     *
     * @param tournamentId the tournament UUID
     * @param teamId the team UUID
     * @return base64 data URI (e.g., {@code data:image/jpeg;base64,/9j/...}) or empty string
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
     * Returns the relative API URL for a team's photo (AC6 — HTML path).
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
        return "/api/tournaments/" + tournamentId + "/teams/" + teamId + "/photo";
    }

    /**
     * Builds a jmustache-compatible attribute map from a {@link CertificatePlacementRow}.
     *
     * <p>All 6 D-4 template variable keys are present so jmustache strict mode does not throw on
     * missing keys. Boolean-guarded sections use actual types.
     *
     * @param row the placement row
     * @return map with keys: placement, teamName, teamPhoto, tournamentName, date, location,
     *     hasPhoto
     */
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

    /**
     * Formats the tournament appointment date for the {@code {{date}}} variable (AC6).
     *
     * <p>Returns an empty string if no appointment is set.
     *
     * @param tournament the tournament entity
     * @return formatted date string (e.g., "15. April 2026") or empty string
     */
    private String buildDateString(Tournament tournament) {
        return tournament.getAppointment() != null
                ? tournament.getAppointment().toLocalDate().format(DATE_FORMATTER)
                : "";
    }

    /**
     * Builds a team UUID → Team lookup map.
     *
     * @param teams list of teams
     * @return map from team UUID to Team entity
     */
    private Map<UUID, Team> buildTeamMap(List<Team> teams) {
        Map<UUID, Team> map = new HashMap<>();
        for (Team t : teams) {
            map.put(t.getId(), t);
        }
        return map;
    }

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /**
     * Intermediate record linking a placement ordinal to an avatar's team ID (AC5).
     *
     * @param placement 1-based ordinal
     * @param teamId the team UUID
     * @param avatarId the avatar UUID (for identity tracing)
     */
    public record AvatarPlacement(int placement, UUID teamId, UUID avatarId) {}
}
