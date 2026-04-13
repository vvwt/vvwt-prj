package de.vvwt.tm.infrastructure.score;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Locale;

/**
 * Spring MVC controller for scoring tablet routes ({@code /score/**}).
 *
 * <h2>Story E06S02 — AC3, AC9, AC10</h2>
 * <ul>
 *   <li>AC3: All scoring tablet routes are served under {@code /score/**}, physically
 *       separated from {@code /admin/**} (Svelte SPA) and {@code /api/**} (REST).</li>
 *   <li>AC9: The {@code /score/**} routes are accessible without admin authentication.
 *       Device authentication (device token) is handled client-side via device token.</li>
 *   <li>AC10: Mustache template model is populated from the Spring {@link MessageSource}
 *       locale bundle, establishing the i18n mechanism reused by subsequent E06 stories.</li>
 * </ul>
 *
 * <h2>Story E06S04 — AC1, AC2, AC10</h2>
 * <ul>
 *   <li>AC1: {@code GET /score/register} renders the registration Mustache template.</li>
 *   <li>AC2: Template model includes PIN instruction text and page heading from i18n bundle.</li>
 *   <li>AC9: The controller does NOT pass the device token to the template model —
 *       token storage is client-side only (localStorage / cookie).</li>
 *   <li>AC10: All user-visible strings resolved from {@link MessageSource}.</li>
 * </ul>
 *
 * <h2>DEC-12, DEC-19</h2>
 * <p>Templates are resolved from {@code classpath:/templates/score/} by the
 * auto-configured Mustache {@code ViewResolver}. The scoring tablet surface uses
 * Mustache + ES5 JavaScript only — no Svelte, no modern JS framework.</p>
 *
 * <h2>i18n mechanism (AC10)</h2>
 * <p>Locale-resolved strings are fetched from the Spring {@link MessageSource}
 * ({@code messages.properties}) and passed to the Mustache template as model
 * attributes. Each Mustache page follows this pattern: the controller loads its
 * keys from {@code MessageSource}, the template renders them via
 * {@code {{key}}} placeholders. Missing keys fall back to a safe default string
 * to avoid template rendering errors.</p>
 *
 * @see de.vvwt.tm.auth.SecurityConfig — permits /score/** without auth
 */
@Controller
@RequestMapping("/score")
public class ScoreController {

    private final MessageSource messageSource;
    private final String appVersion;

    /**
     * Constructor injection.
     *
     * <p>{@link BuildProperties} is optional — it is only available when the
     * application is packaged with {@code spring-boot-maven-plugin} and the
     * {@code build-info} goal is enabled. In test environments or plain IDE runs,
     * the bean is absent. The version label falls back to {@code "dev"}.
     */
    @Autowired
    public ScoreController(MessageSource messageSource,
                           @Autowired(required = false) BuildProperties buildProperties) {
        this.messageSource = messageSource;
        this.appVersion = buildProperties != null ? buildProperties.getVersion() : "dev";
    }

    /**
     * Hello-world page confirming Mustache integration and ES5 utility script delivery.
     *
     * <p>Mapped to {@code GET /score/test} (AC3). Renders the Mustache template
     * {@code classpath:/templates/score/hello.mustache}.</p>
     *
     * @param model Spring MVC model populated with i18n strings
     * @return Mustache view name {@code score/hello}
     */
    @GetMapping("/test")
    public String helloWorld(Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        model.addAttribute("locale", locale.toLanguageTag());
        model.addAttribute("title",       msg("score.hello.title",        "Scoring Tablet", locale));
        model.addAttribute("heading",     msg("score.hello.heading",      "Scoring Tablet", locale));
        model.addAttribute("description", msg("score.hello.description",  "Foundation page.", locale));
        model.addAttribute("versionLabel", msg("score.hello.version.label", "Version", locale));
        model.addAttribute("appVersion",  appVersion);

        return "score/hello";
    }

    /**
     * Tablet registration page — E06S04.
     *
     * <p>Mapped to {@code GET /score/register} (AC1). Renders the Mustache template
     * {@code classpath:/templates/score/register.mustache}.</p>
     *
     * <p>The page immediately calls {@code POST /api/devices/register} via ES5 inline
     * JavaScript when loaded. The device token returned is stored client-side only
     * (localStorage with cookie fallback — AC3). The controller does NOT include the
     * device token in the template model — the token never appears in rendered HTML
     * source (AC9).</p>
     *
     * <p>All user-visible text is resolved from the Spring {@link MessageSource}
     * ({@code messages.properties}) for German locale (AC10).</p>
     *
     * @param model Spring MVC model populated with i18n strings (no device token — AC9)
     * @return Mustache view name {@code score/register}
     */
    @GetMapping("/register")
    public String registerPage(Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        model.addAttribute("locale", locale.toLanguageTag());
        // AC2 — page title and heading
        model.addAttribute("title",             msg("score.register.title",               "Scoring Tablet — Registration", locale));
        model.addAttribute("heading",           msg("score.register.heading",             "Scoring Tablet", locale));
        // AC2 — registering / waiting state messages (used by JS to set initial status text)
        model.addAttribute("msgRegistering",    msg("score.register.registering",         "Registering\u2026", locale));
        model.addAttribute("msgWaiting",        msg("score.register.waiting",             "Waiting for assignment\u2026", locale));
        // AC2 — PIN instruction text shown after successful registration
        model.addAttribute("msgPinInstruction", msg("score.register.pin.instruction",     "Please tell this PIN to the organizer", locale));
        // AC4 — polling status message
        model.addAttribute("msgPolling",        msg("score.register.polling",             "Being assigned\u2026", locale));
        // AC8 — error messages
        model.addAttribute("msgErrorReg",       msg("score.register.error.registration",  "Registration failed. Please try again.", locale));
        model.addAttribute("msgErrorNet",       msg("score.register.error.network",       "Network error. Please try again.", locale));
        // AC8 — retry button label
        model.addAttribute("msgRetry",          msg("score.register.retry",               "Try again", locale));
        // version (informational)
        model.addAttribute("versionLabel", msg("score.register.version.label", "Version", locale));
        model.addAttribute("appVersion",   appVersion);

        // AC9: device token is intentionally NOT added to the model.
        // Token storage (localStorage / cookie) is performed entirely in client-side ES5 JS.

        return "score/register";
    }

    /**
     * Score entry page for a specific field (E06S06, AC2, AC3).
     *
     * <p>Mapped to {@code GET /score/field/{fieldNumber}} (AC2). Renders the Mustache template
     * {@code classpath:/templates/score/field.mustache}.</p>
     *
     * <p>The device token is read client-side from localStorage/cookie and used to call
     * {@code GET /api/score/match?field={n}&token={t}} on page load (AC3). The controller
     * does NOT validate the token — validation happens in {@link ScoreEntryService} per API call.
     * This allows the page to render first and then show an error if token is missing (AC13).</p>
     *
     * <p>All user-visible text is resolved from the Spring {@link MessageSource}
     * ({@code messages.properties}) for i18n (AC13).</p>
     *
     * @param fieldNumber the court field number from the URL path (1-based)
     * @param model       Spring MVC model populated with i18n strings
     * @return Mustache view name {@code score/field}
     */
    @GetMapping("/field/{fieldNumber}")
    public String fieldPage(@PathVariable("fieldNumber") int fieldNumber, Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        model.addAttribute("locale", locale.toLanguageTag());
        model.addAttribute("fieldNumber", fieldNumber);

        // AC13 — page title and heading
        model.addAttribute("title",                 msg("score.field.title",               "Scoring Tablet — Field " + fieldNumber, locale));
        model.addAttribute("heading",               msg("score.field.heading",             "Scoring Tablet", locale));
        // AC4 — field number label
        model.addAttribute("msgFieldLabel",         msg("score.field.field.label",         "Field", locale));
        // AC3 — no match state (AC9)
        model.addAttribute("msgNoMatch",            msg("score.field.no.match",            "No active match on this field", locale));
        // AC3 — match display labels
        model.addAttribute("msgLapLabel",           msg("score.field.lap.label",           "Round", locale));
        model.addAttribute("msgSetLabel",           msg("score.field.set.label",           "Set", locale));
        model.addAttribute("msgVsLabel",            msg("score.field.vs.label",            "vs.", locale));
        model.addAttribute("msgRefereeLabel",       msg("score.field.referee.label",       "Referee", locale));
        // AC3 — score entry controls
        model.addAttribute("msgTeam1Label",         msg("score.field.team1.label",         "Team 1", locale));
        model.addAttribute("msgTeam2Label",         msg("score.field.team2.label",         "Team 2", locale));
        model.addAttribute("msgPlusLabel",          msg("score.field.plus.label",          "+", locale));
        model.addAttribute("msgMinusLabel",         msg("score.field.minus.label",         "-", locale));
        // AC5 — confirm dialog
        model.addAttribute("msgConfirmHeading",     msg("score.field.confirm.heading",     "Confirm set result", locale));
        model.addAttribute("msgConfirmPrompt",      msg("score.field.confirm.prompt",      "Final score for this set?", locale));
        model.addAttribute("msgConfirmYes",         msg("score.field.confirm.yes",         "Confirm", locale));
        model.addAttribute("msgConfirmNo",          msg("score.field.confirm.no",          "Cancel", locale));
        // AC3 — error and status messages
        model.addAttribute("msgLoading",            msg("score.field.loading",             "Loading match\u2026", locale));
        model.addAttribute("msgErrorNet",           msg("score.field.error.network",       "Network error. Please retry.", locale));
        model.addAttribute("msgErrorToken",         msg("score.field.error.token",         "Device not authorized. Please register again.", locale));
        model.addAttribute("msgErrorForbidden",     msg("score.field.error.forbidden",     "This device is not authorized for this field.", locale));
        model.addAttribute("msgErrorValidation",    msg("score.field.error.validation",    "Invalid score. Please check and retry.", locale));
        model.addAttribute("msgSubmitSuccess",      msg("score.field.submit.success",      "Set result recorded.", locale));
        // AC11 — score queue status
        model.addAttribute("msgQueuePending",       msg("score.field.queue.pending",       "Saving\u2026", locale));
        model.addAttribute("msgQueueSaved",         msg("score.field.queue.saved",         "Saved.", locale));
        // version (informational)
        model.addAttribute("versionLabel",          msg("score.field.version.label",       "Version", locale));
        model.addAttribute("appVersion",            appVersion);

        return "score/field";
    }

    /**
     * Resolve a message from the {@link MessageSource} with a safe fallback.
     *
     * <p>Returns {@code fallback} if the key is not present or the message source
     * cannot be resolved, preventing Mustache template rendering errors on missing keys.</p>
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
