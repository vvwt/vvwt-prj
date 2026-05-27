// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc slice for {@link ScoreController} (E22S10, DEC-38 Clause A / DEC-40 Clause E).
 *
 * <h2>Why standalone (not {@code @WebMvcTest})</h2>
 *
 * <p>The scoring tablet endpoints are {@code permitAll} per {@code SecurityConfig}. A
 * {@code @WebMvcTest} context loads the default Spring Security auto-configuration which requires
 * authentication for all routes — a separate {@code SecurityConfig} bean would be needed just for
 * the slice. Standalone MockMvc avoids this overhead: {@link ScoreController} only depends on
 * {@link MessageSource} (mocked) and {@link org.springframework.boot.info.BuildProperties}
 * (optional, tested via null-safe constructor). No collaborator access from other Modulith modules.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>AC-VIEW-NAME-PARITY: view-name returns are byte-equivalent to legacy
 *   <li>AC-MODEL-ATTRIBUTE-PARITY: model attribute KEYS match legacy golden fixture
 *   <li>AC-MESSAGESOURCE-FALLBACK: fallback on {@link NoSuchMessageException}
 *   <li>AC-SECURITY-DEVICE-REGISTRATION: {@code deviceToken} / {@code token} absent from model
 *   <li>AC-INVALID-DEVICE-TOKEN-REDIRECT: {@code /score/field/{n}} renders without token (HTTP 200,
 *       no server-side validation)
 * </ul>
 *
 * <h2>DEC-36 compliance</h2>
 *
 * <p>This test class is in package {@code de.vvwt.tm.web} (same package as the subject), so
 * white-box access to {@link ScoreController} is permitted per DEC-36 same-package rule. The {@link
 * MessageSource} collaborator is mocked via Mockito directly.
 *
 * @see ScoreController
 * @see ScoreControllerIT
 * @see <a href="DEC-19">DEC-19 — Mustache + ES5 carve-out</a>
 * @see <a href="DEC-36">DEC-36 — Cross-package test typing (same-package white-box exemption)</a>
 * @see <a href="DEC-38">DEC-38 — {@code @ApplicationModuleTest} canon (IT complement)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S10">E22S10</a>
 */
@DisplayName("ScoreController slice tests — E22S10 AC-VIEW-NAME-PARITY + AC-MODEL-ATTRIBUTE-PARITY")
class ScoreControllerSliceTest {

    private MockMvc mockMvc;
    private MessageSource messageSource;

    @BeforeEach
    void setUp() {
        messageSource = Mockito.mock(MessageSource.class);
        // Default stub: return null (msg() helper falls back to hardcoded default)
        ScoreController controller = new ScoreController(messageSource, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // =========================================================================
    // AC-VIEW-NAME-PARITY: view-name returns are byte-equivalent to legacy
    // =========================================================================

    @Test
    @DisplayName("AC-VIEW-NAME-PARITY: GET /score/test returns view 'score/hello'")
    void helloWorld_returnsCorrectViewName() throws Exception {
        mockMvc.perform(get("/score/test"))
                .andExpect(status().isOk())
                .andExpect(view().name("score/hello"));
    }

    @Test
    @DisplayName("AC-VIEW-NAME-PARITY: GET /score/register returns view 'score/register'")
    void registerPage_returnsCorrectViewName() throws Exception {
        mockMvc.perform(get("/score/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("score/register"));
    }

    @Test
    @DisplayName("AC-VIEW-NAME-PARITY: GET /score/field/1 returns view 'score/field'")
    void fieldPage_returnsCorrectViewName() throws Exception {
        mockMvc.perform(get("/score/field/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("score/field"));
    }

    // =========================================================================
    // AC-MODEL-ATTRIBUTE-PARITY: model attribute KEYS match legacy golden fixture
    // =========================================================================

    /**
     * Golden fixture for {@code helloWorld} model attribute keys (enumerated from legacy {@code
     * de.vvwt.tm.infrastructure.score.ScoreController#helloWorld}).
     *
     * <p>E22S10 AC-MODEL-ATTRIBUTE-PARITY: key set must be byte-equal to legacy. Any added or
     * removed key blocks story closure.
     */
    private static final Set<String> HELLO_WORLD_KEYS =
            Set.of("locale", "title", "heading", "description", "versionLabel", "appVersion");

    /** Golden fixture for {@code registerPage} model attribute keys (enumerated from legacy). */
    private static final Set<String> REGISTER_PAGE_KEYS =
            Set.of(
                    "locale",
                    "title",
                    "heading",
                    "msgRegistering",
                    "msgWaiting",
                    "msgPinInstruction",
                    "msgPolling",
                    "msgErrorReg",
                    "msgErrorNet",
                    "msgRetry",
                    "versionLabel",
                    "appVersion");

    /** Golden fixture for {@code fieldPage} model attribute keys (enumerated from legacy). */
    private static final Set<String> FIELD_PAGE_KEYS =
            Set.of(
                    "locale",
                    "fieldNumber",
                    "title",
                    "heading",
                    "msgFieldLabel",
                    "msgNoMatch",
                    "msgLapLabel",
                    "msgSetLabel",
                    "msgVsLabel",
                    "msgRefereeLabel",
                    "msgTeam1Label",
                    "msgTeam2Label",
                    "msgPlusLabel",
                    "msgMinusLabel",
                    "msgConfirmHeading",
                    "msgConfirmPrompt",
                    "msgConfirmYes",
                    "msgConfirmNo",
                    "msgLoading",
                    "msgErrorNet",
                    "msgErrorToken",
                    "msgErrorForbidden",
                    "msgErrorValidation",
                    "msgSubmitSuccess",
                    "msgQueuePending",
                    "msgQueueSaved",
                    "versionLabel",
                    "appVersion",
                    // AC1/AC8 (E61S03): swap control label added
                    "msgSwapLabel",
                    // AC3 (E65S07): tap-to-refresh label added
                    "msgTapToRefresh");

    @Test
    @DisplayName("AC-MODEL-ATTRIBUTE-PARITY: helloWorld model contains exactly the legacy key set")
    void helloWorld_modelContainsGoldenKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/score/test")).andReturn();
        Set<String> actualKeys = extractUserKeys(result.getModelAndView().getModel());
        assertThat(actualKeys)
                .as(
                        "helloWorld model keys must exactly match legacy golden fixture"
                                + " (AC-MODEL-ATTRIBUTE-PARITY)")
                .containsExactlyInAnyOrderElementsOf(HELLO_WORLD_KEYS);
    }

    @Test
    @DisplayName(
            "AC-MODEL-ATTRIBUTE-PARITY: registerPage model contains exactly the legacy key set")
    void registerPage_modelContainsGoldenKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/score/register")).andReturn();
        Set<String> actualKeys = extractUserKeys(result.getModelAndView().getModel());
        assertThat(actualKeys)
                .as(
                        "registerPage model keys must exactly match legacy golden fixture"
                                + " (AC-MODEL-ATTRIBUTE-PARITY)")
                .containsExactlyInAnyOrderElementsOf(REGISTER_PAGE_KEYS);
    }

    @Test
    @DisplayName("AC-MODEL-ATTRIBUTE-PARITY: fieldPage model contains exactly the legacy key set")
    void fieldPage_modelContainsGoldenKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/score/field/2")).andReturn();
        Set<String> actualKeys = extractUserKeys(result.getModelAndView().getModel());
        assertThat(actualKeys)
                .as(
                        "fieldPage model keys must exactly match legacy golden fixture"
                                + " (AC-MODEL-ATTRIBUTE-PARITY)")
                .containsExactlyInAnyOrderElementsOf(FIELD_PAGE_KEYS);
    }

    @Test
    @DisplayName("AC-MODEL-ATTRIBUTE-PARITY: fieldPage sets fieldNumber from path variable")
    void fieldPage_setsFieldNumberFromPathVariable() throws Exception {
        MvcResult result = mockMvc.perform(get("/score/field/7")).andReturn();
        Map<String, Object> model = result.getModelAndView().getModel();
        assertThat(model.get("fieldNumber"))
                .as("fieldNumber attribute must match path variable value")
                .isEqualTo(7);
    }

    // =========================================================================
    // AC-MESSAGESOURCE-FALLBACK: fallback on NoSuchMessageException
    // =========================================================================

    @Test
    @DisplayName(
            "AC-MESSAGESOURCE-FALLBACK: GET /score/test renders successfully when MessageSource"
                    + " throws NoSuchMessageException")
    void helloWorld_fallsBackGracefullyWhenMessageSourceThrows() throws Exception {
        // Stub: any getMessage() call throws NoSuchMessageException
        Mockito.when(
                        messageSource.getMessage(
                                Mockito.<String>any(),
                                Mockito.any(),
                                Mockito.anyString(),
                                Mockito.any(Locale.class)))
                .thenThrow(new NoSuchMessageException("any.key"));

        // Must not throw — msg() helper catches and returns fallback
        MvcResult result =
                mockMvc.perform(get("/score/test")).andExpect(status().isOk()).andReturn();
        // Model must contain the fallback value for "title"
        assertThat(result.getModelAndView().getModel().get("title"))
                .as("title must contain fallback when MessageSource throws")
                .isEqualTo("Scoring Tablet");
    }

    @Test
    @DisplayName(
            "AC-MESSAGESOURCE-FALLBACK: GET /score/register renders successfully when MessageSource"
                    + " throws NoSuchMessageException")
    void registerPage_fallsBackGracefullyWhenMessageSourceThrows() throws Exception {
        Mockito.when(
                        messageSource.getMessage(
                                Mockito.<String>any(),
                                Mockito.any(),
                                Mockito.anyString(),
                                Mockito.any(Locale.class)))
                .thenThrow(new NoSuchMessageException("any.key"));

        MvcResult result =
                mockMvc.perform(get("/score/register")).andExpect(status().isOk()).andReturn();
        assertThat(result.getModelAndView().getModel().get("title"))
                .as("title must contain fallback when MessageSource throws")
                .isEqualTo("Scoring Tablet — Registration");
    }

    // =========================================================================
    // AC-SECURITY-DEVICE-REGISTRATION: no token in model
    // =========================================================================

    @Test
    @DisplayName(
            "AC-SECURITY-DEVICE-REGISTRATION: registerPage model does NOT contain"
                    + " deviceToken / token attribute")
    void registerPage_doesNotExposeDeviceTokenInModel() throws Exception {
        MvcResult result = mockMvc.perform(get("/score/register")).andReturn();
        Map<String, Object> model = result.getModelAndView().getModel();
        assertThat(model)
                .as("model must not contain 'deviceToken' — AC-SECURITY-DEVICE-REGISTRATION")
                .doesNotContainKey("deviceToken");
        assertThat(model)
                .as("model must not contain 'device_token' — AC-SECURITY-DEVICE-REGISTRATION")
                .doesNotContainKey("device_token");
        assertThat(model)
                .as("model must not contain 'token' — AC-SECURITY-DEVICE-REGISTRATION")
                .doesNotContainKey("token");
    }

    // =========================================================================
    // AC-INVALID-DEVICE-TOKEN-REDIRECT: no server-side token validation
    // =========================================================================

    @Test
    @DisplayName(
            "AC-INVALID-DEVICE-TOKEN-REDIRECT: GET /score/field/{n} without deviceToken cookie"
                    + " returns 200 (no server-side validation)")
    void fieldPage_rendersWithoutDeviceToken() throws Exception {
        // No cookie — per legacy Javadoc: controller does NOT validate the token
        mockMvc.perform(get("/score/field/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("score/field"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Extract user-defined model keys, filtering out Spring-internal keys that start with {@code
     * "org.springframework"}.
     */
    private static Set<String> extractUserKeys(Map<String, Object> model) {
        return model.keySet().stream()
                .filter(k -> !k.startsWith("org.springframework"))
                .collect(Collectors.toSet());
    }
}
