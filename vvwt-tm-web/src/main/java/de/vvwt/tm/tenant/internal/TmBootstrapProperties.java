package de.vvwt.tm.tenant.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the default-tenant bootstrap at first boot.
 *
 * <h2>Purpose</h2>
 *
 * <p>Binds {@code tm.bootstrap.default-tenant.*} from {@code application.yml} (or environment
 * override) to this bean. These properties are consulted ONLY on first boot (empty registry). On
 * subsequent boots the runner respects the existing registry — see {@code
 * AC-FIRST-BOOT-ONLY-PROPERTY-EVALUATION}.
 *
 * <h2>Properties</h2>
 *
 * <ul>
 *   <li>{@code tm.bootstrap.default-tenant.display-name} — human-readable name for the bootstrapped
 *       default tenant. Default: {@code "ToM Tournament Manager (LAN)"}. Override via env var
 *       {@code TM_BOOTSTRAP_DEFAULT_TENANT_DISPLAY_NAME}.
 *   <li>{@code tm.bootstrap.default-tenant.language} — ISO 639-1 language tag for the bootstrapped
 *       default tenant. Default: {@code "de"} (system default per Brief C-15). Override via env var
 *       {@code TM_BOOTSTRAP_DEFAULT_TENANT_LANGUAGE}.
 * </ul>
 *
 * <h2>Validation</h2>
 *
 * <p>Both properties are validated at bean-construction time via the setter (fail-fast strategy per
 * AC-DISPLAY-NAME-VALIDATION-FAIL-FAST-OR-FALLBACK and
 * AC-LANGUAGE-VALIDATION-FAIL-FAST-OR-FALLBACK). Null, empty, or whitespace-only values throw
 * {@link IllegalArgumentException}. Deeper ISO-639-1 conformance is NOT validated here — locale
 * resolution is E46S02's concern.
 *
 * <h2>Precedent</h2>
 *
 * <p>Pattern mirrors {@link TmDataDirProperties} in the same package
 * ({@code @ConfigurationProperties(prefix = "tm.data")}, line 37 of that class).
 *
 * @see DefaultTenantBootstrapRunner
 * @see TmDataDirProperties
 * @see <a href="../../../../../../../../docs/governance/stories/E46S05.story.md">Story E46S05</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-35.md">DEC-35</a>
 */
@Component
@ConfigurationProperties(prefix = "tm.bootstrap.default-tenant")
public class TmBootstrapProperties {

    /**
     * Display name for the default tenant bootstrapped on first start.
     *
     * <p>Default: {@code "ToM Tournament Manager (LAN)"}. Override via env var {@code
     * TM_BOOTSTRAP_DEFAULT_TENANT_DISPLAY_NAME} or Spring property {@code
     * tm.bootstrap.default-tenant.display-name}.
     */
    private String displayName = "ToM Tournament Manager (LAN)";

    /**
     * Language tag for the default tenant bootstrapped on first start.
     *
     * <p>Default: {@code "de"} (system default per Brief C-15). Override via env var {@code
     * TM_BOOTSTRAP_DEFAULT_TENANT_LANGUAGE} or Spring property {@code
     * tm.bootstrap.default-tenant.language}.
     */
    private String language = "de";

    /**
     * Returns the configured display name.
     *
     * @return the display name; never {@code null}
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Sets the display name. Rejects null, empty, and whitespace-only values (fail-fast per
     * AC-DISPLAY-NAME-VALIDATION-FAIL-FAST-OR-FALLBACK).
     *
     * @param displayName the new display name
     * @throws IllegalArgumentException if the value is null, empty, or whitespace-only
     */
    public void setDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException(
                    "tm.bootstrap.default-tenant.display-name must not be null, empty, or"
                            + " whitespace-only. Rejected value: '"
                            + displayName
                            + "'. Set a non-blank display name or remove the property to use the"
                            + " default 'ToM Tournament Manager (LAN)'.");
        }
        this.displayName = displayName;
    }

    /**
     * Returns the configured language tag.
     *
     * @return the language tag; never {@code null}
     */
    public String getLanguage() {
        return language;
    }

    /**
     * Sets the language tag. Rejects null, empty, and whitespace-only values (fail-fast per
     * AC-LANGUAGE-VALIDATION-FAIL-FAST-OR-FALLBACK). Deeper ISO-639-1 conformance is NOT validated
     * here — locale resolution is E46S02's concern.
     *
     * @param language the new language tag
     * @throws IllegalArgumentException if the value is null, empty, or whitespace-only
     */
    public void setLanguage(String language) {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException(
                    "tm.bootstrap.default-tenant.language must not be null, empty, or"
                            + " whitespace-only. Rejected value: '"
                            + language
                            + "'. Set a non-blank language tag or remove the property to use the"
                            + " default 'de'.");
        }
        this.language = language;
    }
}
