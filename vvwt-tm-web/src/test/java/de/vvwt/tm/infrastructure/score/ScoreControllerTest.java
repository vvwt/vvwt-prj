package de.vvwt.tm.infrastructure.score;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScoreController}.
 *
 * <p>Story E06S02 — AC3, AC9, AC10.
 * Tests are isolated (no Spring context) to verify model population, view name,
 * and i18n message resolution independently of infrastructure.
 */
@DisplayName("ScoreController unit tests")
class ScoreControllerTest {

    private ScoreController controller;
    private StaticMessageSource messageSource;

    @BeforeEach
    void setUp() {
        messageSource = new StaticMessageSource();
        // Register messages for the JVM default locale (may be de_DE in CI/local), ROOT, and ENGLISH
        // to ensure the controller's MessageSource.getMessage() finds the key regardless of locale.
        Locale jvmDefault = Locale.getDefault();
        for (Locale locale : new Locale[]{Locale.ROOT, Locale.ENGLISH, jvmDefault}) {
            messageSource.addMessage("score.hello.title",         locale, "Test Title");
            messageSource.addMessage("score.hello.heading",       locale, "Test Heading");
            messageSource.addMessage("score.hello.description",   locale, "Test Description");
            messageSource.addMessage("score.hello.version.label", locale, "Version");
        }

        controller = new ScoreController(messageSource, null);
    }

    // -----------------------------------------------------------------------
    // AC3: Route returns correct Mustache view name
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3: helloWorld returns view name 'score/hello'")
    void helloWorldReturnsCorrectViewName() {
        Model model = new ConcurrentModel();
        String viewName = controller.helloWorld(model);
        assertThat(viewName).isEqualTo("score/hello");
    }

    // -----------------------------------------------------------------------
    // AC10: Model is populated from MessageSource
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC10: model contains title from MessageSource")
    void modelContainsTitleFromMessageSource() {
        Model model = new ConcurrentModel();
        controller.helloWorld(model);
        assertThat(model.getAttribute("title")).isEqualTo("Test Title");
    }

    @Test
    @DisplayName("AC10: model contains heading from MessageSource")
    void modelContainsHeadingFromMessageSource() {
        Model model = new ConcurrentModel();
        controller.helloWorld(model);
        assertThat(model.getAttribute("heading")).isEqualTo("Test Heading");
    }

    @Test
    @DisplayName("AC10: model contains description from MessageSource")
    void modelContainsDescriptionFromMessageSource() {
        Model model = new ConcurrentModel();
        controller.helloWorld(model);
        assertThat(model.getAttribute("description")).isEqualTo("Test Description");
    }

    @Test
    @DisplayName("AC10: model contains versionLabel from MessageSource")
    void modelContainsVersionLabelFromMessageSource() {
        Model model = new ConcurrentModel();
        controller.helloWorld(model);
        assertThat(model.getAttribute("versionLabel")).isEqualTo("Version");
    }

    @Test
    @DisplayName("AC10: model contains appVersion (defaults to 'dev' without BuildProperties)")
    void modelContainsAppVersionDefault() {
        Model model = new ConcurrentModel();
        controller.helloWorld(model);
        assertThat(model.getAttribute("appVersion")).isEqualTo("dev");
    }

    @Test
    @DisplayName("AC10: model contains locale attribute")
    void modelContainsLocale() {
        Model model = new ConcurrentModel();
        controller.helloWorld(model);
        assertThat(model.getAttribute("locale")).isNotNull();
    }

    // -----------------------------------------------------------------------
    // AC10: Missing i18n key falls back to default (no exception)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC10: missing MessageSource key falls back to default without exception")
    void missingMessageKeyFallsBackToDefault() {
        MessageSource emptySource = new StaticMessageSource();
        ScoreController controllerWithEmptySource = new ScoreController(emptySource, null);
        Model model = new ConcurrentModel();

        // Must not throw — falls back to the hardcoded default strings
        controllerWithEmptySource.helloWorld(model);

        assertThat(model.getAttribute("title")).isEqualTo("Scoring Tablet");
        assertThat(model.getAttribute("heading")).isEqualTo("Scoring Tablet");
    }
}
