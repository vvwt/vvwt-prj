package de.vvwt.tm.infrastructure.print;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.PhaseBreak;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.repo.ActivityTypeRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseBreakRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Spring MVC controller for print routes ({@code /print/**}).
 *
 * <h2>Story E08S07 — AC1, AC2, AC5, AC7, AC8, AC9</h2>
 * <ul>
 *   <li>AC1: All print routes are served under {@code /print/**} by this controller.</li>
 *   <li>AC2: {@code /print/**} is protected by basic auth in {@link de.vvwt.tm.auth.SecurityConfig}.
 *       Unauthenticated requests receive 401 — enforced by Spring Security before this controller
 *       is invoked.</li>
 *   <li>AC5: Mustache partials {@code print-header} and {@code print-page-break} are included
 *       in templates; the controller populates the model attributes they consume.</li>
 *   <li>AC7: Non-existent tournament → 404. Tournament with no phases (draft) → human-readable
 *       Mustache error page, not a raw JSON error.</li>
 *   <li>AC8: All user-visible strings sourced from {@link MessageSource} (German default per E05).</li>
 *   <li>AC9: Tournament resolved via tenant-scoped {@link TournamentRepository} — cross-tenant
 *       access is impossible because the repository guards tenant scope on every query.</li>
 * </ul>
 *
 * <h2>Story E08S08 — AC1, AC2, AC13, AC14, AC15</h2>
 * <ul>
 *   <li>AC1: {@code GET /print/{tournamentId}/team-schedules/{teamId}} renders a single team's
 *       Laufzettel.</li>
 *   <li>AC2: {@code GET /print/{tournamentId}/team-schedules} renders all teams' Laufzettel.</li>
 *   <li>AC13: No matches → error page; non-existent team → 404.</li>
 *   <li>AC14: All static text via {@link MessageSource} (German default).</li>
 *   <li>AC15: Print routes inherit E08S07's basic auth enforcement (same {@code /print/**} rule).</li>
 * </ul>
 *
 * <h2>Story E08S09 — AC1–AC11</h2>
 * <ul>
 *   <li>AC1: {@code GET /print/{tournamentId}/activity-schedule/{activityTypeId}} renders the
 *       Mannschaftsfoto-Übersicht for the specified activity type.</li>
 *   <li>AC2: Page header shows tournament name and activity type name.</li>
 *   <li>AC3: Schedule table ordered by round number; empty rounds omitted.</li>
 *   <li>AC4: Break separator rows inserted from timeline context.</li>
 *   <li>AC5: Summary line — N teams total, across M rounds.</li>
 *   <li>AC6: Unassigned warning section when some teams have no free round.</li>
 *   <li>AC7: Time column omitted when tournament has no plannedStartTime.</li>
 *   <li>AC8: CSS print styling — compact A4 layout.</li>
 *   <li>AC9: Non-existent activityTypeId → 404.</li>
 *   <li>AC10: All static text from MessageSource (German default).</li>
 *   <li>AC11: Route inherits E08S07's basic auth enforcement.</li>
 * </ul>
 *
 * <h2>DEC-12, DEC-15</h2>
 * <p>Templates resolved from {@code classpath:/templates/print/} by the auto-configured Mustache
 * {@code ViewResolver}. Shared partials ({@code print-header}, {@code print-page-break}) live at
 * {@code classpath:/templates/} root so jmustache can resolve them via {@code {{> print-header}}}.
 * Static print CSS is served from {@code classpath:/static/print/assets/print.css} (bundled in the
 * jlink archive automatically per DEC-15).</p>
 *
 * @see de.vvwt.tm.auth.SecurityConfig — configures /print/** as authenticated, /print/assets/** as permitAll
 * @see LaufzettelAssembler
 * @see ActivityScheduleAssembler
 */
@Controller
@RequestMapping("/print")
public class PrintController {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamRepository teamRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final PhaseBreakRepository phaseBreakRepository;
    private final ActivityTypeRepository activityTypeRepository;
    private final LaufzettelAssembler laufzettelAssembler;
    private final ActivityScheduleAssembler activityScheduleAssembler;
    private final MessageSource messageSource;
    private final String appVersion;

    /**
     * Constructor injection.
     *
     * <p>{@link BuildProperties} is optional — absent in test environments and plain IDE runs
     * where the app is not repackaged. Falls back to {@code "dev"}.
     */
    @Autowired
    public PrintController(TournamentRepository tournamentRepository,
                           PhaseRepository phaseRepository,
                           MatchRepository matchRepository,
                           TeamRepository teamRepository,
                           TeamAvatarRepository teamAvatarRepository,
                           PhaseBreakRepository phaseBreakRepository,
                           ActivityTypeRepository activityTypeRepository,
                           LaufzettelAssembler laufzettelAssembler,
                           ActivityScheduleAssembler activityScheduleAssembler,
                           MessageSource messageSource,
                           @Autowired(required = false) BuildProperties buildProperties) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamRepository = teamRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.phaseBreakRepository = phaseBreakRepository;
        this.activityTypeRepository = activityTypeRepository;
        this.laufzettelAssembler = laufzettelAssembler;
        this.activityScheduleAssembler = activityScheduleAssembler;
        this.messageSource = messageSource;
        this.appVersion = buildProperties != null ? buildProperties.getVersion() : "dev";
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
     *   <li>If the tournament does not exist in the active tenant's scope: {@code HTTP 404} (AC7, AC9).</li>
     *   <li>If the tournament exists but has no phases (draft, not yet applied): renders
     *       {@code print/error.mustache} with a human-readable message (AC7).</li>
     *   <li>Otherwise: renders {@code print/index.mustache} showing navigation links to E08S08/S09
     *       templates (AC1).</li>
     * </ul>
     *
     * @param tournamentId the UUID of the tournament to print
     * @param model        Spring MVC model populated with i18n strings and tournament data
     * @return Mustache view name
     */
    @GetMapping("/{tournamentId}")
    public String printIndex(@PathVariable("tournamentId") UUID tournamentId, Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        // AC9: tenant-scoped repository query — cross-tenant access returns empty Optional
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new TournamentNotFoundException(tournamentId));

        // AC7: draft tournament (no phases applied yet) → human-readable error page
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        if (phases.isEmpty()) {
            populateErrorModel(model, tournament, locale);
            return "print/error";
        }

        // AC1, AC5, AC8: populate model for print index page and shared partials
        populateCommonModel(model, tournament, locale);
        model.addAttribute("title",   msg("print.index.title",   "Tournament Schedule", locale));
        model.addAttribute("heading", msg("print.index.heading", "Print Documents",     locale));

        // Links to E08S08 / E08S09 templates
        model.addAttribute("tournamentId",      tournamentId.toString());
        model.addAttribute("laufzettelUrl",     "/print/" + tournamentId + "/team-schedules");
        model.addAttribute("fotosUrl",          "/print/" + tournamentId + "/fotos");
        model.addAttribute("msgLaufzettelLink", msg("print.index.laufzettel.link", "Team Schedule (Laufzettel)", locale));
        model.addAttribute("msgFotosLink",      msg("print.index.fotos.link",      "Photo Schedule",             locale));

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
     *   <li>Tournament not found → 404 (AC13, AC15 — tenant-scoped per E08S07 AC9).</li>
     *   <li>No phases → human-readable error page (AC13).</li>
     *   <li>No matches in any phase → {@code print/laufzettel-no-matches.mustache} (AC13).</li>
     *   <li>Team not found in this tournament → 404 (AC13).</li>
     *   <li>Otherwise: renders {@code print/laufzettel.mustache} for the single team (AC1).</li>
     * </ul>
     *
     * @param tournamentId the tournament UUID (tenant-scoped)
     * @param teamId       the team UUID
     * @param model        Spring MVC model
     * @return Mustache view name
     */
    @GetMapping("/{tournamentId}/team-schedules/{teamId}")
    public String singleTeamSchedule(@PathVariable("tournamentId") UUID tournamentId,
                                      @PathVariable("teamId") UUID teamId,
                                      Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new TournamentNotFoundException(tournamentId));

        // AC13: unknown team → 404 (must be checked before phases/matches to return 404 not error page)
        List<Team> teams = teamRepository.findByTournamentId(tournamentId);
        teams.sort(java.util.Comparator.comparingInt(Team::getTeamNumber));

        Team requestedTeam = teams.stream()
                .filter(t -> teamId.equals(t.getId()))
                .findFirst()
                .orElseThrow(() -> new TournamentNotFoundException(teamId));  // 404 for unknown team

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
        Map<UUID, List<LaufzettelRow>> rowsByTeam = laufzettelAssembler.assemble(
                tournament, phases, singleTeam, avatarsByPhase, matchesByPhase,
                breaksByPhase, activityTypes, 0);

        List<LaufzettelRow> rows = rowsByTeam.getOrDefault(requestedTeam.getId(), Collections.emptyList());

        // Populate model
        populateCommonModel(model, tournament, locale);
        model.addAttribute("title",   msg("print.laufzettel.title.single", "VVWT Turniermanager — Laufzettel", locale));
        model.addAttribute("heading", msg("print.laufzettel.heading.team", "Laufzettel Team", locale)
                + " " + requestedTeam.getTeamNumber());
        model.addAttribute("teamName",   requestedTeam.getDescription() != null ? requestedTeam.getDescription() : "");
        model.addAttribute("teamNumber", requestedTeam.getTeamNumber());
        model.addAttribute("hasTime",    laufzettelAssembler.hasTime(tournament));
        model.addAttribute("rows",       toMustacheMaps(rows));
        populateLaufzettelI18n(model, locale);

        return "print/laufzettel";
    }

    /**
     * Renders Laufzettel for ALL teams in team-number order with page breaks between them.
     *
     * <p>Mapped to {@code GET /print/{tournamentId}/team-schedules} (AC2 — E08S08).
     *
     * <p>The organizer prints this single page to get all schedules at once (AC2).
     * CSS page breaks ({@code .page-break}) are inserted between teams (AC12).
     *
     * @param tournamentId the tournament UUID (tenant-scoped)
     * @param model        Spring MVC model
     * @return Mustache view name
     */
    @GetMapping("/{tournamentId}/team-schedules")
    public String allTeamSchedules(@PathVariable("tournamentId") UUID tournamentId, Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        Tournament tournament = tournamentRepository.findById(tournamentId)
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

        Map<UUID, List<LaufzettelRow>> rowsByTeam = laufzettelAssembler.assemble(
                tournament, phases, teams, avatarsByPhase, matchesByPhase,
                breaksByPhase, activityTypes, 0);

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
            schedule.put("rows", toMustacheMaps(rowsByTeam.getOrDefault(team.getId(), Collections.emptyList())));
            // AC12: page break before every team except the first
            schedule.put("showPageBreak", i > 0);
            schedules.add(schedule);
        }

        populateCommonModel(model, tournament, locale);
        model.addAttribute("title",     msg("print.laufzettel.title.all", "VVWT Turniermanager — Alle Laufzettel", locale));
        model.addAttribute("heading",   msg("print.laufzettel.title.all", "Alle Laufzettel", locale));
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
     * <p>Mapped to {@code GET /print/{tournamentId}/activity-schedule/{activityTypeId}} (AC1 — E08S09).
     *
     * <ul>
     *   <li>Tournament not found → 404 (AC9, AC11).</li>
     *   <li>Activity type not found (or not belonging to this tournament/tenant) → 404 (AC9).</li>
     *   <li>No phases (draft tournament) → human-readable error page (same as E08S07 AC7).</li>
     *   <li>No matches in any phase → human-readable error page (AC9).</li>
     *   <li>Otherwise: renders {@code print/activity-schedule.mustache} (AC1).</li>
     * </ul>
     *
     * <p>The route is generalized — it accepts any {@code activityTypeId}, not only "Mannschaftsfoto".
     * The activity type name (e.g., "Mannschaftsfoto") is resolved from the entity and rendered in
     * the header (AC2).
     *
     * @param tournamentId   the tournament UUID (tenant-scoped)
     * @param activityTypeId the UUID of the activity type to render
     * @param model          Spring MVC model
     * @return Mustache view name
     */
    @GetMapping("/{tournamentId}/activity-schedule/{activityTypeId}")
    public String activitySchedule(@PathVariable("tournamentId") UUID tournamentId,
                                    @PathVariable("activityTypeId") UUID activityTypeId,
                                    Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        // AC11: tenant-scoped repository — cross-tenant access returns empty Optional → 404
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new TournamentNotFoundException(tournamentId));

        // AC9: non-existent activity type → 404
        // findByTournamentId returns all activity types for this tournament in tenant scope.
        // We filter to the requested ID — if not found, it is either non-existent or belongs
        // to a different tournament/tenant.
        List<ActivityType> activityTypes = activityTypeRepository.findByTournamentId(tournamentId);
        ActivityType targetType = activityTypes.stream()
                .filter(at -> activityTypeId.equals(at.getId()))
                .findFirst()
                .orElseThrow(() -> new TournamentNotFoundException(activityTypeId));  // 404

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
        ActivityScheduleModel scheduleModel = activityScheduleAssembler.assemble(
                tournament, phases, teams, avatarsByPhase, matchesByPhase,
                breaksByPhase, activityTypes, targetType);

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
        String pageTitle    = msg("print.activitySchedule.title", "VVWT Turniermanager \u2014 Zeitplan", locale)
                + " \u2014 " + activityName;
        String pageHeading  = activityName + " \u2014 "
                + msg("print.activitySchedule.headingSuffix", "Zeitplan", locale);

        model.addAttribute("title",       pageTitle);
        model.addAttribute("heading",     pageHeading);
        model.addAttribute("activityName", activityName);

        // AC7: time column guard
        model.addAttribute("hasTime", scheduleModel.hasTime());

        // AC3: schedule rows
        model.addAttribute("rows", rowMaps);

        // AC4: break rows are interleaved inside rowMaps (isBreak flag)

        // AC5: summary line
        model.addAttribute("totalTeams",   scheduleModel.totalAssignedTeams());
        model.addAttribute("totalRounds",  scheduleModel.roundCount());

        // AC6: unassigned warning
        model.addAttribute("hasUnassigned",    scheduleModel.hasUnassigned());
        model.addAttribute("unassignedTeams",  unassignedMaps);

        // AC10: i18n keys for column headers and labels
        populateActivityScheduleI18n(model, locale);

        return "print/activity-schedule";
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Returns true if any phase in the list has at least one match with an assigned lap number.
     */
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
     * Each map entry is directly accessible as {@code {{fieldName}}} inside the section.
     * Boolean fields use Java boolean so jmustache treats them as truthy/falsy for section guards.
     *
     * @param rows list of LaufzettelRow objects
     * @return list of attribute maps ready for jmustache
     */
    private List<Map<String, Object>> toMustacheMaps(List<LaufzettelRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (LaufzettelRow row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("isPhaseHeader",   row.isPhaseHeader());
            map.put("phaseHeaderName", row.getPhaseHeaderName());
            map.put("isBreak",         row.isBreak());
            map.put("breakLabel",      row.getBreakLabel());
            map.put("breakTimeWindow", row.getBreakTimeWindow());
            map.put("roundNumber",     row.getRoundNumber());
            map.put("timeWindow",      row.getTimeWindow());
            map.put("isPlaying",       row.isPlaying());
            map.put("opponentName",    row.getOpponentName());
            map.put("fieldNumber",     row.getFieldNumber());
            map.put("isRefereeing",    row.isRefereeing());
            map.put("isActivity",      row.isActivity());
            map.put("activityName",    row.getActivityName());
            map.put("isFree",          row.isFree());
            result.add(map);
        }
        return result;
    }

    /**
     * Populates i18n model attributes consumed by the Laufzettel templates (AC14 — E08S08).
     *
     * <p>All static text (column headers, labels) is sourced from the message bundle.
     * German is the default locale.
     */
    private void populateLaufzettelI18n(Model model, Locale locale) {
        model.addAttribute("msgColRound",    msg("print.laufzettel.col.round",    "Runde",          locale));
        model.addAttribute("msgColTime",     msg("print.laufzettel.col.time",     "Zeit",           locale));
        model.addAttribute("msgColActivity", msg("print.laufzettel.col.activity", "Aktivit\u00e4t", locale));
        model.addAttribute("msgColField",    msg("print.laufzettel.col.field",    "Feld",           locale));
        model.addAttribute("msgReferee",     msg("print.laufzettel.label.referee","Schiedsrichter", locale));
        model.addAttribute("msgFree",        msg("print.laufzettel.label.free",   "Frei",           locale));
        model.addAttribute("msgBreak",       msg("print.laufzettel.label.break",  "Pause",          locale));
    }

    /**
     * Populates model for the no-matches error page (AC13 — E08S08).
     */
    private void populateNoMatchesErrorModel(Model model, Tournament tournament, Locale locale) {
        populateCommonModel(model, tournament, locale);
        model.addAttribute("title",         msg("print.laufzettel.error.nomatches.title",
                "VVWT Turniermanager \u2014 Laufzettel", locale));
        model.addAttribute("heading",       msg("print.laufzettel.error.nomatches.heading",
                "Spielplan nicht verf\u00fcgbar", locale));
        model.addAttribute("msgNoMatches",  msg("print.laufzettel.error.nomatches.message",
                "Die Spielpaarungen wurden noch nicht erstellt. " +
                "Bitte erstellen Sie den Spielplan, bevor Sie den Laufzettel drucken.", locale));
    }

    /**
     * Populates model attributes consumed by the {@code {{> print-header}}} partial (AC5, AC8 — E08S07).
     */
    private void populateCommonModel(Model model, Tournament tournament, Locale locale) {
        model.addAttribute("tournamentName", tournament.getDescription() != null
                ? tournament.getDescription()
                : msg("print.header.unnamedTournament", "Unnamed Tournament", locale));

        String dateStr = tournament.getAppointment() != null
                ? tournament.getAppointment().toLocalDate().format(DATE_FORMATTER)
                : "";
        model.addAttribute("tournamentDate", dateStr);

        model.addAttribute("msgTournamentLabel", msg("print.header.tournamentLabel", "Tournament", locale));
        model.addAttribute("msgDateLabel",       msg("print.header.dateLabel",       "Date",       locale));
        model.addAttribute("phaseName",          "");
        model.addAttribute("msgPhaseLabel",      msg("print.header.phaseLabel",      "Phase",      locale));
        model.addAttribute("cssPath",            "/print/assets/print.css");
        model.addAttribute("appVersion",         appVersion);
        model.addAttribute("locale",             locale.toLanguageTag());
    }

    /**
     * Populates model for the no-phases error page (AC7 — E08S07).
     */
    private void populateErrorModel(Model model, Tournament tournament, Locale locale) {
        populateCommonModel(model, tournament, locale);
        model.addAttribute("title",      msg("print.error.title",   "Print — Not Available", locale));
        model.addAttribute("heading",    msg("print.error.heading", "Cannot Print",          locale));
        model.addAttribute("msgNophase", msg("print.error.nophase",
                "The tournament schedule has not been generated yet. " +
                "Please apply the draft in the admin panel before printing.", locale));
    }

    /**
     * Converts a list of {@link ActivityScheduleRow} to jmustache-compatible list of attribute maps.
     *
     * <p>Each map has the same keys regardless of row type (isDataRow / isBreak), so that
     * jmustache strict mode does not throw {@code MustacheException} on absent keys.
     *
     * @param rows list of ActivityScheduleRow objects
     * @return list of attribute maps ready for jmustache
     */
    private List<Map<String, Object>> toActivityScheduleRowMaps(List<ActivityScheduleRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (ActivityScheduleRow row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("isDataRow",       row.isDataRow());
            map.put("roundNumber",     row.getRoundNumber());
            map.put("timeWindow",      row.getTimeWindow());
            map.put("teamNames",       row.getTeamNames());
            map.put("isBreak",         row.isBreak());
            map.put("breakLabel",      row.getBreakLabel());
            map.put("breakTimeWindow", row.getBreakTimeWindow());
            result.add(map);
        }
        return result;
    }

    /**
     * Populates i18n model attributes consumed by the activity-schedule template (AC10 — E08S09).
     */
    private void populateActivityScheduleI18n(Model model, Locale locale) {
        model.addAttribute("msgColRound",       msg("print.activitySchedule.col.round",    "Runde",         locale));
        model.addAttribute("msgColTime",        msg("print.activitySchedule.col.time",     "Zeit",          locale));
        model.addAttribute("msgColTeams",       msg("print.activitySchedule.col.teams",    "Mannschaften",  locale));
        model.addAttribute("msgSummary",        msg("print.activitySchedule.summary",
                "{totalTeams} Mannschaften, verteilt auf {totalRounds} Runden.", locale));
        model.addAttribute("msgUnassignedTitle",msg("print.activitySchedule.unassigned.title",
                "Keine freie Runde verf\u00fcgbar", locale));
        model.addAttribute("msgBreakLabel",     msg("print.activitySchedule.break",        "Pause",         locale));
    }

    /**
     * Populates model for the no-matches error page in the activity schedule context (AC9 — E08S09).
     */
    private void populateActivityScheduleNoMatchesErrorModel(Model model, Tournament tournament, Locale locale) {
        populateCommonModel(model, tournament, locale);
        model.addAttribute("title",      msg("print.error.title",   "Print \u2014 Not Available", locale));
        model.addAttribute("heading",    msg("print.error.heading", "Cannot Print",               locale));
        model.addAttribute("msgNophase", msg("print.activitySchedule.error.nomatches",
                "Der Spielplan wurde noch nicht erstellt. " +
                "Bitte erstellen Sie die Spielpaarungen, bevor Sie den Aktivit\u00e4tsplan drucken.", locale));
    }

    /**
     * Resolves a message from {@link MessageSource} with a safe fallback.
     */
    private String msg(String key, String fallback, Locale locale) {
        try {
            return messageSource.getMessage(key, null, fallback, locale);
        } catch (Exception e) {
            return fallback;
        }
    }
}
