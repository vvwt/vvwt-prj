package de.vvwt.tm.infrastructure.print;

import com.samskivert.mustache.MustacheException;
import de.vvwt.tm.certificate.CertificateAssembler;
import de.vvwt.tm.certificate.CertificatePlacementRow;
import de.vvwt.tm.certificate.CertificateTemplateService;
import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.domain.repo.ActivityTypeRepository;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Spring MVC controller for print routes ({@code /print/**}).
 *
 * <h2>Story E08S07 — AC1, AC2, AC5, AC7, AC8, AC9</h2>
 *
 * <ul>
 *   <li>AC1: All print routes are served under {@code /print/**} by this controller.
 *   <li>AC2: {@code /print/**} is protected by basic auth in {@link
 *       de.vvwt.tm.auth.internal.SecurityConfig}. Unauthenticated requests receive 401 — enforced
 *       by Spring Security before this controller is invoked.
 *   <li>AC5: Mustache partials {@code print-header} and {@code print-page-break} are included in
 *       templates; the controller populates the model attributes they consume.
 *   <li>AC7: Non-existent tournament → 404. Tournament with no phases (draft) → human-readable
 *       Mustache error page, not a raw JSON error.
 *   <li>AC8: All user-visible strings sourced from {@link MessageSource} (German default per E05).
 *   <li>AC9: Tournament resolved via tenant-scoped {@link TournamentRepository} — cross-tenant
 *       access is impossible because the repository guards tenant scope on every query.
 * </ul>
 *
 * <h2>Story E08S08 — AC1, AC2, AC13, AC14, AC15</h2>
 *
 * <ul>
 *   <li>AC1: {@code GET /print/{tournamentId}/team-schedules/{teamId}} renders a single team's
 *       Laufzettel.
 *   <li>AC2: {@code GET /print/{tournamentId}/team-schedules} renders all teams' Laufzettel.
 *   <li>AC13: No matches → error page; non-existent team → 404.
 *   <li>AC14: All static text via {@link MessageSource} (German default).
 *   <li>AC15: Print routes inherit E08S07's basic auth enforcement (same {@code /print/**} rule).
 * </ul>
 *
 * <h2>Story E08S09 — AC1–AC11</h2>
 *
 * <ul>
 *   <li>AC1: {@code GET /print/{tournamentId}/activity-schedule/{activityTypeId}} renders the
 *       Mannschaftsfoto-Übersicht for the specified activity type.
 *   <li>AC2: Page header shows tournament name and activity type name.
 *   <li>AC3: Schedule table ordered by round number; empty rounds omitted.
 *   <li>AC4: Break separator rows inserted from timeline context.
 *   <li>AC5: Summary line — N teams total, across M rounds.
 *   <li>AC6: Unassigned warning section when some teams have no free round.
 *   <li>AC7: Time column omitted when tournament has no plannedStartTime.
 *   <li>AC8: CSS print styling — compact A4 layout.
 *   <li>AC9: Non-existent activityTypeId → 404.
 *   <li>AC10: All static text from MessageSource (German default).
 *   <li>AC11: Route inherits E08S07's basic auth enforcement.
 * </ul>
 *
 * <h2>DEC-12, DEC-15</h2>
 *
 * <p>Templates resolved from {@code classpath:/templates/print/} by the auto-configured Mustache
 * {@code ViewResolver}. Shared partials ({@code print-header}, {@code print-page-break}) live at
 * {@code classpath:/templates/} root so jmustache can resolve them via {@code {{> print-header}}}.
 * Static print CSS is served from {@code classpath:/static/print/assets/print.css} (bundled in the
 * jlink archive automatically per DEC-15).
 *
 * <h2>Story E12S06 — AC1–AC11</h2>
 *
 * <ul>
 *   <li>AC1: {@code GET /print/tournaments/{tournamentId}/certificates/{teamId}} → SVG certificate.
 *   <li>AC2: Same route → HTML certificate when template format is HTML.
 *   <li>AC3: {@code GET /print/tournaments/{tournamentId}/certificates} → ZIP of SVG certificates.
 *   <li>AC4: Same route → HTML all-certificates page when template format is HTML.
 *   <li>AC5: Placement derived from final-phase D-33 TeamAvatarRating order.
 *   <li>AC6: All 6 D-4 template variables filled by {@link CertificateAssembler}.
 *   <li>AC7: No template → 400 with i18n message.
 *   <li>AC8: No standings (no ratings) → 400 with i18n message.
 *   <li>AC9: Mustache errors → 500; unknown team/tournament → 404.
 *   <li>AC10: Error messages from message bundle (German default).
 *   <li>AC11: Admin auth enforced by {@code /print/**} security rule; tenant isolation via repo.
 * </ul>
 *
 * @see de.vvwt.tm.auth.internal.SecurityConfig — configures /print/** as authenticated,
 *     /print/assets/** as permitAll
 * @see LaufzettelAssembler
 * @see ActivityScheduleAssembler
 * @see CertificateAssembler
 */
@Controller
@RequestMapping("/print")
public class PrintController {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamRepository teamRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final PhaseBreakRepository phaseBreakRepository;
    private final ActivityTypeRepository activityTypeRepository;
    private final LaufzettelAssembler laufzettelAssembler;
    private final ActivityScheduleAssembler activityScheduleAssembler;
    private final CertificateAssembler certificateAssembler;
    private final CertificateTemplateService certificateTemplateService;
    private final TenantContext tenantContext;
    private final MessageSource messageSource;
    private final String appVersion;

    /**
     * JdbcTemplate for the {@link #resolveLocationDisplayName(UUID)} read-query (Q-1a, E23S10).
     *
     * <p>Migrated from {@code CertificateAssembler.resolveLocationDisplayName} per Brief D-13 C2=β.
     * Pre-Big-Bang: uses the shared (root) DataSource; {@code tenant_id = ?} discriminator is
     * preserved verbatim per DEC-39.
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor injection.
     *
     * <p>{@link BuildProperties} is optional — absent in test environments and plain IDE runs where
     * the app is not repackaged. Falls back to {@code "dev"}.
     */
    @Autowired
    public PrintController(
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            TeamRepository teamRepository,
            TeamAvatarRepository teamAvatarRepository,
            PhaseBreakRepository phaseBreakRepository,
            ActivityTypeRepository activityTypeRepository,
            LaufzettelAssembler laufzettelAssembler,
            ActivityScheduleAssembler activityScheduleAssembler,
            CertificateAssembler certificateAssembler,
            CertificateTemplateService certificateTemplateService,
            TenantContext tenantContext,
            MessageSource messageSource,
            @Autowired(required = false) BuildProperties buildProperties,
            JdbcTemplate jdbcTemplate) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamRepository = teamRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.phaseBreakRepository = phaseBreakRepository;
        this.activityTypeRepository = activityTypeRepository;
        this.laufzettelAssembler = laufzettelAssembler;
        this.activityScheduleAssembler = activityScheduleAssembler;
        this.certificateAssembler = certificateAssembler;
        this.certificateTemplateService = certificateTemplateService;
        this.tenantContext = tenantContext;
        this.messageSource = messageSource;
        this.appVersion = buildProperties != null ? buildProperties.getVersion() : "dev";
        this.jdbcTemplate = jdbcTemplate;
    }

    // =========================================================================
    // E08S07: Print index route
    // =========================================================================

    /**
     * Print index page for a tournament.
     *
     * <p>Mapped to {@code GET /print/{tournamentId}} (AC1 — E08S07).
     *
     * <ul>
     *   <li>If the tournament does not exist in the active tenant's scope: {@code HTTP 404} (AC7,
     *       AC9).
     *   <li>If the tournament exists but has no phases (draft, not yet applied): renders {@code
     *       print/error.mustache} with a human-readable message (AC7).
     *   <li>Otherwise: renders {@code print/index.mustache} showing navigation links to E08S08/S09
     *       templates (AC1).
     * </ul>
     *
     * @param tournamentId the UUID of the tournament to print
     * @param model Spring MVC model populated with i18n strings and tournament data
     * @return Mustache view name
     */
    @GetMapping("/{tournamentId}")
    public String printIndex(@PathVariable("tournamentId") UUID tournamentId, Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        // AC9: tenant-scoped repository query — cross-tenant access returns empty Optional
        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(() -> new TournamentNotFoundException(tournamentId));

        // AC7: draft tournament (no phases applied yet) → human-readable error page
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        if (phases.isEmpty()) {
            populateErrorModel(model, tournament, locale);
            return "print/error";
        }

        // AC1, AC5, AC8: populate model for print index page and shared partials
        populateCommonModel(model, tournament, locale);
        model.addAttribute("title", msg("print.index.title", "Tournament Schedule", locale));
        model.addAttribute("heading", msg("print.index.heading", "Print Documents", locale));

        // Links to E08S08 / E08S09 templates
        model.addAttribute("tournamentId", tournamentId.toString());
        model.addAttribute("laufzettelUrl", "/print/" + tournamentId + "/team-schedules");
        model.addAttribute("fotosUrl", "/print/" + tournamentId + "/fotos");
        model.addAttribute(
                "msgLaufzettelLink",
                msg("print.index.laufzettel.link", "Team Schedule (Laufzettel)", locale));
        model.addAttribute("msgFotosLink", msg("print.index.fotos.link", "Photo Schedule", locale));

        return "print/index";
    }

    // =========================================================================
    // E08S08: Laufzettel routes
    // =========================================================================

    /**
     * Renders the Laufzettel (team schedule) for a single team.
     *
     * <p>Mapped to {@code GET /print/{tournamentId}/team-schedules/{teamId}} (AC1 — E08S08).
     *
     * <ul>
     *   <li>Tournament not found → 404 (AC13, AC15 — tenant-scoped per E08S07 AC9).
     *   <li>No phases → human-readable error page (AC13).
     *   <li>No matches in any phase → {@code print/laufzettel-no-matches.mustache} (AC13).
     *   <li>Team not found in this tournament → 404 (AC13).
     *   <li>Otherwise: renders {@code print/laufzettel.mustache} for the single team (AC1).
     * </ul>
     *
     * @param tournamentId the tournament UUID (tenant-scoped)
     * @param teamId the team UUID
     * @param model Spring MVC model
     * @return Mustache view name
     */
    @GetMapping("/{tournamentId}/team-schedules/{teamId}")
    public String singleTeamSchedule(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("teamId") UUID teamId,
            Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(() -> new TournamentNotFoundException(tournamentId));

        // AC13: unknown team → 404 (must be checked before phases/matches to return 404 not error
        // page)
        List<Team> teams = teamRepository.findByTournamentId(tournamentId);
        teams.sort(java.util.Comparator.comparingInt(Team::getTeamNumber));

        Team requestedTeam =
                teams.stream()
                        .filter(t -> teamId.equals(t.getId()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new TournamentNotFoundException(
                                                teamId)); // 404 for unknown team

        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        if (phases.isEmpty()) {
            populateErrorModel(model, tournament, locale);
            return "print/error";
        }

        // Check for matches — AC13
        if (!hasAnyMatches(phases)) {
            populateNoMatchesErrorModel(model, tournament, locale);
            return "print/laufzettel-no-matches";
        }

        // Load supporting data
        Map<UUID, List<TeamAvatar>> avatarsByPhase = loadAvatarsByPhase(phases);
        Map<UUID, List<Match>> matchesByPhase = loadMatchesByPhase(phases);
        Map<UUID, List<PhaseBreak>> breaksByPhase = loadBreaksByPhase(phases);
        List<ActivityType> activityTypes = activityTypeRepository.findByTournamentId(tournamentId);

        // Assemble — only for the requested team
        List<Team> singleTeam = Collections.singletonList(requestedTeam);
        Map<UUID, List<LaufzettelRow>> rowsByTeam =
                laufzettelAssembler.assemble(
                        tournament,
                        phases,
                        singleTeam,
                        avatarsByPhase,
                        matchesByPhase,
                        breaksByPhase,
                        activityTypes,
                        0);

        List<LaufzettelRow> rows =
                rowsByTeam.getOrDefault(requestedTeam.getId(), Collections.emptyList());

        // Populate model
        populateCommonModel(model, tournament, locale);
        model.addAttribute(
                "title",
                msg("print.laufzettel.title.single", "VVWT Turniermanager — Laufzettel", locale));
        model.addAttribute(
                "heading",
                msg("print.laufzettel.heading.team", "Laufzettel Team", locale)
                        + " "
                        + requestedTeam.getTeamNumber());
        model.addAttribute(
                "teamName",
                requestedTeam.getDescription() != null ? requestedTeam.getDescription() : "");
        model.addAttribute("teamNumber", requestedTeam.getTeamNumber());
        model.addAttribute("hasTime", laufzettelAssembler.hasTime(tournament));
        model.addAttribute("rows", toMustacheMaps(rows));
        populateLaufzettelI18n(model, locale);

        return "print/laufzettel";
    }

    /**
     * Renders Laufzettel for ALL teams in team-number order with page breaks between them.
     *
     * <p>Mapped to {@code GET /print/{tournamentId}/team-schedules} (AC2 — E08S08).
     *
     * <p>The organizer prints this single page to get all schedules at once (AC2). CSS page breaks
     * ({@code .page-break}) are inserted between teams (AC12).
     *
     * @param tournamentId the tournament UUID (tenant-scoped)
     * @param model Spring MVC model
     * @return Mustache view name
     */
    @GetMapping("/{tournamentId}/team-schedules")
    public String allTeamSchedules(@PathVariable("tournamentId") UUID tournamentId, Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(() -> new TournamentNotFoundException(tournamentId));

        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        if (phases.isEmpty()) {
            populateErrorModel(model, tournament, locale);
            return "print/error";
        }

        if (!hasAnyMatches(phases)) {
            populateNoMatchesErrorModel(model, tournament, locale);
            return "print/laufzettel-no-matches";
        }

        List<Team> teams = teamRepository.findByTournamentId(tournamentId);
        teams.sort(java.util.Comparator.comparingInt(Team::getTeamNumber));

        Map<UUID, List<TeamAvatar>> avatarsByPhase = loadAvatarsByPhase(phases);
        Map<UUID, List<Match>> matchesByPhase = loadMatchesByPhase(phases);
        Map<UUID, List<PhaseBreak>> breaksByPhase = loadBreaksByPhase(phases);
        List<ActivityType> activityTypes = activityTypeRepository.findByTournamentId(tournamentId);

        Map<UUID, List<LaufzettelRow>> rowsByTeam =
                laufzettelAssembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        breaksByPhase,
                        activityTypes,
                        0);

        boolean hasTime = laufzettelAssembler.hasTime(tournament);
        String headingTeam = msg("print.laufzettel.heading.team", "Laufzettel Team", locale);

        // Build list of per-team schedule maps for Mustache iteration
        List<Map<String, Object>> schedules = new ArrayList<>();
        for (int i = 0; i < teams.size(); i++) {
            Team team = teams.get(i);
            Map<String, Object> schedule = new LinkedHashMap<>();
            schedule.put("teamNumber", team.getTeamNumber());
            schedule.put("teamName", team.getDescription() != null ? team.getDescription() : "");
            schedule.put("heading", headingTeam + " " + team.getTeamNumber());
            schedule.put("hasTime", hasTime);
            schedule.put(
                    "rows",
                    toMustacheMaps(rowsByTeam.getOrDefault(team.getId(), Collections.emptyList())));
            // AC12: page break before every team except the first
            schedule.put("showPageBreak", i > 0);
            schedules.add(schedule);
        }

        populateCommonModel(model, tournament, locale);
        model.addAttribute(
                "title",
                msg("print.laufzettel.title.all", "VVWT Turniermanager — Alle Laufzettel", locale));
        model.addAttribute("heading", msg("print.laufzettel.title.all", "Alle Laufzettel", locale));
        model.addAttribute("schedules", schedules);
        populateLaufzettelI18n(model, locale);

        return "print/laufzettel-all";
    }

    // =========================================================================
    // E08S09: Mannschaftsfoto-Übersicht — activity schedule route
    // =========================================================================

    /**
     * Renders the Mannschaftsfoto-Übersicht (activity schedule) for a single activity type.
     *
     * <p>Mapped to {@code GET /print/{tournamentId}/activity-schedule/{activityTypeId}} (AC1 —
     * E08S09).
     *
     * <ul>
     *   <li>Tournament not found → 404 (AC9, AC11).
     *   <li>Activity type not found (or not belonging to this tournament/tenant) → 404 (AC9).
     *   <li>No phases (draft tournament) → human-readable error page (same as E08S07 AC7).
     *   <li>No matches in any phase → human-readable error page (AC9).
     *   <li>Otherwise: renders {@code print/activity-schedule.mustache} (AC1).
     * </ul>
     *
     * <p>The route is generalized — it accepts any {@code activityTypeId}, not only
     * "Mannschaftsfoto". The activity type name (e.g., "Mannschaftsfoto") is resolved from the
     * entity and rendered in the header (AC2).
     *
     * @param tournamentId the tournament UUID (tenant-scoped)
     * @param activityTypeId the UUID of the activity type to render
     * @param model Spring MVC model
     * @return Mustache view name
     */
    @GetMapping("/{tournamentId}/activity-schedule/{activityTypeId}")
    public String activitySchedule(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("activityTypeId") UUID activityTypeId,
            Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        // AC11: tenant-scoped repository — cross-tenant access returns empty Optional → 404
        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(() -> new TournamentNotFoundException(tournamentId));

        // AC9: non-existent activity type → 404
        // findByTournamentId returns all activity types for this tournament in tenant scope.
        // We filter to the requested ID — if not found, it is either non-existent or belongs
        // to a different tournament/tenant.
        List<ActivityType> activityTypes = activityTypeRepository.findByTournamentId(tournamentId);
        ActivityType targetType =
                activityTypes.stream()
                        .filter(at -> activityTypeId.equals(at.getId()))
                        .findFirst()
                        .orElseThrow(() -> new TournamentNotFoundException(activityTypeId)); // 404

        // Draft tournament (no phases) → error page
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        if (phases.isEmpty()) {
            populateErrorModel(model, tournament, locale);
            return "print/error";
        }

        // No matches → error page (AC9: cannot assign without match schedule)
        if (!hasAnyMatches(phases)) {
            populateActivityScheduleNoMatchesErrorModel(model, tournament, locale);
            return "print/error";
        }

        // Load supporting data
        List<Team> teams = teamRepository.findByTournamentId(tournamentId);
        Map<UUID, List<TeamAvatar>> avatarsByPhase = loadAvatarsByPhase(phases);
        Map<UUID, List<Match>> matchesByPhase = loadMatchesByPhase(phases);
        Map<UUID, List<PhaseBreak>> breaksByPhase = loadBreaksByPhase(phases);

        // Assemble activity schedule
        ActivityScheduleModel scheduleModel =
                activityScheduleAssembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        breaksByPhase,
                        activityTypes,
                        targetType);

        // Build row maps for jmustache (List<Map<String, Object>>)
        List<Map<String, Object>> rowMaps = toActivityScheduleRowMaps(scheduleModel.rows());

        // Unassigned team names for warning section (AC6)
        List<Map<String, Object>> unassignedMaps = new ArrayList<>();
        for (String name : scheduleModel.unassignedTeamNames()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("teamName", name);
            unassignedMaps.add(m);
        }

        // Populate common model (shared print header partial — AC2, AC10)
        populateCommonModel(model, tournament, locale);

        // AC2: activity type name in header
        String activityName = targetType.getName();
        String pageTitle =
                msg("print.activitySchedule.title", "VVWT Turniermanager \u2014 Zeitplan", locale)
                        + " \u2014 "
                        + activityName;
        String pageHeading =
                activityName
                        + " \u2014 "
                        + msg("print.activitySchedule.headingSuffix", "Zeitplan", locale);

        model.addAttribute("title", pageTitle);
        model.addAttribute("heading", pageHeading);
        model.addAttribute("activityName", activityName);

        // AC7: time column guard
        model.addAttribute("hasTime", scheduleModel.hasTime());

        // AC3: schedule rows
        model.addAttribute("rows", rowMaps);

        // AC4: break rows are interleaved inside rowMaps (isBreak flag)

        // AC5: summary line
        model.addAttribute("totalTeams", scheduleModel.totalAssignedTeams());
        model.addAttribute("totalRounds", scheduleModel.roundCount());

        // AC6: unassigned warning
        model.addAttribute("hasUnassigned", scheduleModel.hasUnassigned());
        model.addAttribute("unassignedTeams", unassignedMaps);

        // AC10: i18n keys for column headers and labels
        populateActivityScheduleI18n(model, locale);

        return "print/activity-schedule";
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Returns true if any phase in the list has at least one match with an assigned lap number. */
    private boolean hasAnyMatches(List<Phase> phases) {
        for (Phase phase : phases) {
            List<Match> matches = matchRepository.findByPhaseId(phase.getId());
            for (Match m : matches) {
                if (m.getLapNumber() != null) return true;
            }
        }
        return false;
    }

    /** Loads TeamAvatars grouped by phaseId. */
    private Map<UUID, List<TeamAvatar>> loadAvatarsByPhase(List<Phase> phases) {
        Map<UUID, List<TeamAvatar>> map = new HashMap<>();
        for (Phase phase : phases) {
            map.put(phase.getId(), teamAvatarRepository.findByPhaseId(phase.getId()));
        }
        return map;
    }

    /** Loads Matches grouped by phaseId. */
    private Map<UUID, List<Match>> loadMatchesByPhase(List<Phase> phases) {
        Map<UUID, List<Match>> map = new HashMap<>();
        for (Phase phase : phases) {
            map.put(phase.getId(), matchRepository.findByPhaseId(phase.getId()));
        }
        return map;
    }

    /** Loads PhaseBreaks grouped by phaseId. */
    private Map<UUID, List<PhaseBreak>> loadBreaksByPhase(List<Phase> phases) {
        Map<UUID, List<PhaseBreak>> map = new HashMap<>();
        for (Phase phase : phases) {
            map.put(phase.getId(), phaseBreakRepository.findByPhaseId(phase.getId()));
        }
        return map;
    }

    /**
     * Converts a list of {@link LaufzettelRow} to jmustache-compatible list of attribute maps.
     *
     * <p>jmustache renders {@code {{#rows}}} where rows is a {@code List<Map<String, Object>>}.
     * Each map entry is directly accessible as {@code {{fieldName}}} inside the section. Boolean
     * fields use Java boolean so jmustache treats them as truthy/falsy for section guards.
     *
     * @param rows list of LaufzettelRow objects
     * @return list of attribute maps ready for jmustache
     */
    private List<Map<String, Object>> toMustacheMaps(List<LaufzettelRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (LaufzettelRow row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("isPhaseHeader", row.isPhaseHeader());
            map.put("phaseHeaderName", row.getPhaseHeaderName());
            map.put("isBreak", row.isBreak());
            map.put("breakLabel", row.getBreakLabel());
            map.put("breakTimeWindow", row.getBreakTimeWindow());
            map.put("roundNumber", row.getRoundNumber());
            map.put("timeWindow", row.getTimeWindow());
            map.put("isPlaying", row.isPlaying());
            map.put("opponentName", row.getOpponentName());
            map.put("fieldNumber", row.getFieldNumber());
            map.put("isRefereeing", row.isRefereeing());
            map.put("isActivity", row.isActivity());
            map.put("activityName", row.getActivityName());
            map.put("isFree", row.isFree());
            result.add(map);
        }
        return result;
    }

    /**
     * Populates i18n model attributes consumed by the Laufzettel templates (AC14 — E08S08).
     *
     * <p>All static text (column headers, labels) is sourced from the message bundle. German is the
     * default locale.
     */
    private void populateLaufzettelI18n(Model model, Locale locale) {
        model.addAttribute("msgColRound", msg("print.laufzettel.col.round", "Runde", locale));
        model.addAttribute("msgColTime", msg("print.laufzettel.col.time", "Zeit", locale));
        model.addAttribute(
                "msgColActivity", msg("print.laufzettel.col.activity", "Aktivit\u00e4t", locale));
        model.addAttribute("msgColField", msg("print.laufzettel.col.field", "Feld", locale));
        model.addAttribute(
                "msgReferee", msg("print.laufzettel.label.referee", "Schiedsrichter", locale));
        model.addAttribute("msgFree", msg("print.laufzettel.label.free", "Frei", locale));
        model.addAttribute("msgBreak", msg("print.laufzettel.label.break", "Pause", locale));
    }

    /** Populates model for the no-matches error page (AC13 — E08S08). */
    private void populateNoMatchesErrorModel(Model model, Tournament tournament, Locale locale) {
        populateCommonModel(model, tournament, locale);
        model.addAttribute(
                "title",
                msg(
                        "print.laufzettel.error.nomatches.title",
                        "VVWT Turniermanager \u2014 Laufzettel",
                        locale));
        model.addAttribute(
                "heading",
                msg(
                        "print.laufzettel.error.nomatches.heading",
                        "Spielplan nicht verf\u00fcgbar",
                        locale));
        model.addAttribute(
                "msgNoMatches",
                msg(
                        "print.laufzettel.error.nomatches.message",
                        "Die Spielpaarungen wurden noch nicht erstellt. Bitte erstellen Sie den"
                                + " Spielplan, bevor Sie den Laufzettel drucken.",
                        locale));
    }

    /**
     * Populates model attributes consumed by the {@code {{> print-header}}} partial (AC5, AC8 —
     * E08S07).
     */
    private void populateCommonModel(Model model, Tournament tournament, Locale locale) {
        model.addAttribute(
                "tournamentName",
                tournament.getDescription() != null
                        ? tournament.getDescription()
                        : msg("print.header.unnamedTournament", "Unnamed Tournament", locale));

        String dateStr =
                tournament.getAppointment() != null
                        ? tournament.getAppointment().toLocalDate().format(DATE_FORMATTER)
                        : "";
        model.addAttribute("tournamentDate", dateStr);

        model.addAttribute(
                "msgTournamentLabel", msg("print.header.tournamentLabel", "Tournament", locale));
        model.addAttribute("msgDateLabel", msg("print.header.dateLabel", "Date", locale));
        model.addAttribute("phaseName", "");
        model.addAttribute("msgPhaseLabel", msg("print.header.phaseLabel", "Phase", locale));
        model.addAttribute("cssPath", "/print/assets/print.css");
        model.addAttribute("appVersion", appVersion);
        model.addAttribute("locale", locale.toLanguageTag());
    }

    /** Populates model for the no-phases error page (AC7 — E08S07). */
    private void populateErrorModel(Model model, Tournament tournament, Locale locale) {
        populateCommonModel(model, tournament, locale);
        model.addAttribute("title", msg("print.error.title", "Print — Not Available", locale));
        model.addAttribute("heading", msg("print.error.heading", "Cannot Print", locale));
        model.addAttribute(
                "msgNophase",
                msg(
                        "print.error.nophase",
                        "The tournament schedule has not been generated yet. "
                                + "Please apply the draft in the admin panel before printing.",
                        locale));
    }

    /**
     * Converts a list of {@link ActivityScheduleRow} to jmustache-compatible list of attribute
     * maps.
     *
     * <p>Each map has the same keys regardless of row type (isDataRow / isBreak), so that jmustache
     * strict mode does not throw {@code MustacheException} on absent keys.
     *
     * @param rows list of ActivityScheduleRow objects
     * @return list of attribute maps ready for jmustache
     */
    private List<Map<String, Object>> toActivityScheduleRowMaps(List<ActivityScheduleRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (ActivityScheduleRow row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("isDataRow", row.isDataRow());
            map.put("roundNumber", row.getRoundNumber());
            map.put("timeWindow", row.getTimeWindow());
            map.put("teamNames", row.getTeamNames());
            map.put("isBreak", row.isBreak());
            map.put("breakLabel", row.getBreakLabel());
            map.put("breakTimeWindow", row.getBreakTimeWindow());
            result.add(map);
        }
        return result;
    }

    /**
     * Populates i18n model attributes consumed by the activity-schedule template (AC10 — E08S09).
     */
    private void populateActivityScheduleI18n(Model model, Locale locale) {
        model.addAttribute("msgColRound", msg("print.activitySchedule.col.round", "Runde", locale));
        model.addAttribute("msgColTime", msg("print.activitySchedule.col.time", "Zeit", locale));
        model.addAttribute(
                "msgColTeams", msg("print.activitySchedule.col.teams", "Mannschaften", locale));
        model.addAttribute(
                "msgSummary",
                msg(
                        "print.activitySchedule.summary",
                        "{totalTeams} Mannschaften, verteilt auf {totalRounds} Runden.",
                        locale));
        model.addAttribute(
                "msgUnassignedTitle",
                msg(
                        "print.activitySchedule.unassigned.title",
                        "Keine freie Runde verf\u00fcgbar",
                        locale));
        model.addAttribute("msgBreakLabel", msg("print.activitySchedule.break", "Pause", locale));
    }

    /**
     * Populates model for the no-matches error page in the activity schedule context (AC9 —
     * E08S09).
     */
    private void populateActivityScheduleNoMatchesErrorModel(
            Model model, Tournament tournament, Locale locale) {
        populateCommonModel(model, tournament, locale);
        model.addAttribute("title", msg("print.error.title", "Print \u2014 Not Available", locale));
        model.addAttribute("heading", msg("print.error.heading", "Cannot Print", locale));
        model.addAttribute(
                "msgNophase",
                msg(
                        "print.activitySchedule.error.nomatches",
                        "Der Spielplan wurde noch nicht erstellt. Bitte erstellen Sie die"
                                + " Spielpaarungen, bevor Sie den Aktivit\u00e4tsplan drucken.",
                        locale));
    }

    /** Resolves a message from {@link MessageSource} with a safe fallback. */
    private String msg(String key, String fallback, Locale locale) {
        try {
            return messageSource.getMessage(key, null, fallback, locale);
        } catch (Exception e) {
            return fallback;
        }
    }

    // =========================================================================
    // E12S06: Certificate routes
    // =========================================================================

    /**
     * Renders a single certificate for a team (AC1 + AC2).
     *
     * <p>The response format is determined by the uploaded template:
     *
     * <ul>
     *   <li>SVG template (AC1): returns the rendered SVG file with {@code Content-Type:
     *       image/svg+xml} and {@code Content-Disposition: attachment}.
     *   <li>HTML template (AC2): returns a rendered HTML page using the Mustache view resolver.
     * </ul>
     *
     * <p>Error responses (AC7–AC10):
     *
     * <ul>
     *   <li>No template uploaded → 400 with German message.
     *   <li>No standings (no ratings) → 400 with German message.
     *   <li>Unknown tournament or team → 404 via {@link TournamentNotFoundException}.
     *   <li>Mustache rendering failure → 500 with error detail (AC9).
     * </ul>
     *
     * @param tournamentId the tournament UUID (tenant-scoped)
     * @param teamId the team UUID
     * @param model Spring MVC model (used for HTML path)
     * @return view name (HTML path) or handled via ResponseEntity (SVG path)
     */
    @GetMapping("/tournaments/{tournamentId}/certificates/{teamId}")
    public Object singleCertificate(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("teamId") UUID teamId,
            Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        // AC11: tenant-scoped — returns 404 for wrong-tenant or missing tournament
        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(() -> new TournamentNotFoundException(tournamentId));

        // AC7: no template uploaded
        Optional<CertificateTemplateService.TemplateFile> templateOpt =
                certificateTemplateService.retrieveFile(tournamentId);
        if (templateOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            msg(
                                    "print.certificate.error.noTemplate",
                                    "Keine Urkunden-Vorlage hochgeladen. Bitte laden Sie zuerst"
                                            + " eine Vorlage hoch.",
                                    locale));
        }

        // AC8: no standings (check final phase exists and has ratings)
        Optional<Phase> finalPhaseOpt = certificateAssembler.getFinalPhase(tournamentId);
        if (finalPhaseOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            msg(
                                    "print.certificate.error.noStandings",
                                    "Es sind keine Spielergebnisse vorhanden. Bitte spielen Sie"
                                            + " zuerst die Spiele.",
                                    locale));
        }

        List<CertificateAssembler.AvatarPlacement> placements =
                certificateAssembler.computePlacementOrder(tournamentId, finalPhaseOpt.get());
        if (placements.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            msg(
                                    "print.certificate.error.noStandings",
                                    "Es sind keine Spielergebnisse vorhanden. Bitte spielen Sie"
                                            + " zuerst die Spiele.",
                                    locale));
        }

        // AC9: unknown team → 404
        CertificateAssembler.AvatarPlacement teamPlacement =
                placements.stream()
                        .filter(ap -> teamId.equals(ap.teamId()))
                        .findFirst()
                        .orElseThrow(() -> new TournamentNotFoundException(teamId));

        String locationDisplayName = this.resolveLocationDisplayName(tenantContext.current());

        CertificateTemplateService.TemplateFile templateFile = templateOpt.get();
        String format = templateFile.metadata().format(); // "svg" or "html"

        if ("svg".equals(format)) {
            // AC1: SVG path — render and return as binary attachment
            String templateContent = readTemplateContent(templateFile);
            List<CertificatePlacementRow> svgRows =
                    certificateAssembler.buildSvgRows(
                            tournament, List.of(teamPlacement), locationDisplayName);

            try {
                String renderedSvg =
                        certificateAssembler.renderSvgTemplate(templateContent, svgRows.get(0));
                byte[] svgBytes = renderedSvg.getBytes(StandardCharsets.UTF_8);

                // AC1: filename = "{placement}-{teamName}.svg"
                String safeTeamName = sanitizeFilename(svgRows.get(0).teamName());
                String filename = teamPlacement.placement() + "-" + safeTeamName + ".svg";

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType("image/svg+xml"));
                headers.set(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"");
                return ResponseEntity.ok().headers(headers).body(svgBytes);

            } catch (MustacheException ex) {
                // AC9: Mustache rendering error → 500 with detail for admin debugging
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Mustache rendering error: " + ex.getMessage());
            }

        } else {
            // AC2: HTML path — Mustache view rendering
            List<CertificatePlacementRow> htmlRows =
                    certificateAssembler.buildHtmlRows(
                            tournament, List.of(teamPlacement), locationDisplayName);
            CertificatePlacementRow row = htmlRows.get(0);

            populateCommonModel(model, tournament, locale);
            model.addAttribute(
                    "title",
                    msg(
                            "print.certificate.title.single",
                            "VVWT Turniermanager \u2014 Urkunde",
                            locale));
            model.addAttribute("certificates", List.of(certificateAssembler.toMustacheMap(row)));
            model.addAttribute("singleCertificate", certificateAssembler.toMustacheMap(row));
            return "print/certificate";
        }
    }

    /**
     * Renders all certificates for a tournament in batch (AC3 + AC4).
     *
     * <p>The response format is determined by the uploaded template:
     *
     * <ul>
     *   <li>SVG template (AC3): returns a ZIP archive with one SVG per team. Content-Type: {@code
     *       application/zip}.
     *   <li>HTML template (AC4): returns a single HTML page with all certificates sequentially,
     *       with CSS page breaks between teams.
     * </ul>
     *
     * @param tournamentId the tournament UUID (tenant-scoped)
     * @param model Spring MVC model (used for HTML path)
     * @return view name (HTML path) or {@link ResponseEntity} (SVG/ZIP path)
     */
    @GetMapping("/tournaments/{tournamentId}/certificates")
    public Object allCertificates(@PathVariable("tournamentId") UUID tournamentId, Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(() -> new TournamentNotFoundException(tournamentId));

        // AC7
        Optional<CertificateTemplateService.TemplateFile> templateOpt =
                certificateTemplateService.retrieveFile(tournamentId);
        if (templateOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            msg(
                                    "print.certificate.error.noTemplate",
                                    "Keine Urkunden-Vorlage hochgeladen. Bitte laden Sie zuerst"
                                            + " eine Vorlage hoch.",
                                    locale));
        }

        // AC8
        Optional<Phase> finalPhaseOpt = certificateAssembler.getFinalPhase(tournamentId);
        if (finalPhaseOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            msg(
                                    "print.certificate.error.noStandings",
                                    "Es sind keine Spielergebnisse vorhanden. Bitte spielen Sie"
                                            + " zuerst die Spiele.",
                                    locale));
        }

        List<CertificateAssembler.AvatarPlacement> placements =
                certificateAssembler.computePlacementOrder(tournamentId, finalPhaseOpt.get());
        if (placements.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(
                            msg(
                                    "print.certificate.error.noStandings",
                                    "Es sind keine Spielergebnisse vorhanden. Bitte spielen Sie"
                                            + " zuerst die Spiele.",
                                    locale));
        }

        String locationDisplayName = this.resolveLocationDisplayName(tenantContext.current());

        CertificateTemplateService.TemplateFile templateFile = templateOpt.get();
        String format = templateFile.metadata().format();

        if ("svg".equals(format)) {
            // AC3: ZIP of SVG files
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
            // AC4: HTML all-certificates page with page breaks
            List<CertificatePlacementRow> htmlRows =
                    certificateAssembler.buildHtmlRows(tournament, placements, locationDisplayName);

            List<Map<String, Object>> certificateMaps = new ArrayList<>();
            for (int i = 0; i < htmlRows.size(); i++) {
                Map<String, Object> certMap =
                        new LinkedHashMap<>(certificateAssembler.toMustacheMap(htmlRows.get(i)));
                // AC4: CSS page break before every certificate except the first
                certMap.put("showPageBreak", i > 0);
                certificateMaps.add(certMap);
            }

            populateCommonModel(model, tournament, locale);
            model.addAttribute(
                    "title",
                    msg(
                            "print.certificate.title.all",
                            "VVWT Turniermanager \u2014 Alle Urkunden",
                            locale));
            model.addAttribute("certificates", certificateMaps);
            return "print/certificate-all";
        }
    }

    /**
     * Reads the full template content from a {@link CertificateTemplateService.TemplateFile}.
     *
     * <p>Closes the InputStream after reading.
     *
     * @param templateFile the template file result from {@link CertificateTemplateService}
     * @return the template content as a UTF-8 string
     * @throws de.vvwt.tm.certificate.CertificateTemplateStorageException on I/O failure (re-thrown
     *     as-is)
     */
    private String readTemplateContent(CertificateTemplateService.TemplateFile templateFile) {
        try (InputStream is = templateFile.inputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new de.vvwt.tm.certificate.CertificateTemplateStorageException(
                    "Failed to read certificate template: " + ex.getMessage(), ex);
        }
    }

    /**
     * Builds a ZIP archive containing one rendered SVG per team (AC3).
     *
     * <p>Files inside the ZIP are named {@code {placement}-{teamName}.svg} with placement
     * zero-padded to 2 digits for sort order consistency (e.g., {@code 01-Musterteam.svg}).
     *
     * @param svgRows ordered list of placement rows (placement-ordered)
     * @param templateContent the raw SVG Mustache template
     * @return the ZIP archive as a byte array
     * @throws IOException on ZIP streaming failure
     * @throws MustacheException on Mustache rendering failure
     */
    private byte[] buildSvgZip(List<CertificatePlacementRow> svgRows, String templateContent)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (CertificatePlacementRow row : svgRows) {
                String renderedSvg = certificateAssembler.renderSvgTemplate(templateContent, row);
                byte[] svgBytes = renderedSvg.getBytes(StandardCharsets.UTF_8);

                // AC3: filenames zero-padded to 2 digits for ≤99 teams
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

    /**
     * Sanitizes a string for use as a filename component — replaces characters that are problematic
     * on common filesystems (Windows, macOS, Linux) with underscores.
     *
     * <p>Characters replaced: {@code / \ : * ? " < > | space tab}. Leading/trailing dots and spaces
     * are trimmed. An empty result is replaced with "team".
     *
     * @param name the raw team name or tournament name
     * @return a filesystem-safe filename fragment
     */
    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "team";
        }
        // Replace filesystem-unsafe characters with underscores
        String safe = name.replaceAll("[/\\\\:*?\"<>| \t]", "_").replaceAll("^[. ]+|[. ]+$", "");
        return safe.isBlank() ? "team" : safe;
    }

    // =========================================================================
    // Q-1a: resolveLocationDisplayName (migrated from CertificateAssembler, E23S10)
    // =========================================================================

    /**
     * Resolves the display name of the location associated with the given tenant (Q-1a, E23S10).
     *
     * <p>Migrated byte-equivalent from {@code
     * de.vvwt.tm.infrastructure.print.CertificateAssembler#resolveLocationDisplayName(UUID)} per
     * Brief D-13 C2=β decision. The {@code certificate} module's public interface ({@link
     * de.vvwt.tm.certificate.CertificateAssembler}) no longer exposes this method after E23S10.
     *
     * <h2>DEC-39 — tenant_id predicate preserved pre-Big-Bang</h2>
     *
     * <p>The {@code tenant_id = ?} WHERE predicate is preserved verbatim. Per DEC-39, removal of
     * {@code tenant_id} from tenant-scoped tables (including {@code locations}) is deferred to the
     * Wave-2 Big-Bang-Reset (DEC-25). Post-Big-Bang, DB-per-Tenant DataSource routing (DEC-20)
     * alone will enforce tenant scoping and the predicate will be removed.
     *
     * <h2>DEC-20 — apparent redundancy acknowledged</h2>
     *
     * <p>DB-per-Tenant DataSource routing (DEC-20) already isolates the query to the correct tenant
     * DB. The {@code tenant_id = ?} predicate is therefore redundant at runtime but is preserved to
     * match the legacy {@code CertificateAssembler} behavior byte-equivalent.
     *
     * @param tenantId the active tenant UUID (from {@link
     *     de.vvwt.tm.tenant.TenantContext#current()})
     * @return the location display name, or an empty string if not found
     */
    String resolveLocationDisplayName(UUID tenantId) {
        List<String> names =
                jdbcTemplate.query(
                        "SELECT display_name FROM locations WHERE tenant_id = ? LIMIT 1",
                        (rs, rowNum) -> rs.getString(1),
                        tenantId);
        return names.isEmpty() ? "" : names.get(0);
    }
}
