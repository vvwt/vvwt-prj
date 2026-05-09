package de.vvwt.tm.web;

import de.vvwt.tm.print.ActivityScheduleAssembler;
import de.vvwt.tm.print.ActivityScheduleModel;
import de.vvwt.tm.print.ActivityScheduleRow;
import de.vvwt.tm.print.LaufzettelAssembler;
import de.vvwt.tm.print.LaufzettelRow;
import de.vvwt.tm.tenant.LocationDisplayResolver;
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
import de.vvwt.tm.tournament.activity.ActivityType;
import de.vvwt.tm.tournament.activity.ActivityTypeRepository;
import de.vvwt.tm.tournament.exceptions.TournamentNotFoundException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * REST controller for print-native endpoints at new URLs (E24S06 — fresh Q-1a TDD reconstruction).
 *
 * <p>This is the NEW {@code web.PrintController} at {@code de.vvwt.tm.web} per DEC-40
 * Primary-Adapter-Isolation. Serves 4 print-native endpoints at new domain-aligned URLs:
 *
 * <ul>
 *   <li>{@code GET /print/tournaments/{tid}} — print index
 *   <li>{@code GET /print/tournaments/{tid}/team-schedules/{teamId}} — Laufzettel single team
 *   <li>{@code GET /print/tournaments/{tid}/team-schedules} — Laufzettel all teams
 *   <li>{@code GET /print/tournaments/{tid}/activity-schedule/{activityTypeId}} — activity schedule
 * </ul>
 *
 * <p>The legacy {@code PrintController} served URLs ({@code /print/{tid}/...}) until the E24S07
 * atomic cutover (DEC-21 reconstruction-in-place) at which point it was deleted. No certificate
 * endpoints — those are owned by {@code web.certificate.CertificateRenderController} (E24S05).
 *
 * <h2>Bean-name (AC-BEAN-NAME-NEW-EXPLICIT)</h2>
 *
 * <p>Explicit bean name {@code "tmPrintController"} was chosen at E24S06 to prevent collision with
 * the now-deleted legacy {@code PrintController} (Spring default bean name {@code
 * "printController"}). Follows project convention: {@code tmTournamentController}, {@code
 * tmDeviceController}, etc.
 *
 * <h2>URL disjointness</h2>
 *
 * <p>Legacy URLs use {@code /print/{UUID}/} path-variable pattern; new URLs use {@code
 * /print/tournaments/{UUID}/} literal segment. Spring's URL matcher distinguishes (literal
 * "tournaments" vs UUID-typed path variable) — no {@code AmbiguousMappingException}.
 *
 * <h2>DEC-40 Trigger-β (AC-BETA-DOES-NOT-FIRE)</h2>
 *
 * <p>All 4 endpoints import from {tournament, print} = 2 bounded contexts (tenant excluded per D-16
 * natural reading). β does NOT fire.
 *
 * <h2>DEC-40 Clause B disposition (AC-DEC-40-CLAUSE-B-N-A)</h2>
 *
 * <p>All 4 endpoints return Mustache view names (HTML). No JSON DTOs. Clause B N/A.
 *
 * @see de.vvwt.tm.web.GlobalExceptionHandler — handles TournamentNotFoundException
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (Q-1a RED-first)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-38">DEC-38 — @ApplicationModuleTest IT canon</a>
 * @since E24S06
 */
@Controller("tmPrintController")
@RequestMapping("/print/tournaments/{tid}")
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
    private final TenantContext tenantContext;
    private final LocationDisplayResolver locationDisplayResolver;
    private final MessageSource messageSource;
    private final String appVersion;

    /**
     * Constructor injection (AC-DEPS-INJECTED).
     *
     * <p>{@link BuildProperties} is optional — absent in test environments. Falls back to {@code
     * "dev"}.
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
            TenantContext tenantContext,
            LocationDisplayResolver locationDisplayResolver,
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
        this.tenantContext = tenantContext;
        this.locationDisplayResolver = locationDisplayResolver;
        this.messageSource = messageSource;
        this.appVersion = buildProperties != null ? buildProperties.getVersion() : "dev";
    }

    // =========================================================================
    // E24S06 — printIndex (AC-URL-INDEX)
    // =========================================================================

    /**
     * Print index page for a tournament at new URL {@code GET /print/tournaments/{tid}}.
     *
     * <p>Mapped at the controller's class-level {@code @RequestMapping} root (AC-URL-INDEX).
     *
     * <ul>
     *   <li>Tournament not found → {@link TournamentNotFoundException} → 404 via {@code
     *       GlobalExceptionHandler.handleTournamentNotFound} (S05, AC-THROW-TNFE).
     *   <li>No phases (draft) → {@code print/error} view (AC-URL-INDEX).
     *   <li>Otherwise → {@code print/index} with model attributes (AC-URL-INDEX).
     * </ul>
     *
     * @param tid tournament UUID from path variable (AC-URL-INDEX)
     * @param model Spring MVC model
     * @return Mustache view name
     */
    @GetMapping
    public String printIndex(@PathVariable("tid") UUID tid, Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        // AC-THROW-TNFE: throws TournamentNotFoundException → GlobalExceptionHandler → 404
        Tournament tournament =
                tournamentRepository
                        .findById(tid)
                        .orElseThrow(() -> new TournamentNotFoundException(tid));

        List<Phase> phases = phaseRepository.findByTournamentId(tid);
        if (phases.isEmpty()) {
            populateErrorModel(model, tournament, locale);
            return "print/error";
        }

        populateCommonModel(model, tournament, locale);
        model.addAttribute("tournament", tournament);
        model.addAttribute("tournamentId", tid.toString()); // required by index.mustache line 16
        model.addAttribute("title", msg("print.index.title", "Tournament Schedule", locale));
        model.addAttribute("heading", msg("print.index.heading", "Print Documents", locale));

        // AC-URL-INDEX: laufzettelUrl uses new URL scheme
        model.addAttribute("laufzettelUrl", "/print/tournaments/" + tid + "/team-schedules");
        // AC-URL-INDEX: fotosUrl — legacy behaviour preserved (dead link per legacy)
        model.addAttribute("fotosUrl", "/print/tournaments/" + tid + "/fotos");

        model.addAttribute(
                "msgLaufzettelLink",
                msg("print.index.laufzettel.link", "Team Schedule (Laufzettel)", locale));
        model.addAttribute("msgFotosLink", msg("print.index.fotos.link", "Photo Schedule", locale));

        return "print/index";
    }

    // =========================================================================
    // E24S06 — singleTeamSchedule (AC-URL-LAUFZETTEL-SINGLE)
    // =========================================================================

    /**
     * Renders the Laufzettel for a single team at new URL {@code GET
     * /print/tournaments/{tid}/team-schedules/{teamId}}.
     *
     * <ul>
     *   <li>Tournament not found → 404 (AC-THROW-TNFE).
     *   <li>Team not found → 404 (AC-URL-LAUFZETTEL-SINGLE: team not found →
     *       TournamentNotFoundException).
     *   <li>No phases → {@code print/error}.
     *   <li>No matches → {@code print/laufzettel-no-matches}.
     *   <li>Otherwise → {@code print/laufzettel}.
     * </ul>
     *
     * @param tid tournament UUID
     * @param teamId team UUID
     * @param model Spring MVC model
     * @return Mustache view name
     */
    @GetMapping("/team-schedules/{teamId}")
    public String singleTeamSchedule(
            @PathVariable("tid") UUID tid, @PathVariable("teamId") UUID teamId, Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        Tournament tournament =
                tournamentRepository
                        .findById(tid)
                        .orElseThrow(() -> new TournamentNotFoundException(tid));

        List<Team> teams = new ArrayList<>(teamRepository.findByTournamentId(tid));
        teams.sort(java.util.Comparator.comparingInt(Team::getTeamNumber));

        Team requestedTeam =
                teams.stream()
                        .filter(t -> teamId.equals(t.getId()))
                        .findFirst()
                        .orElseThrow(() -> new TournamentNotFoundException(teamId)); // 404 for team

        List<Phase> phases =
                phaseRepository.findByTournamentId(tid).stream()
                        .filter(p -> Phase.PhaseStatus.ACTIVE.name().equals(p.getStatus()))
                        .toList();
        if (phases.isEmpty()) {
            populateErrorModel(model, tournament, locale);
            return "print/error";
        }

        if (!hasAnyMatches(phases)) {
            populateNoMatchesErrorModel(model, tournament, locale);
            return "print/laufzettel-no-matches";
        }

        Map<UUID, List<TeamAvatar>> avatarsByPhase = loadAvatarsByPhase(phases);
        Map<UUID, List<Match>> matchesByPhase = loadMatchesByPhase(phases);
        Map<UUID, List<PhaseBreak>> breaksByPhase = loadBreaksByPhase(phases);
        List<ActivityType> activityTypes = activityTypeRepository.findByTournamentId(tid);

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

    // =========================================================================
    // E24S06 — allTeamSchedules (AC-URL-LAUFZETTEL-ALL)
    // =========================================================================

    /**
     * Renders Laufzettel for ALL teams at new URL {@code GET
     * /print/tournaments/{tid}/team-schedules}.
     *
     * <ul>
     *   <li>Tournament not found → 404.
     *   <li>No phases → {@code print/error}.
     *   <li>No matches → {@code print/laufzettel-no-matches}.
     *   <li>Otherwise → {@code print/laufzettel-all}.
     * </ul>
     *
     * @param tid tournament UUID
     * @param model Spring MVC model
     * @return Mustache view name
     */
    @GetMapping("/team-schedules")
    public String allTeamSchedules(@PathVariable("tid") UUID tid, Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        Tournament tournament =
                tournamentRepository
                        .findById(tid)
                        .orElseThrow(() -> new TournamentNotFoundException(tid));

        List<Phase> phases =
                phaseRepository.findByTournamentId(tid).stream()
                        .filter(p -> Phase.PhaseStatus.ACTIVE.name().equals(p.getStatus()))
                        .toList();
        if (phases.isEmpty()) {
            populateErrorModel(model, tournament, locale);
            return "print/error";
        }

        if (!hasAnyMatches(phases)) {
            populateNoMatchesErrorModel(model, tournament, locale);
            return "print/laufzettel-no-matches";
        }

        List<Team> teams = new ArrayList<>(teamRepository.findByTournamentId(tid));
        teams.sort(java.util.Comparator.comparingInt(Team::getTeamNumber));

        Map<UUID, List<TeamAvatar>> avatarsByPhase = loadAvatarsByPhase(phases);
        Map<UUID, List<Match>> matchesByPhase = loadMatchesByPhase(phases);
        Map<UUID, List<PhaseBreak>> breaksByPhase = loadBreaksByPhase(phases);
        List<ActivityType> activityTypes = activityTypeRepository.findByTournamentId(tid);

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
            schedule.put("showPageBreak", i > 0);
            schedules.add(schedule);
        }

        populateCommonModel(model, tournament, locale);
        model.addAttribute(
                "title",
                msg("print.laufzettel.title.all", "VVWT Turniermanager — Alle Laufzettel", locale));
        model.addAttribute("heading", msg("print.laufzettel.title.all", "Alle Laufzettel", locale));
        model.addAttribute("schedules", schedules);
        // laufzettel-all.mustache comment block references {{teamNumber}}, {{teamName}},
        // {{heading}}, {{hasTime}}, {{showPageBreak}}, {{rows}} at the top-level template context
        // (lines 16–17). jmustache in strict mode evaluates {{...}} tags even inside {{! }}
        // comments — expose stub top-level values so strict-mode doesn't throw.
        // heading is already added above.
        model.addAttribute("hasTime", hasTime);
        model.addAttribute("teamNumber", 0);
        model.addAttribute("teamName", "");
        model.addAttribute("showPageBreak", false);
        model.addAttribute("rows", List.of());
        populateLaufzettelI18n(model, locale);

        return "print/laufzettel-all";
    }

    // =========================================================================
    // E24S06 — activitySchedule (AC-URL-ACTIVITY-SCHEDULE)
    // =========================================================================

    /**
     * Renders the Mannschaftsfoto-Übersicht at new URL {@code GET
     * /print/tournaments/{tid}/activity-schedule/{activityTypeId}}.
     *
     * <ul>
     *   <li>Tournament not found → 404.
     *   <li>Activity type not found → 404 (via TournamentNotFoundException per legacy pattern).
     *   <li>No phases → {@code print/error}.
     *   <li>No matches → {@code print/error}.
     *   <li>Otherwise → {@code print/activity-schedule}.
     * </ul>
     *
     * @param tid tournament UUID
     * @param activityTypeId activity type UUID
     * @param model Spring MVC model
     * @return Mustache view name
     */
    @GetMapping("/activity-schedule/{activityTypeId}")
    public String activitySchedule(
            @PathVariable("tid") UUID tid,
            @PathVariable("activityTypeId") UUID activityTypeId,
            Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        Tournament tournament =
                tournamentRepository
                        .findById(tid)
                        .orElseThrow(() -> new TournamentNotFoundException(tid));

        List<ActivityType> activityTypes = activityTypeRepository.findByTournamentId(tid);
        ActivityType targetType =
                activityTypes.stream()
                        .filter(at -> activityTypeId.equals(at.getId()))
                        .findFirst()
                        .orElseThrow(() -> new TournamentNotFoundException(activityTypeId)); // 404

        List<Phase> phases = phaseRepository.findByTournamentId(tid);
        if (phases.isEmpty()) {
            populateErrorModel(model, tournament, locale);
            return "print/error";
        }

        if (!hasAnyMatches(phases)) {
            populateActivityScheduleNoMatchesErrorModel(model, tournament, locale);
            return "print/error";
        }

        List<Team> teams = teamRepository.findByTournamentId(tid);
        Map<UUID, List<TeamAvatar>> avatarsByPhase = loadAvatarsByPhase(phases);
        Map<UUID, List<Match>> matchesByPhase = loadMatchesByPhase(phases);
        Map<UUID, List<PhaseBreak>> breaksByPhase = loadBreaksByPhase(phases);

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

        List<Map<String, Object>> rowMaps = toActivityScheduleRowMaps(scheduleModel.rows());

        List<Map<String, Object>> unassignedMaps = new ArrayList<>();
        for (String name : scheduleModel.unassignedTeamNames()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("teamName", name);
            unassignedMaps.add(m);
        }

        populateCommonModel(model, tournament, locale);

        String activityName = targetType.getName();
        String pageTitle =
                msg("print.activitySchedule.title", "VVWT Turniermanager — Zeitplan", locale)
                        + " — "
                        + activityName;
        String pageHeading =
                activityName
                        + " — "
                        + msg("print.activitySchedule.headingSuffix", "Zeitplan", locale);

        model.addAttribute("title", pageTitle);
        model.addAttribute("heading", pageHeading);
        model.addAttribute("activityName", activityName);
        model.addAttribute("hasTime", scheduleModel.hasTime());
        model.addAttribute("rows", rowMaps);
        model.addAttribute("totalTeams", scheduleModel.totalAssignedTeams());
        model.addAttribute("totalRounds", scheduleModel.roundCount());
        model.addAttribute("hasUnassigned", scheduleModel.hasUnassigned());
        model.addAttribute("unassignedTeams", unassignedMaps);
        populateActivityScheduleI18n(model, locale);

        return "print/activity-schedule";
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Returns {@code true} if any phase has at least one match with an assigned lap number. */
    private boolean hasAnyMatches(List<Phase> phases) {
        for (Phase phase : phases) {
            List<Match> matches = matchRepository.findByPhaseId(phase.getId());
            for (Match m : matches) {
                if (m.getLapNumber() != null) return true;
            }
        }
        return false;
    }

    private Map<UUID, List<TeamAvatar>> loadAvatarsByPhase(List<Phase> phases) {
        Map<UUID, List<TeamAvatar>> map = new HashMap<>();
        for (Phase phase : phases) {
            map.put(phase.getId(), teamAvatarRepository.findByPhaseId(phase.getId()));
        }
        return map;
    }

    private Map<UUID, List<Match>> loadMatchesByPhase(List<Phase> phases) {
        Map<UUID, List<Match>> map = new HashMap<>();
        for (Phase phase : phases) {
            map.put(phase.getId(), matchRepository.findByPhaseId(phase.getId()));
        }
        return map;
    }

    private Map<UUID, List<PhaseBreak>> loadBreaksByPhase(List<Phase> phases) {
        Map<UUID, List<PhaseBreak>> map = new HashMap<>();
        for (Phase phase : phases) {
            map.put(phase.getId(), phaseBreakRepository.findByPhaseId(phase.getId()));
        }
        return map;
    }

    /**
     * Converts {@link LaufzettelRow} instances to jmustache-compatible attribute maps.
     *
     * <p>jmustache renders {@code {{#rows}}} where rows is a {@code List<Map<String, Object>>}.
     */
    private List<Map<String, Object>> toMustacheMaps(List<LaufzettelRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (LaufzettelRow row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            // LaufzettelRow is a record — component accessors use the component name directly.
            // Boolean components starting with "is" are accessed as isPhaseHeader(), isBreak(),
            // etc.
            map.put("isPhaseHeader", row.isPhaseHeader());
            map.put("phaseHeaderName", row.phaseHeaderName());
            map.put("isBreak", row.isBreak());
            map.put("breakLabel", row.breakLabel());
            map.put("breakTimeWindow", row.breakTimeWindow());
            map.put("roundNumber", row.roundNumber());
            map.put("timeWindow", row.timeWindow());
            map.put("isPlaying", row.isPlaying());
            map.put("opponentName", row.opponentName());
            map.put("fieldNumber", row.fieldNumber());
            map.put("isRefereeing", row.isRefereeing());
            map.put("refereeMatchTeamA", row.refereeMatchTeamA());
            map.put("refereeMatchTeamB", row.refereeMatchTeamB());
            map.put("isActivity", row.isActivity());
            map.put("activityName", row.activityName());
            map.put("isFree", row.isFree());
            result.add(map);
        }
        return result;
    }

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

    private void populateNoMatchesErrorModel(Model model, Tournament tournament, Locale locale) {
        populateCommonModel(model, tournament, locale);
        model.addAttribute(
                "title",
                msg(
                        "print.laufzettel.error.nomatches.title",
                        "VVWT Turniermanager — Laufzettel",
                        locale));
        model.addAttribute(
                "heading",
                msg(
                        "print.laufzettel.error.nomatches.heading",
                        "Spielplan nicht verfügbar",
                        locale));
        model.addAttribute(
                "msgNoMatches",
                msg(
                        "print.laufzettel.error.nomatches.message",
                        "Die Spielpaarungen wurden noch nicht erstellt. Bitte erstellen Sie den"
                                + " Spielplan, bevor Sie den Laufzettel drucken.",
                        locale));
    }

    private void populateActivityScheduleNoMatchesErrorModel(
            Model model, Tournament tournament, Locale locale) {
        populateCommonModel(model, tournament, locale);
        model.addAttribute("title", msg("print.error.title", "Print — Not Available", locale));
        model.addAttribute("heading", msg("print.error.heading", "Cannot Print", locale));
        model.addAttribute(
                "msgNophase",
                msg(
                        "print.activitySchedule.error.nomatches",
                        "Der Spielplan wurde noch nicht erstellt. Bitte erstellen Sie die"
                                + " Spielpaarungen, bevor Sie den Aktivitätsplan drucken.",
                        locale));
    }

    private void populateLaufzettelI18n(Model model, Locale locale) {
        model.addAttribute("msgColRound", msg("print.laufzettel.col.round", "Runde", locale));
        model.addAttribute("msgColTime", msg("print.laufzettel.col.time", "Zeit", locale));
        model.addAttribute(
                "msgColActivity", msg("print.laufzettel.col.activity", "Aktivität", locale));
        model.addAttribute("msgColField", msg("print.laufzettel.col.field", "Feld", locale));
        model.addAttribute(
                "msgReferee", msg("print.laufzettel.label.referee", "Schiedsrichter", locale));
        model.addAttribute("msgFree", msg("print.laufzettel.label.free", "Frei", locale));
        model.addAttribute("msgBreak", msg("print.laufzettel.label.break", "Pause", locale));
        model.addAttribute("msgPlayingVs", msg("print.laufzettel.playing.vs", "vs", locale));
        model.addAttribute("msgVs", msg("print.laufzettel.vs", "vs", locale));
    }

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
                        "Keine freie Runde verfügbar",
                        locale));
        model.addAttribute("msgBreakLabel", msg("print.activitySchedule.break", "Pause", locale));
    }

    private String msg(String key, String fallback, Locale locale) {
        try {
            return messageSource.getMessage(key, null, fallback, locale);
        } catch (Exception e) {
            return fallback;
        }
    }
}
