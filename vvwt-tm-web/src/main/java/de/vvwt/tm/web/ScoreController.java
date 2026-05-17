// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import java.util.Locale;
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
 * Spring MVC controller for scoring tablet routes ({@code /score/**}) — reconstruction of {@code
 * de.vvwt.tm.infrastructure.score.ScoreController} in the {@code de.vvwt.tm.web} Modulith module.
 *
 * <h2>E22S10 — TDD-reconstruct ScoreController in web.* (DEC-19 Mustache + ES5 carve-out preserved)
 * </h2>
 *
 * <p>Reconstructed via RED-first TDD per DEC-22 Iron Law. URL mappings, model attribute keys, view
 * names, and constructor injection signature are byte-equivalent to the legacy implementation.
 * Mustache templates ({@code templates/score/*.mustache}) and the ES5 static asset ({@code
 * static/score/assets/vvwt-tablet.js}) are UNCHANGED per DEC-19 carve-out.
 *
 * <h2>DEC-40 Clause A — Primary-Adapter-Isolation</h2>
 *
 * <p>REST controllers reside in {@code de.vvwt.tm.web}, not in bounded-context modules. This
 * controller is a driving-side primary adapter: it maps HTTP requests to Mustache template
 * rendering using {@link MessageSource} for i18n. No bounded-context services are injected (the
 * scoring tablet endpoints are stateless page-renderers — the device token and API calls are
 * handled client-side in ES5 JavaScript per DEC-19).
 *
 * <h2>AC-SECURITY-NO-ADMIN-AUTH (DEC-19 + DEC-12)</h2>
 *
 * <p>The {@code /score/**} routes are accessible without admin authentication. Device
 * authentication (device token) is handled client-side via localStorage/cookie. The controller DOES
 * NOT place the device token in the model — token storage is client-side only (AC9 preservation
 * from legacy).
 *
 * <h2>Coexistence (E22S10 transitional window)</h2>
 *
 * <p>During the reconstruction-in-place window before E22S11 atomic cutover, the legacy {@code
 * de.vvwt.tm.infrastructure.score.ScoreController} is excluded from component scan in {@link
 * de.vvwt.tm.TournamentManagerApplication} to prevent ambiguous-mapping startup failure. The
 * exclusion is removed at E22S11 when the legacy class is deleted.
 *
 * @see de.vvwt.tm.auth.internal.SecurityConfig — permits /score/** without authentication
 * @see <a href="DEC-19">DEC-19 — Mustache + ES5 carve-out for scoring tablet UI</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith adoption with atomic cutover</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first reconstruction)</a>
 * @see <a href="DEC-35">DEC-35 — Package layout (controllers in web module per DEC-40
 *     amendment)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation: controllers in de.vvwt.tm.web</a>
 * @see <a href="E22S10">E22S10</a>
 */
@Controller
@RequestMapping("/score")
public class ScoreController {

    private final MessageSource messageSource;
    private final String appVersion;

    /**
     * Constructor injection.
     *
     * <p>{@link BuildProperties} is optional — it is only available when the application is
     * packaged with {@code spring-boot-maven-plugin} and the {@code build-info} goal is enabled. In
     * test environments or plain IDE runs, the bean is absent. The version label falls back to
     * {@code "dev"}.
     *
     * <p>Constructor signature is byte-equivalent to the legacy {@code
     * de.vvwt.tm.infrastructure.score.ScoreController} per AC-CONTROLLER-AUTHORED (E22S10).
     */
    @Autowired
    public ScoreController(
            MessageSource messageSource,
            @Autowired(required = false) BuildProperties buildProperties) {
        this.messageSource = messageSource;
        this.appVersion = buildProperties != null ? buildProperties.getVersion() : "dev";
    }

    /**
     * Hello-world page confirming Mustache integration and ES5 utility script delivery.
     *
     * <p>Mapped to {@code GET /score/test} (AC-URL-MAP-PARITY). Renders the Mustache template
     * {@code classpath:/templates/score/hello.mustache}. View name {@code "score/hello"} is
     * byte-equivalent to the legacy controller (AC-VIEW-NAME-PARITY).
     *
     * <p>Model attribute keys are enumerated in {@code ScoreControllerSliceTest#HELLO_WORLD_KEYS}
     * (AC-MODEL-ATTRIBUTE-PARITY).
     *
     * @param model Spring MVC model populated with i18n strings
     * @return Mustache view name {@code score/hello}
     */
    @GetMapping("/test")
    public String helloWorld(Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        model.addAttribute("locale", locale.toLanguageTag());
        model.addAttribute("title", msg("score.hello.title", "Scoring Tablet", locale));
        model.addAttribute("heading", msg("score.hello.heading", "Scoring Tablet", locale));
        model.addAttribute(
                "description", msg("score.hello.description", "Foundation page.", locale));
        model.addAttribute("versionLabel", msg("score.hello.version.label", "Version", locale));
        model.addAttribute("appVersion", appVersion);

        return "score/hello";
    }

    /**
     * Tablet registration page.
     *
     * <p>Mapped to {@code GET /score/register} (AC-URL-MAP-PARITY). Renders the Mustache template
     * {@code classpath:/templates/score/register.mustache}. View name {@code "score/register"} is
     * byte-equivalent to the legacy controller (AC-VIEW-NAME-PARITY).
     *
     * <p>The page immediately calls {@code POST /api/devices/register} via ES5 inline JavaScript
     * when loaded. The device token returned is stored client-side only (localStorage with cookie
     * fallback). The controller does NOT include the device token in the model — the token never
     * appears in rendered HTML source (AC-SECURITY-DEVICE-REGISTRATION).
     *
     * <p>This URL is consumed by the Svelte admin-UI at {@code Devices.svelte:272,489} as the
     * QR-code target for tablet onboarding (O-13 hard invariant — URL preservation is mandatory).
     *
     * <p>Model attribute keys are enumerated in {@code ScoreControllerSliceTest#REGISTER_PAGE_KEYS}
     * (AC-MODEL-ATTRIBUTE-PARITY).
     *
     * @param model Spring MVC model populated with i18n strings (no device token —
     *     AC-SECURITY-DEVICE-REGISTRATION)
     * @return Mustache view name {@code score/register}
     */
    @GetMapping("/register")
    public String registerPage(Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        model.addAttribute("locale", locale.toLanguageTag());
        model.addAttribute(
                "title", msg("score.register.title", "Scoring Tablet — Registration", locale));
        model.addAttribute("heading", msg("score.register.heading", "Scoring Tablet", locale));
        model.addAttribute(
                "msgRegistering", msg("score.register.registering", "Registering…", locale));
        model.addAttribute(
                "msgWaiting", msg("score.register.waiting", "Waiting for assignment…", locale));
        model.addAttribute(
                "msgPinInstruction",
                msg(
                        "score.register.pin.instruction",
                        "Please tell this PIN to the organizer",
                        locale));
        model.addAttribute("msgPolling", msg("score.register.polling", "Being assigned…", locale));
        model.addAttribute(
                "msgErrorReg",
                msg(
                        "score.register.error.registration",
                        "Registration failed. Please try again.",
                        locale));
        model.addAttribute(
                "msgErrorNet",
                msg("score.register.error.network", "Network error. Please try again.", locale));
        model.addAttribute("msgRetry", msg("score.register.retry", "Try again", locale));
        model.addAttribute("versionLabel", msg("score.register.version.label", "Version", locale));
        model.addAttribute("appVersion", appVersion);

        // AC-SECURITY-DEVICE-REGISTRATION: device token is intentionally NOT added to the model.
        // Token storage (localStorage / cookie) is performed entirely in client-side ES5 JS.

        return "score/register";
    }

    /**
     * Score entry page for a specific field.
     *
     * <p>Mapped to {@code GET /score/field/{fieldNumber}} (AC-URL-MAP-PARITY). Renders the Mustache
     * template {@code classpath:/templates/score/field.mustache}. View name {@code "score/field"}
     * is byte-equivalent to the legacy controller (AC-VIEW-NAME-PARITY).
     *
     * <p>The device token is read client-side from localStorage/cookie and used to call {@code GET
     * /api/score/match?field={n}&token={t}} on page load. The controller does NOT validate the
     * token — validation happens in {@code ScoreEntryService} per API call. This allows the page to
     * render first and then show an error if the token is missing (AC-INVALID-DEVICE-TOKEN-REDIRECT
     * preserved legacy behavior).
     *
     * <p>Model attribute keys are enumerated in {@code ScoreControllerSliceTest#FIELD_PAGE_KEYS}
     * (AC-MODEL-ATTRIBUTE-PARITY).
     *
     * @param fieldNumber the court field number from the URL path (1-based)
     * @param model Spring MVC model populated with i18n strings
     * @return Mustache view name {@code score/field}
     */
    @GetMapping("/field/{fieldNumber}")
    public String fieldPage(@PathVariable("fieldNumber") int fieldNumber, Model model) {
        Locale locale = LocaleContextHolder.getLocale();

        model.addAttribute("locale", locale.toLanguageTag());
        model.addAttribute("fieldNumber", fieldNumber);
        model.addAttribute(
                "title", msg("score.field.title", "Scoring Tablet — Field " + fieldNumber, locale));
        model.addAttribute("heading", msg("score.field.heading", "Scoring Tablet", locale));
        model.addAttribute("msgFieldLabel", msg("score.field.field.label", "Field", locale));
        model.addAttribute(
                "msgNoMatch", msg("score.field.no.match", "No active match on this field", locale));
        model.addAttribute("msgLapLabel", msg("score.field.lap.label", "Round", locale));
        model.addAttribute("msgSetLabel", msg("score.field.set.label", "Set", locale));
        model.addAttribute("msgVsLabel", msg("score.field.vs.label", "vs.", locale));
        model.addAttribute("msgRefereeLabel", msg("score.field.referee.label", "Referee", locale));
        model.addAttribute("msgTeam1Label", msg("score.field.team1.label", "Team 1", locale));
        model.addAttribute("msgTeam2Label", msg("score.field.team2.label", "Team 2", locale));
        model.addAttribute("msgPlusLabel", msg("score.field.plus.label", "+", locale));
        model.addAttribute("msgMinusLabel", msg("score.field.minus.label", "-", locale));
        model.addAttribute(
                "msgConfirmHeading",
                msg("score.field.confirm.heading", "Confirm set result", locale));
        model.addAttribute(
                "msgConfirmPrompt",
                msg("score.field.confirm.prompt", "Final score for this set?", locale));
        model.addAttribute("msgConfirmYes", msg("score.field.confirm.yes", "Confirm", locale));
        model.addAttribute("msgConfirmNo", msg("score.field.confirm.no", "Cancel", locale));
        model.addAttribute("msgLoading", msg("score.field.loading", "Loading match…", locale));
        model.addAttribute(
                "msgErrorNet",
                msg("score.field.error.network", "Network error. Please retry.", locale));
        model.addAttribute(
                "msgErrorToken",
                msg(
                        "score.field.error.token",
                        "Device not authorized. Please register again.",
                        locale));
        model.addAttribute(
                "msgErrorForbidden",
                msg(
                        "score.field.error.forbidden",
                        "This device is not authorized for this field.",
                        locale));
        model.addAttribute(
                "msgErrorValidation",
                msg(
                        "score.field.error.validation",
                        "Invalid score. Please check and retry.",
                        locale));
        model.addAttribute(
                "msgSubmitSuccess",
                msg("score.field.submit.success", "Set result recorded.", locale));
        model.addAttribute("msgQueuePending", msg("score.field.queue.pending", "Saving…", locale));
        model.addAttribute("msgQueueSaved", msg("score.field.queue.saved", "Saved.", locale));
        model.addAttribute("versionLabel", msg("score.field.version.label", "Version", locale));
        model.addAttribute("appVersion", appVersion);
        // AC1/AC8 (E61S03): swap control label
        model.addAttribute("msgSwapLabel", msg("score.field.swap.label", "Swap sides", locale));

        return "score/field";
    }

    /**
     * Resolve a message from the {@link MessageSource} with a safe fallback.
     *
     * <p>Returns {@code fallback} if the key is not present or the message source cannot resolve
     * it, preventing Mustache template rendering errors on missing keys
     * (AC-MESSAGESOURCE-FALLBACK).
     *
     * @param key message key
     * @param fallback value to return if the key is absent
     * @param locale target locale
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
