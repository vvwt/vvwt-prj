package de.vvwt.tm.infrastructure.print;

import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.repo.PhaseRepository;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
 * <h2>DEC-12, DEC-15</h2>
 * <p>Templates resolved from {@code classpath:/templates/print/} by the auto-configured Mustache
 * {@code ViewResolver}. Shared partials ({@code print-header}, {@code print-page-break}) live at
 * {@code classpath:/templates/} root so jmustache can resolve them via {@code {{> print-header}}}.
 * Static print CSS is served from {@code classpath:/static/print/assets/print.css} (bundled in the
 * jlink archive automatically per DEC-15).</p>
 *
 * @see de.vvwt.tm.auth.SecurityConfig — configures /print/** as authenticated, /print/assets/** as permitAll
 */
@Controller
@RequestMapping("/print")
public class PrintController {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
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
                           MessageSource messageSource,
                           @Autowired(required = false) BuildProperties buildProperties) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.messageSource = messageSource;
        this.appVersion = buildProperties != null ? buildProperties.getVersion() : "dev";
    }

    /**
     * Print index page for a tournament.
     *
     * <p>Mapped to {@code GET /print/{tournamentId}} (AC1).
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

        // Links to E08S08 / E08S09 templates (rendered by those stories)
        model.addAttribute("tournamentId",      tournamentId.toString());
        model.addAttribute("laufzettelUrl",     "/print/" + tournamentId + "/laufzettel");
        model.addAttribute("fotosUrl",          "/print/" + tournamentId + "/fotos");
        model.addAttribute("msgLaufzettelLink", msg("print.index.laufzettel.link", "Team Schedule (Laufzettel)", locale));
        model.addAttribute("msgFotosLink",      msg("print.index.fotos.link",      "Photo Schedule",             locale));

        return "print/index";
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Populates model attributes consumed by the {@code {{> print-header}}} partial (AC5, AC8).
     *
     * <p>The partial template uses: {@code tournamentName}, {@code tournamentDate},
     * {@code phaseName}, {@code msgTournamentLabel}, {@code msgDateLabel}, {@code cssPath}.
     *
     * @param model      Spring MVC model to populate
     * @param tournament the current tournament
     * @param locale     the active locale for i18n resolution
     */
    private void populateCommonModel(Model model, Tournament tournament, Locale locale) {
        // Tournament name (description field per Tournament entity)
        model.addAttribute("tournamentName", tournament.getDescription() != null
                ? tournament.getDescription()
                : msg("print.header.unnamedTournament", "Unnamed Tournament", locale));

        // Tournament date from appointment field (LocalDateTime) — formatted as dd.MM.yyyy
        String dateStr = tournament.getAppointment() != null
                ? tournament.getAppointment().toLocalDate().format(DATE_FORMATTER)
                : "";
        model.addAttribute("tournamentDate", dateStr);

        // AC5: i18n labels consumed by print-header partial
        model.addAttribute("msgTournamentLabel", msg("print.header.tournamentLabel", "Tournament", locale));
        model.addAttribute("msgDateLabel",       msg("print.header.dateLabel",       "Date",       locale));

        // AC5: phaseName and msgPhaseLabel are always set in the model.
        // jmustache throws MustacheException for missing keys even inside {{#phaseName}}...{{/phaseName}}
        // sections (strict mode by default). E08S08/S09 will override phaseName with a non-empty
        // string when rendering phase-specific print templates. Here we set empty string so the
        // conditional section renders as empty (jmustache treats empty string as falsy).
        model.addAttribute("phaseName",      "");
        model.addAttribute("msgPhaseLabel",  msg("print.header.phaseLabel", "Phase", locale));

        // AC3, AC6: path to print CSS (served as static resource — DEC-15)
        model.addAttribute("cssPath", "/print/assets/print.css");

        model.addAttribute("appVersion",   appVersion);
        model.addAttribute("locale",       locale.toLanguageTag());
    }

    /**
     * Populates model for the no-phases error page (AC7).
     *
     * @param model      Spring MVC model to populate
     * @param tournament the current tournament (exists, but has no phases)
     * @param locale     the active locale
     */
    private void populateErrorModel(Model model, Tournament tournament, Locale locale) {
        populateCommonModel(model, tournament, locale);
        model.addAttribute("title",         msg("print.error.title",   "Print — Not Available", locale));
        model.addAttribute("heading",       msg("print.error.heading", "Cannot Print",          locale));
        model.addAttribute("msgNophase",    msg("print.error.nophase",
                "The tournament schedule has not been generated yet. " +
                "Please apply the draft in the admin panel before printing.", locale));
    }

    /**
     * Resolve a message from the {@link MessageSource} with a safe fallback.
     *
     * <p>Returns {@code fallback} if the key is absent or resolution fails, preventing
     * Mustache template rendering errors on missing keys.
     *
     * @param key      message key
     * @param fallback value to return if the key is absent
     * @param locale   target locale
     * @return resolved message string
     */
    private String msg(String key, String fallback, Locale locale) {
        try {
            return messageSource.getMessage(key, null, fallback, locale);
        } catch (Exception e) {
            return fallback;
        }
    }
}
