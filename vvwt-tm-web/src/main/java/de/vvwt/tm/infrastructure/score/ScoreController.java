package de.vvwt.tm.infrastructure.score;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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
 *       Device authentication (device token) is deferred to E06S04.</li>
 *   <li>AC10: Mustache template model is populated from the Spring {@link MessageSource}
 *       locale bundle, establishing the i18n mechanism reused by subsequent E06 stories.</li>
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
