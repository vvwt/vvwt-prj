package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TmBootstrapProperties} — E46S05 validation ACs.
 *
 * <p>DEC-22 Iron Law: all behavioral changes verified RED-first. DEC-41 observable-form: property
 * state and exception type/message are observable outputs.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>AC-DISPLAY-NAME-VALIDATION-FAIL-FAST-OR-FALLBACK — setDisplayName with null/empty/blank
 *   <li>AC-LANGUAGE-VALIDATION-FAIL-FAST-OR-FALLBACK — setLanguage with null/empty/blank
 *   <li>Default values for both properties
 * </ul>
 *
 * @see TmBootstrapProperties
 * @see <a href="../../../../../../../../docs/governance/stories/E46S05.story.md">Story E46S05</a>
 */
class TmBootstrapPropertiesTest {

    // =========================================================================
    // Default value tests
    // =========================================================================

    /** Default displayName is {@code "ToM Tournament Manager (LAN)"}. */
    @Test
    void defaultDisplayName_isProductBrandName() {
        TmBootstrapProperties props = new TmBootstrapProperties();
        assertThat(props.getDisplayName())
                .as("Default display name must be the product brand name")
                .isEqualTo("ToM Tournament Manager (LAN)");
    }

    /** Default language is {@code "de"} per Brief C-15. */
    @Test
    void defaultLanguage_isDe() {
        TmBootstrapProperties props = new TmBootstrapProperties();
        assertThat(props.getLanguage())
                .as("Default language must be 'de' per Brief C-15")
                .isEqualTo("de");
    }

    // =========================================================================
    // AC-DISPLAY-NAME-VALIDATION-FAIL-FAST-OR-FALLBACK
    // =========================================================================

    /**
     * AC-DISPLAY-NAME-VALIDATION-FAIL-FAST-OR-FALLBACK: setDisplayName with null throws {@link
     * IllegalArgumentException} with property name in message.
     */
    @Test
    void setDisplayName_null_throwsIllegalArgumentException() {
        TmBootstrapProperties props = new TmBootstrapProperties();
        assertThatThrownBy(() -> props.setDisplayName(null))
                .as("Null display name must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tm.bootstrap.default-tenant.display-name");
    }

    /**
     * AC-DISPLAY-NAME-VALIDATION-FAIL-FAST-OR-FALLBACK: setDisplayName with empty string throws.
     */
    @Test
    void setDisplayName_empty_throwsIllegalArgumentException() {
        TmBootstrapProperties props = new TmBootstrapProperties();
        assertThatThrownBy(() -> props.setDisplayName(""))
                .as("Empty display name must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tm.bootstrap.default-tenant.display-name");
    }

    /**
     * AC-DISPLAY-NAME-VALIDATION-FAIL-FAST-OR-FALLBACK: setDisplayName with whitespace-only string
     * throws.
     */
    @Test
    void setDisplayName_whitespaceOnly_throwsIllegalArgumentException() {
        TmBootstrapProperties props = new TmBootstrapProperties();
        assertThatThrownBy(() -> props.setDisplayName("   "))
                .as("Whitespace-only display name must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tm.bootstrap.default-tenant.display-name");
    }

    /** Valid display name is accepted without exception. */
    @Test
    void setDisplayName_validValue_accepted() {
        TmBootstrapProperties props = new TmBootstrapProperties();
        props.setDisplayName("My Club");
        assertThat(props.getDisplayName()).isEqualTo("My Club");
    }

    // =========================================================================
    // AC-LANGUAGE-VALIDATION-FAIL-FAST-OR-FALLBACK
    // =========================================================================

    /**
     * AC-LANGUAGE-VALIDATION-FAIL-FAST-OR-FALLBACK: setLanguage with null throws {@link
     * IllegalArgumentException} with property name in message.
     */
    @Test
    void setLanguage_null_throwsIllegalArgumentException() {
        TmBootstrapProperties props = new TmBootstrapProperties();
        assertThatThrownBy(() -> props.setLanguage(null))
                .as("Null language must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tm.bootstrap.default-tenant.language");
    }

    /** AC-LANGUAGE-VALIDATION-FAIL-FAST-OR-FALLBACK: setLanguage with empty string throws. */
    @Test
    void setLanguage_empty_throwsIllegalArgumentException() {
        TmBootstrapProperties props = new TmBootstrapProperties();
        assertThatThrownBy(() -> props.setLanguage(""))
                .as("Empty language must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tm.bootstrap.default-tenant.language");
    }

    /** AC-LANGUAGE-VALIDATION-FAIL-FAST-OR-FALLBACK: setLanguage with whitespace-only throws. */
    @Test
    void setLanguage_whitespaceOnly_throwsIllegalArgumentException() {
        TmBootstrapProperties props = new TmBootstrapProperties();
        assertThatThrownBy(() -> props.setLanguage("   "))
                .as("Whitespace-only language must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tm.bootstrap.default-tenant.language");
    }

    /** Valid language tag is accepted without exception. */
    @Test
    void setLanguage_validValue_accepted() {
        TmBootstrapProperties props = new TmBootstrapProperties();
        props.setLanguage("en");
        assertThat(props.getLanguage()).isEqualTo("en");
    }
}
