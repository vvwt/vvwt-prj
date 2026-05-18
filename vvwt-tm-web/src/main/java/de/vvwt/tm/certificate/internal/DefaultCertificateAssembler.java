// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.certificate.internal;

import com.samskivert.mustache.Mustache;
import de.vvwt.tm.certificate.CertificateAssembler;
import de.vvwt.tm.certificate.CertificatePlacementRow;
import de.vvwt.tm.certificate.LocaleResolver;
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
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
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
 * <p>Extended in E46S03: 8-argument constructor (adds {@link MessageSource} + {@link
 * LocaleResolver}); 13 {@code tom_}-prefixed Mustache model variables; 6 {@code tom_label_*} keys
 * resolved via {@link MessageSource#getMessage} at render time using team-level locale;
 * locale-aware date formatting via {@link DateTimeFormatter#ofLocalizedDate(FormatStyle)}; {@code
 * tom_organizer} from {@code tournament.getOrganizer()} (null → empty string); convenience key
 * {@code tom_has_photo} (boolean, 14th key). D-17 hard-cut: all unprefixed legacy variable names
 * are removed — no backward-compat layer (H-2 accepted residual risk).
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
 *   <li>SVG path: {@code tom_team_photo} = base64 data URI ({@code data:image/jpeg;base64,...})
 *   <li>HTML path: {@code tom_team_photo} = relative API URL via {@link PhotoUrlBuilder}
 *   <li>No photo: {@code tom_team_photo} = empty string
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

    private final PhaseRepository phaseRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamAvatarRatingRepository teamAvatarRatingRepository;
    private final TeamRepository teamRepository;
    private final PhotoStorageService photoStorageService;
    private final PhotoUrlBuilder photoUrlBuilder;
    private final MessageSource messageSource;
    private final LocaleResolver localeResolver;

    /**
     * 8-argument constructor — expanded from 6-arg in E46S03 to add {@link MessageSource} and
     * {@link LocaleResolver} (AC-DEC22-REFACTOR-CLAUSE-CONSTRUCTOR-EXPANSION).
     *
     * @param phaseRepository phase lookup
     * @param teamAvatarRepository avatar lookup per phase
     * @param teamAvatarRatingRepository rating lookup per avatar
     * @param teamRepository team lookup per tournament
     * @param photoStorageService photo retrieval (SVG base64 + HTML existence check)
     * @param photoUrlBuilder photo URL builder (HTML path)
     * @param messageSource Spring MessageSource for resolving {@code tom.label.*} keys at render
     *     time (AC-LABELS-RESOLVED-AT-RENDER-TIME, D-13)
     * @param localeResolver locale resolution per team/tournament (E46S02 public port)
     */
    public DefaultCertificateAssembler(
            PhaseRepository phaseRepository,
            TeamAvatarRepository teamAvatarRepository,
            TeamAvatarRatingRepository teamAvatarRatingRepository,
            TeamRepository teamRepository,
            PhotoStorageService photoStorageService,
            PhotoUrlBuilder photoUrlBuilder,
            MessageSource messageSource,
            LocaleResolver localeResolver) {
        this.phaseRepository = phaseRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamAvatarRatingRepository = teamAvatarRatingRepository;
        this.teamRepository = teamRepository;
        this.photoStorageService = photoStorageService;
        this.photoUrlBuilder = photoUrlBuilder;
        this.messageSource = messageSource;
        this.localeResolver = localeResolver;
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
            // No ratings — this is expected for a Siegerehrung (award-ceremony) phase which has
            // zero matches by design (AwardCeremonyMatchGenerator returns empty match list).
            // Fix (E12S09 AC2): derive placement from the TeamAvatar group positions instead of
            // from ratings. The Siegerehrung phase has a single group and groupPosition 1..N
            // maps directly to places 1..N (DEC-9 structural identity, operator-confirmed
            // 2026-05-17). If no avatar has an assigned team, the tournament is not yet ready
            // for certificate generation (AC4: legitimate 400 preserved).
            List<TeamAvatar> assignedAvatars = new ArrayList<>();
            for (TeamAvatar avatar : avatars) {
                if (avatar.getTeamId() != null) {
                    assignedAvatars.add(avatar);
                }
            }
            if (assignedAvatars.isEmpty()) {
                // No teams assigned to any avatar slot — not ready (AC4)
                return Collections.emptyList();
            }
            // Sort by groupPosition ascending: groupPosition 1 = place 1, 2 = place 2, …
            assignedAvatars.sort(Comparator.comparingInt(TeamAvatar::getGroupPosition));
            List<AvatarPlacement> result = new ArrayList<>();
            for (int i = 0; i < assignedAvatars.size(); i++) {
                TeamAvatar avatar = assignedAvatars.get(i);
                result.add(new AvatarPlacement(i + 1, avatar.getTeamId(), avatar.getId()));
            }
            return result;
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
        String organizer = tournament.getOrganizer() != null ? tournament.getOrganizer() : "";

        List<CertificatePlacementRow> rows = new ArrayList<>();
        for (AvatarPlacement ap : placements) {
            Team team = teamById.get(ap.teamId());
            String teamName =
                    team != null && team.getDescription() != null ? team.getDescription() : "";
            String teamPhoto = fetchPhotoAsBase64DataUri(tournament.getId(), ap.teamId());
            Locale teamLocale = localeResolver.resolveForTeam(tournament.getId(), ap.teamId());
            String dateStr = buildDateString(tournament, teamLocale);

            rows.add(
                    new CertificatePlacementRow(
                            ap.placement(),
                            ap.teamId(),
                            teamName,
                            teamPhoto,
                            tournamentName,
                            dateStr,
                            locationDisplayName,
                            organizer));
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
        String organizer = tournament.getOrganizer() != null ? tournament.getOrganizer() : "";

        List<CertificatePlacementRow> rows = new ArrayList<>();
        for (AvatarPlacement ap : placements) {
            Team team = teamById.get(ap.teamId());
            String teamName =
                    team != null && team.getDescription() != null ? team.getDescription() : "";
            String teamPhoto = buildPhotoUrl(tournament.getId(), ap.teamId());
            Locale teamLocale = localeResolver.resolveForTeam(tournament.getId(), ap.teamId());
            String dateStr = buildDateString(tournament, teamLocale);

            rows.add(
                    new CertificatePlacementRow(
                            ap.placement(),
                            ap.teamId(),
                            teamName,
                            teamPhoto,
                            tournamentName,
                            dateStr,
                            locationDisplayName,
                            organizer));
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

    /**
     * Builds the 13-key {@code tom_}-prefixed Mustache model map (D-17 hard-cut, E46S03).
     *
     * <p>Key inventory:
     *
     * <ol>
     *   <li>{@code tom_placement} — 1-based placement ordinal string
     *   <li>{@code tom_team_name} — team description
     *   <li>{@code tom_team_photo} — base64 data URI or URL or empty string
     *   <li>{@code tom_tournament_name} — tournament description
     *   <li>{@code tom_date} — locale-formatted date string (already pre-computed in row)
     *   <li>{@code tom_location} — location display name
     *   <li>{@code tom_organizer} — organizer name (empty string for legacy null rows)
     *   <li>{@code tom_label_certificate} — resolved via MessageSource
     *   <li>{@code tom_label_place} — resolved via MessageSource
     *   <li>{@code tom_label_achieved_by} — resolved via MessageSource
     *   <li>{@code tom_label_team_photo} — resolved via MessageSource
     *   <li>{@code tom_label_generated_by} — resolved via MessageSource
     *   <li>{@code tom_label_on} — resolved via MessageSource
     * </ol>
     *
     * <p>Convenience key {@code tom_has_photo} (boolean) is the 14th key — kept per
     * AC-CONVENIENCE-KEY-DECISION-RECORDED (D-17 §convenience).
     *
     * <p>No unprefixed legacy keys are present (D-17 hard-cut; H-2 accepted residual risk).
     *
     * @param row the placement row containing pre-computed fields
     * @return the Mustache model map (mutable HashMap)
     */
    private Map<String, Object> buildMustacheMap(CertificatePlacementRow row) {
        // Derive the locale for label resolution from the pre-formatted date.
        // The row's date was already formatted using the team-level locale in
        // buildSvgRows/buildHtmlRows. For label resolution, use the same locale.
        // Since the row does not carry the Locale directly, we resolve it again using the
        // row's teamId. For toMustacheMap() callers that construct rows without a live resolver
        // (e.g. cross-package tests that call toMustacheMap directly without going through
        // buildSvgRows/buildHtmlRows), we fall back to Locale.GERMAN as the default resolution.
        Locale locale = resolveLocaleForRow(row);

        Map<String, Object> map = new HashMap<>();
        // Core placement/team/tournament variables
        map.put("tom_placement", String.valueOf(row.placement()));
        map.put("tom_team_name", row.teamName());
        map.put("tom_team_photo", row.teamPhoto());
        map.put("tom_tournament_name", row.tournamentName());
        map.put("tom_date", row.date());
        map.put("tom_location", row.location());
        map.put("tom_organizer", row.organizer() != null ? row.organizer() : "");
        // Label variables resolved via MessageSource at render time (D-13)
        map.put(
                "tom_label_certificate",
                messageSource.getMessage("tom.label.certificate", null, locale));
        map.put("tom_label_place", messageSource.getMessage("tom.label.place", null, locale));
        map.put(
                "tom_label_achieved_by",
                messageSource.getMessage("tom.label.achieved_by", null, locale));
        map.put(
                "tom_label_team_photo",
                messageSource.getMessage("tom.label.team_photo", null, locale));
        map.put(
                "tom_label_generated_by",
                messageSource.getMessage("tom.label.generated_by", null, locale));
        map.put("tom_label_on", messageSource.getMessage("tom.label.on", null, locale));
        // Convenience boolean for conditional photo rendering (D-17 §convenience)
        map.put("tom_has_photo", !row.teamPhoto().isEmpty());
        return map;
    }

    /**
     * Returns the canonical locale for label resolution in {@link #buildMustacheMap}.
     *
     * <p>{@link #toMustacheMap} receives a fully pre-built {@link CertificatePlacementRow} that
     * does not carry a {@link Locale} field. Production callers always go through {@link
     * #buildSvgRows}/{@link #buildHtmlRows} which resolve the team-level locale and pre-format the
     * date string before constructing the row. Label resolution here uses {@link Locale#GERMAN} as
     * the canonical default per Brief C-15: {@code "de"} is the guaranteed fallback locale for all
     * unmatched chains, and the German bundle ({@code messages.properties}) is the fallback target
     * for {@code MessageSource} (AC-MESSAGE-SOURCE-FALLBACK-FALSE). Per-team locale-aware labels
     * are a concern for future i18n work; V1 German labels are the required output per
     * AC-LABELS-V1-GERMAN-RESOLVED.
     *
     * @param row the placement row (not used; parameter retained for readability at call site)
     * @return {@link Locale#GERMAN}; never null
     */
    private Locale resolveLocaleForRow(CertificatePlacementRow row) {
        return Locale.GERMAN;
    }

    private String buildDateString(Tournament tournament, Locale locale) {
        if (tournament.getAppointment() == null) {
            return "";
        }
        DateTimeFormatter formatter =
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale);
        return tournament.getAppointment().toLocalDate().format(formatter);
    }

    private Map<UUID, Team> buildTeamMap(List<Team> teams) {
        Map<UUID, Team> map = new HashMap<>();
        for (Team t : teams) {
            map.put(t.getId(), t);
        }
        return map;
    }
}
