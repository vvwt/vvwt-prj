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
            // E06S04 register page keys
            messageSource.addMessage("score.register.title",             locale, "Test Register Title");
            messageSource.addMessage("score.register.heading",           locale, "Test Register Heading");
            messageSource.addMessage("score.register.registering",       locale, "Registering...");
            messageSource.addMessage("score.register.waiting",           locale, "Waiting...");
            messageSource.addMessage("score.register.pin.instruction",   locale, "Tell PIN to organizer");
            messageSource.addMessage("score.register.polling",           locale, "Being assigned...");
            messageSource.addMessage("score.register.error.registration", locale, "Registration failed.");
            messageSource.addMessage("score.register.error.network",     locale, "Network error.");
            messageSource.addMessage("score.register.retry",             locale, "Retry");
            messageSource.addMessage("score.register.version.label",     locale, "Version");
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

    // =========================================================================
    // E06S04 — Registration page unit tests
    // =========================================================================

    // -----------------------------------------------------------------------
    // AC1: registerPage returns correct Mustache view name
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("E06S04 AC1: registerPage returns view name 'score/register'")
    void registerPageReturnsCorrectViewName() {
        Model model = new ConcurrentModel();
        String viewName = controller.registerPage(model);
        assertThat(viewName).isEqualTo("score/register");
    }

    // -----------------------------------------------------------------------
    // AC2: Model contains i18n strings for PIN instruction and headings
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("E06S04 AC2: model contains title from MessageSource")
    void registerPage_modelContainsTitle() {
        Model model = new ConcurrentModel();
        controller.registerPage(model);
        assertThat(model.getAttribute("title")).isEqualTo("Test Register Title");
    }

    @Test
    @DisplayName("E06S04 AC2: model contains heading from MessageSource")
    void registerPage_modelContainsHeading() {
        Model model = new ConcurrentModel();
        controller.registerPage(model);
        assertThat(model.getAttribute("heading")).isEqualTo("Test Register Heading");
    }

    @Test
    @DisplayName("E06S04 AC2: model contains PIN instruction from MessageSource")
    void registerPage_modelContainsPinInstruction() {
        Model model = new ConcurrentModel();
        controller.registerPage(model);
        assertThat(model.getAttribute("msgPinInstruction")).isEqualTo("Tell PIN to organizer");
    }

    @Test
    @DisplayName("E06S04 AC2: model contains registering message from MessageSource")
    void registerPage_modelContainsRegisteringMsg() {
        Model model = new ConcurrentModel();
        controller.registerPage(model);
        assertThat(model.getAttribute("msgRegistering")).isEqualTo("Registering...");
    }

    @Test
    @DisplayName("E06S04 AC2: model contains waiting message from MessageSource")
    void registerPage_modelContainsWaitingMsg() {
        Model model = new ConcurrentModel();
        controller.registerPage(model);
        assertThat(model.getAttribute("msgWaiting")).isEqualTo("Waiting...");
    }

    @Test
    @DisplayName("E06S04 AC8: model contains error messages from MessageSource")
    void registerPage_modelContainsErrorMessages() {
        Model model = new ConcurrentModel();
        controller.registerPage(model);
        assertThat(model.getAttribute("msgErrorReg")).isEqualTo("Registration failed.");
        assertThat(model.getAttribute("msgErrorNet")).isEqualTo("Network error.");
    }

    @Test
    @DisplayName("E06S04 AC8: model contains retry button label from MessageSource")
    void registerPage_modelContainsRetryLabel() {
        Model model = new ConcurrentModel();
        controller.registerPage(model);
        assertThat(model.getAttribute("msgRetry")).isEqualTo("Retry");
    }

    // -----------------------------------------------------------------------
    // AC9: Device token must NOT be present in model
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("E06S04 AC9: model does NOT contain deviceToken attribute")
    void registerPage_modelDoesNotContainDeviceToken() {
        Model model = new ConcurrentModel();
        controller.registerPage(model);
        assertThat(model.asMap()).doesNotContainKey("deviceToken");
        assertThat(model.asMap()).doesNotContainKey("device_token");
        assertThat(model.asMap()).doesNotContainKey("token");
    }

    // -----------------------------------------------------------------------
    // AC10: Missing i18n key for register page falls back to default
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("E06S04 AC10: missing MessageSource key for register page falls back to default without exception")
    void registerPage_missingMessageKeyFallsBackToDefault() {
        MessageSource emptySource = new StaticMessageSource();
        ScoreController controllerWithEmptySource = new ScoreController(emptySource, null);
        Model model = new ConcurrentModel();

        // Must not throw — falls back to the hardcoded default strings
        controllerWithEmptySource.registerPage(model);

        assertThat(model.getAttribute("title")).isEqualTo("Scoring Tablet — Registration");
        assertThat(model.getAttribute("heading")).isEqualTo("Scoring Tablet");
        assertThat(model.getAttribute("msgRetry")).isEqualTo("Try again");
    }

    // =========================================================================
    // E06S06 — Score-entry page (field page) unit tests
    // =========================================================================

    private void registerFieldPageMessages() {
        Locale jvmDefault = Locale.getDefault();
        for (Locale locale : new Locale[]{Locale.ROOT, Locale.ENGLISH, jvmDefault}) {
            messageSource.addMessage("score.field.title",               locale, "Test Field Title");
            messageSource.addMessage("score.field.heading",             locale, "Scoring Tablet");
            messageSource.addMessage("score.field.field.label",         locale, "Field");
            messageSource.addMessage("score.field.no.match",            locale, "No match");
            messageSource.addMessage("score.field.lap.label",           locale, "Round");
            messageSource.addMessage("score.field.set.label",           locale, "Set");
            messageSource.addMessage("score.field.vs.label",            locale, "vs.");
            messageSource.addMessage("score.field.referee.label",       locale, "Referee");
            messageSource.addMessage("score.field.team1.label",         locale, "Team 1");
            messageSource.addMessage("score.field.team2.label",         locale, "Team 2");
            messageSource.addMessage("score.field.plus.label",          locale, "+");
            messageSource.addMessage("score.field.minus.label",         locale, "-");
            messageSource.addMessage("score.field.confirm.heading",     locale, "Confirm set result");
            messageSource.addMessage("score.field.confirm.prompt",      locale, "Final score?");
            messageSource.addMessage("score.field.confirm.yes",         locale, "Confirm");
            messageSource.addMessage("score.field.confirm.no",          locale, "Cancel");
            messageSource.addMessage("score.field.loading",             locale, "Loading...");
            messageSource.addMessage("score.field.error.network",       locale, "Network error.");
            messageSource.addMessage("score.field.error.token",         locale, "Token error.");
            messageSource.addMessage("score.field.error.forbidden",     locale, "Forbidden.");
            messageSource.addMessage("score.field.error.validation",    locale, "Validation error.");
            messageSource.addMessage("score.field.submit.success",      locale, "Saved.");
            messageSource.addMessage("score.field.queue.pending",       locale, "Saving...");
            messageSource.addMessage("score.field.queue.saved",         locale, "Saved.");
            messageSource.addMessage("score.field.version.label",       locale, "Version");
        }
    }

    @Test
    @DisplayName("E06S06 AC2: fieldPage returns view name 'score/field'")
    void fieldPage_returnsCorrectViewName() {
        registerFieldPageMessages();
        Model model = new ConcurrentModel();
        String viewName = controller.fieldPage(1, model);
        assertThat(viewName).isEqualTo("score/field");
    }

    @Test
    @DisplayName("E06S06 AC2: fieldPage model contains fieldNumber attribute")
    void fieldPage_modelContainsFieldNumber() {
        registerFieldPageMessages();
        Model model = new ConcurrentModel();
        controller.fieldPage(3, model);
        assertThat(model.getAttribute("fieldNumber")).isEqualTo(3);
    }

    @Test
    @DisplayName("E06S06 AC13: fieldPage model contains i18n title from MessageSource")
    void fieldPage_modelContainsTitle() {
        registerFieldPageMessages();
        Model model = new ConcurrentModel();
        controller.fieldPage(1, model);
        assertThat(model.getAttribute("title")).isEqualTo("Test Field Title");
    }

    @Test
    @DisplayName("E06S06 AC9: fieldPage model contains no-match message from MessageSource")
    void fieldPage_modelContainsNoMatchMsg() {
        registerFieldPageMessages();
        Model model = new ConcurrentModel();
        controller.fieldPage(1, model);
        assertThat(model.getAttribute("msgNoMatch")).isEqualTo("No match");
    }

    @Test
    @DisplayName("E06S06 AC5: fieldPage model contains confirm dialog strings")
    void fieldPage_modelContainsConfirmStrings() {
        registerFieldPageMessages();
        Model model = new ConcurrentModel();
        controller.fieldPage(1, model);
        assertThat(model.getAttribute("msgConfirmHeading")).isEqualTo("Confirm set result");
        assertThat(model.getAttribute("msgConfirmYes")).isEqualTo("Confirm");
        assertThat(model.getAttribute("msgConfirmNo")).isEqualTo("Cancel");
    }

    @Test
    @DisplayName("E06S06 AC13: fieldPage falls back to default when MessageSource key missing")
    void fieldPage_missingKeyFallsBackToDefault() {
        MessageSource emptySource = new StaticMessageSource();
        ScoreController controllerWithEmptySource = new ScoreController(emptySource, null);
        Model model = new ConcurrentModel();

        // Must not throw
        controllerWithEmptySource.fieldPage(2, model);

        assertThat(model.getAttribute("fieldNumber")).isEqualTo(2);
        assertThat(model.getAttribute("heading")).isEqualTo("Scoring Tablet");
    }

    // =========================================================================
    // E06S08 — Idle-state i18n model attributes (AC1, AC3, AC4, AC5, AC6, AC7, AC10)
    // =========================================================================

    private void registerIdleStateMessages() {
        Locale jvmDefault = Locale.getDefault();
        for (Locale locale : new Locale[]{Locale.ROOT, Locale.ENGLISH, jvmDefault}) {
            messageSource.addMessage("score.field.idle.waiting.lap",          locale, "Waiting for next round...");
            messageSource.addMessage("score.field.idle.no.match",             locale, "No match on this field.");
            messageSource.addMessage("score.field.idle.phase.transition",     locale, "Phase complete. Waiting...");
            messageSource.addMessage("score.field.idle.tournament.complete",  locale, "Tournament complete.");
            messageSource.addMessage("score.field.idle.no.tournament",        locale, "No active tournament.");
            messageSource.addMessage("score.field.idle.connection.lost",      locale, "Connection lost — reconnecting...");
        }
    }

    @Test
    @DisplayName("E06S08 AC1/AC10: fieldPage model contains msgWaitingLap from MessageSource")
    void fieldPage_modelContainsMsgWaitingLap() {
        registerFieldPageMessages();
        registerIdleStateMessages();
        Model model = new ConcurrentModel();
        controller.fieldPage(1, model);
        assertThat(model.getAttribute("msgWaitingLap")).isEqualTo("Waiting for next round...");
    }

    @Test
    @DisplayName("E06S08 AC3/AC10: fieldPage model contains msgNoMatchField from MessageSource")
    void fieldPage_modelContainsMsgNoMatchField() {
        registerFieldPageMessages();
        registerIdleStateMessages();
        Model model = new ConcurrentModel();
        controller.fieldPage(1, model);
        assertThat(model.getAttribute("msgNoMatchField")).isEqualTo("No match on this field.");
    }

    @Test
    @DisplayName("E06S08 AC4/AC10: fieldPage model contains msgPhaseTransition from MessageSource")
    void fieldPage_modelContainsMsgPhaseTransition() {
        registerFieldPageMessages();
        registerIdleStateMessages();
        Model model = new ConcurrentModel();
        controller.fieldPage(1, model);
        assertThat(model.getAttribute("msgPhaseTransition")).isEqualTo("Phase complete. Waiting...");
    }

    @Test
    @DisplayName("E06S08 AC5/AC10: fieldPage model contains msgTournamentComplete from MessageSource")
    void fieldPage_modelContainsMsgTournamentComplete() {
        registerFieldPageMessages();
        registerIdleStateMessages();
        Model model = new ConcurrentModel();
        controller.fieldPage(1, model);
        assertThat(model.getAttribute("msgTournamentComplete")).isEqualTo("Tournament complete.");
    }

    @Test
    @DisplayName("E06S08 AC6/AC10: fieldPage model contains msgNoActiveTournament from MessageSource")
    void fieldPage_modelContainsMsgNoActiveTournament() {
        registerFieldPageMessages();
        registerIdleStateMessages();
        Model model = new ConcurrentModel();
        controller.fieldPage(1, model);
        assertThat(model.getAttribute("msgNoActiveTournament")).isEqualTo("No active tournament.");
    }

    @Test
    @DisplayName("E06S08 AC7/AC10: fieldPage model contains msgConnectionLost from MessageSource")
    void fieldPage_modelContainsMsgConnectionLost() {
        registerFieldPageMessages();
        registerIdleStateMessages();
        Model model = new ConcurrentModel();
        controller.fieldPage(1, model);
        assertThat(model.getAttribute("msgConnectionLost")).isEqualTo("Connection lost — reconnecting...");
    }

    @Test
    @DisplayName("E06S08 AC10: idle-state messages fall back to defaults when MessageSource missing")
    void fieldPage_idleMessages_fallBackToDefaults() {
        MessageSource emptySource = new StaticMessageSource();
        ScoreController controllerWithEmptySource = new ScoreController(emptySource, null);
        Model model = new ConcurrentModel();

        // Must not throw
        controllerWithEmptySource.fieldPage(1, model);

        assertThat(model.getAttribute("msgWaitingLap")).isEqualTo("Waiting for next round\u2026");
        assertThat(model.getAttribute("msgTournamentComplete")).isEqualTo("Tournament complete.");
        assertThat(model.getAttribute("msgConnectionLost"))
                .isEqualTo("Connection lost \u2014 reconnecting\u2026");
    }
}
