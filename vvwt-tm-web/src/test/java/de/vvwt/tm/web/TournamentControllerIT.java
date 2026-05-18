// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import java.net.URI;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Minimalist integration tests for {@link TournamentController} (E21S02,
 * AC-REST-IT-HAPPY-TournamentController + AC-REST-IT-SEC-TournamentController).
 *
 * <h2>Approach C minimalist-IT</h2>
 *
 * <p>Exactly 2 {@code @Test} methods per controller:
 *
 * <ol>
 *   <li>Happy-path authenticated POST → 201 + assertj-db independent DB verification (DEC-26 Rule
 *       2)
 *   <li>Unauthenticated request → 401 (security gate)
 * </ol>
 *
 * <p>Slice tests ({@link TournamentControllerSliceTest}) cover all other scenarios.
 *
 * <h2>assertj-db independent verifier (DEC-26 Rule 2)</h2>
 *
 * <p>The happy-path test reads the persisted row directly from the DataSource via assertj-db — not
 * via the service or repository. This confirms the write path reaches the DB independently of the
 * read path.
 *
 * <h2>Module scope (DEC-38/DEC-40 Clause E)</h2>
 *
 * <p>Relocated from {@code de.vvwt.tm.tournament} to {@code de.vvwt.tm.web} per DEC-40 Clause D
 * (E22S07, Q-1b whole-class relocation). The {@code @ApplicationModuleTest} annotation now resolves
 * {@code de.vvwt.tm.web} as the module under test (booting web + tenant + tournament +
 * tournament::exceptions + tournament::dto + scoring per {@code allowedDependencies}). {@link
 * WebModuleTestConfig} is used instead of {@link de.vvwt.tm.tournament.TournamentModuleTestConfig}
 * because Spring Modulith 1.4.6's {@code ModuleTestExecutionBeanDefinitionSelector} requires the
 * test config to reside in the module under test (DEC-38 Clause C).
 *
 * @see TournamentController
 * @see TournamentControllerSliceTest
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S07">E22S07 — Relocate TournamentController to de.vvwt.tm.web</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, TournamentControllerIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("TournamentController IT — E21S02 AC-REST-IT + E68S01 organizer")
class TournamentControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S02TournamentControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private DataSource dataSource;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);
    }

    // =========================================================================
    // Happy-path: authenticated POST → 201, row verified via assertj-db
    // =========================================================================

    @Test
    @DisplayName("authenticated POST /api/tournaments creates tournament; assertj-db verifies row")
    void authenticatedPostCreatesTournamentAndPersistsRow() throws Exception {
        var request =
                new TournamentCreateRequest(
                        "IT Hallenturnier E21S02",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        null,
                        null,
                        null, // E53S05: seedMannschaftsfoto = null
                        null); // E68S01: organizer = null → server derives from tenant display_name

        ResponseEntity<TournamentResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, TournamentResponse.class);

        assertThat(response.getStatusCode())
                .as("POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().description()).isEqualTo("IT Hallenturnier E21S02");
        assertThat(response.getBody().status()).isEqualTo("DRAFT");
        assertThat(response.getBody().id()).isNotNull();

        // DEC-26 Rule 2 — assertj-db independent verifier
        UUID newId = response.getBody().id();
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table tournamentTable = assertDb.table("tournament").build();
            List<Object> ids =
                    tournamentTable.getRowsList().stream()
                            .map(row -> row.getColumnValue("ID").getValue())
                            .toList();
            assertThat(ids)
                    .as("tournament table must contain the newly created tournament UUID")
                    .contains(newId);
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // Security: unauthenticated POST → 401
    // =========================================================================

    @Test
    @DisplayName("unauthenticated POST /api/tournaments returns 401")
    void unauthenticatedPostReturns401() throws Exception {
        var request =
                new TournamentCreateRequest(
                        "Unauthorized Tournament",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        null,
                        null,
                        null, // E53S05: seedMannschaftsfoto = null
                        null); // E68S01: organizer = null

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC-TEST-CREATE-WITH-PLANNED-START-TIME-IT-RED (E48S14)
    // RED-first: before production fix, plannedStartTime is silently dropped by all four layers.
    // After fix, POST with plannedStartTime persists the value; GET returns it.
    // =========================================================================

    @Test
    @DisplayName(
            "E48S14 AC-TEST-CREATE-WITH-PLANNED-START-TIME-IT-RED: "
                    + "POST with plannedStartTime='09:00' → 201; GET returns plannedStartTime set")
    void createWithPlannedStartTime_persistsAndReturnsStartTime() throws Exception {
        // Use raw Map to include plannedStartTime in the JSON body regardless of DTO state.
        // Before the fix: TournamentCreateRequest.java lacks the field → backend ignores it →
        // GET returns null → assertion FAILS (RED).
        // After the fix: all four layers wire the field → assertion PASSES (GREEN).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "E48S14 IT Tournament With Start Time");
        body.put("appointment", null);
        body.put("teamCount", 4);
        body.put("fieldCount", 2);
        body.put("matchFormat", "BEST_OF_3");
        body.put("scoringRuleId", "setPoints");
        body.put("setValidationRuleId", "standardVolleyball");
        body.put("matchGeneratorId", "roundRobin");
        body.put("plannedStartTime", "09:00");

        ResponseEntity<TournamentResponse> createResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), body, TournamentResponse.class);

        assertThat(createResponse.getStatusCode())
                .as("POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        UUID newId = createResponse.getBody().id();

        // Fetch the newly created tournament and verify plannedStartTime is persisted.
        ResponseEntity<TournamentResponse> getResponse =
                authed.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + newId), TournamentResponse.class);

        assertThat(getResponse.getStatusCode())
                .as("GET must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        // Jackson 3.x serializes LocalTime as ISO-8601 "HH:mm:ss" when timestamps are disabled
        // (confirmed: WRITE_DATES_AS_TIMESTAMPS disabled in JacksonConfig, E21S10).
        // TournamentResponse.plannedStartTime is a LocalTime field deserialized from the DB value
        // persisted by DefaultTournamentService.createTournament (E48S14 fix).
        assertThat(getResponse.getBody().plannedStartTime())
                .as("plannedStartTime must be persisted by CREATE (E48S14 fix)")
                .isEqualTo(LocalTime.of(9, 0));
    }

    // =========================================================================
    // AC-TEST-CREATE-WITHOUT-PLANNED-START-TIME-IT-GREEN (E48S14)
    // Complementary: POST without plannedStartTime → 201; GET returns plannedStartTime null.
    // Confirms field is optional and does not break existing CREATE callers.
    // =========================================================================

    @Test
    @DisplayName(
            "E48S14 AC-TEST-CREATE-WITHOUT-PLANNED-START-TIME-IT-GREEN: "
                    + "POST without plannedStartTime → 201; GET returns plannedStartTime null")
    void createWithoutPlannedStartTime_returnsNullStartTime() throws Exception {
        // This test should be GREEN both before and after the fix:
        // before: field is silently dropped (never set anyway) → null;
        // after: field is optional (null means "no start time") → null.
        var request =
                new TournamentCreateRequest(
                        "E48S14 IT Tournament Without Start Time",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        null,
                        null,
                        null, // E53S05: seedMannschaftsfoto = null
                        null); // E68S01: organizer = null

        ResponseEntity<TournamentResponse> createResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, TournamentResponse.class);

        assertThat(createResponse.getStatusCode())
                .as("POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        UUID newId = createResponse.getBody().id();

        ResponseEntity<TournamentResponse> getResponse =
                authed.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + newId), TournamentResponse.class);

        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().plannedStartTime())
                .as("plannedStartTime must be null when not provided")
                .isNull();
    }

    // =========================================================================
    // AC1 — E53S05: POST with seedMannschaftsfoto=true seeds ActivityType (RED-first)
    // AC9 — tenant isolation: seeded row lives in default tenant only
    // =========================================================================

    /**
     * E53S05 AC1 + AC9 — RED-first: when {@code seedMannschaftsfoto=true} is passed in the
     * tournament-creation request, a {@code Mannschaftsfoto} {@code ActivityType} with {@code
     * assignment_rule = FIRST_FREE_ROUND} is created for the tournament in the current tenant.
     *
     * <p>RED before E53S05: {@code TournamentCreateRequest} lacks the field → {@code
     * seedMannschaftsfoto} is silently ignored → no row in {@code activity_types} → assertj-db
     * assertion fails.
     *
     * <p>GREEN after E53S05: all four layers wire the field → service seeds the row → assertion
     * passes.
     *
     * <p>DEC-26 Rule 2: DB state verified via assertj-db, not via a subsequent GET.
     *
     * <p>AC9 (tenant isolation): the seeded row lives in the default-tenant DB only.
     *
     * @see de.vvwt.tm.tournament.activity.ActivityTypeService
     * @see <a href="E53S05">E53S05 — Mannschaftsfoto Vorbelegung</a>
     */
    @Test
    @DisplayName(
            "E53S05 AC1+AC9: POST with seedMannschaftsfoto=true seeds FIRST_FREE_ROUND ActivityType"
                    + " via assertj-db")
    void createWithSeedMannschaftsfoto_true_seedsActivityType() throws Exception {
        // Use raw Map so the JSON field is sent regardless of DTO state.
        // Before fix: TournamentCreateRequest lacks seedMannschaftsfoto → ignored.
        // After fix: field wired through all layers → service seeds ActivityType.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "E53S05 IT Mannschaftsfoto seed test");
        body.put("appointment", null);
        body.put("teamCount", 4);
        body.put("fieldCount", 2);
        body.put("matchFormat", "BEST_OF_3");
        body.put("scoringRuleId", "setPoints");
        body.put("setValidationRuleId", "standardVolleyball");
        body.put("matchGeneratorId", "roundRobin");
        body.put("plannedStartTime", null);
        body.put("optimize", null);
        body.put("seedMannschaftsfoto", true); // the new field (E53S05 AC1)

        ResponseEntity<TournamentResponse> createResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), body, TournamentResponse.class);

        assertThat(createResponse.getStatusCode())
                .as("POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        UUID newId = createResponse.getBody().id();

        // DEC-26 Rule 2 — assertj-db independent verifier (not via service or GET)
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table activityTypesTable = assertDb.table("activity_types").build();

            // Collect assignment_rule values for this tournament
            List<Object> assignmentRules =
                    activityTypesTable.getRowsList().stream()
                            .filter(
                                    row ->
                                            newId.equals(
                                                    row.getColumnValue("TOURNAMENT_ID").getValue()))
                            .map(row -> row.getColumnValue("ASSIGNMENT_RULE").getValue())
                            .toList();

            assertThat(assignmentRules)
                    .as(
                            "E53S05 AC1: activity_types must contain exactly one FIRST_FREE_ROUND"
                                    + " row for the new tournament")
                    .hasSize(1);
            assertThat(assignmentRules.get(0))
                    .as("E53S05 AC1: assignment_rule must be FIRST_FREE_ROUND")
                    .isEqualTo("FIRST_FREE_ROUND");
        } finally {
            tenantBinder.unbind();
        }
    }

    /**
     * E53S05 AC4 / AC10 — RED-first: when {@code seedMannschaftsfoto=false} is passed, NO {@code
     * ActivityType} is seeded, and the print-index later renders without the
     * Mannschaftsfoto-Zeitplan link (E53S03 empty-state UX preserved).
     *
     * <p>RED before E53S05: field is unknown → ignored by server → by coincidence no seed happens
     * (no pre-existing seed mechanism) → assertion PASSES trivially (GREEN for wrong reason). After
     * E53S05: field is respected → opt-out path verified: no row in {@code activity_types}.
     *
     * @see <a href="E53S05">E53S05 — AC4 opt-out path</a>
     */
    @Test
    @DisplayName("E53S05 AC4: POST with seedMannschaftsfoto=false → no ActivityType row in DB")
    void createWithSeedMannschaftsfoto_false_noActivityTypeSeeded() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "E53S05 IT opt-out test");
        body.put("appointment", null);
        body.put("teamCount", 4);
        body.put("fieldCount", 2);
        body.put("matchFormat", "BEST_OF_3");
        body.put("scoringRuleId", "setPoints");
        body.put("setValidationRuleId", "standardVolleyball");
        body.put("matchGeneratorId", "roundRobin");
        body.put("plannedStartTime", null);
        body.put("optimize", null);
        body.put("seedMannschaftsfoto", false); // opt-out: no seeding

        ResponseEntity<TournamentResponse> createResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), body, TournamentResponse.class);

        assertThat(createResponse.getStatusCode())
                .as("POST must return 201 Created even when seedMannschaftsfoto=false")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        UUID newId = createResponse.getBody().id();

        // DEC-26 Rule 2 — assertj-db verifier: NO activity_types row for this tournament
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table activityTypesTable = assertDb.table("activity_types").build();

            long rowCount =
                    activityTypesTable.getRowsList().stream()
                            .filter(
                                    row ->
                                            newId.equals(
                                                    row.getColumnValue("TOURNAMENT_ID").getValue()))
                            .count();

            assertThat(rowCount)
                    .as(
                            "E53S05 AC4: no activity_types row must exist for tournament when"
                                    + " seedMannschaftsfoto=false")
                    .isZero();
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // E68S01: organizer persisted on CREATE (AC1 + AC7)
    // =========================================================================

    /**
     * E68S01 AC1 + AC7 RED-first: POST with {@code organizer="Custom Organizer"} → 201; assertj-db
     * verifies organizer column in tournament row.
     *
     * <p>RED before E68S01: {@code TournamentCreateRequest} lacks {@code organizer} field → backend
     * ignores it → organizer is derived from tenant display_name → assertion on "Custom Organizer"
     * FAILS. GREEN after: all layers wire the field → organizer persisted as supplied.
     */
    @Test
    @DisplayName(
            "E68S01 AC1+AC7: POST with organizer='Custom Organizer' → 201; assertj-db verifies"
                    + " organizer column persisted")
    void createWithOrganizer_persistsOrganizer() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "E68S01 IT Create With Organizer");
        body.put("appointment", null);
        body.put("teamCount", 4);
        body.put("fieldCount", 2);
        body.put("matchFormat", "BEST_OF_3");
        body.put("scoringRuleId", "setPoints");
        body.put("setValidationRuleId", "standardVolleyball");
        body.put("matchGeneratorId", "roundRobin");
        body.put("plannedStartTime", null);
        body.put("optimize", null);
        body.put("seedMannschaftsfoto", null);
        body.put("organizer", "Custom Organizer"); // E68S01: explicit organizer

        ResponseEntity<TournamentResponse> createResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), body, TournamentResponse.class);

        assertThat(createResponse.getStatusCode())
                .as("POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        UUID newId = createResponse.getBody().id();

        // Verify organizer in response body
        assertThat(createResponse.getBody().organizer())
                .as("E68S01 AC1: response must contain the supplied organizer")
                .isEqualTo("Custom Organizer");

        // DEC-26 Rule 2 — assertj-db independent verifier
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table tournamentTable = assertDb.table("tournament").build();
            Object organizer =
                    tournamentTable.getRowsList().stream()
                            .filter(row -> newId.equals(row.getColumnValue("ID").getValue()))
                            .findFirst()
                            .map(row -> row.getColumnValue("ORGANIZER").getValue())
                            .orElse(null);
            assertThat(organizer)
                    .as(
                            "E68S01 AC1: tournament.organizer column must equal 'Custom Organizer'"
                                    + " (assertj-db)")
                    .isEqualTo("Custom Organizer");
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // E68S01: organizer updated via PUT (AC2 + AC7)
    // =========================================================================

    /**
     * E68S01 AC2 + AC7 RED-first: PUT with {@code organizer="Updated Organizer"} → 200; assertj-db
     * verifies organizer column updated in tournament row.
     *
     * <p>RED before E68S01: {@code TournamentUpdateRequest} lacks {@code organizer} → backend
     * ignores it → organizer unchanged → assertion on "Updated Organizer" FAILS. GREEN after: all
     * layers wire the field → organizer updated.
     */
    @Test
    @DisplayName(
            "E68S01 AC2+AC7: PUT with organizer='Updated Organizer' → 200; assertj-db verifies"
                    + " organizer column updated")
    void updateWithOrganizer_updatesOrganizer() throws Exception {
        // First create a tournament
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("description", "E68S01 IT Update Organizer Tournament");
        createBody.put("appointment", null);
        createBody.put("teamCount", 4);
        createBody.put("fieldCount", 2);
        createBody.put("matchFormat", "BEST_OF_3");
        createBody.put("scoringRuleId", "setPoints");
        createBody.put("setValidationRuleId", "standardVolleyball");
        createBody.put("matchGeneratorId", "roundRobin");
        createBody.put("plannedStartTime", null);
        createBody.put("organizer", "Initial Organizer");

        ResponseEntity<TournamentResponse> createResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"),
                        createBody,
                        TournamentResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID newId = createResponse.getBody().id();

        // Now update the organizer via PUT
        Map<String, Object> updateBody = new LinkedHashMap<>();
        updateBody.put("description", "E68S01 IT Update Organizer Tournament");
        updateBody.put("teamCount", 4);
        updateBody.put("fieldCount", 2);
        updateBody.put("matchFormat", "BEST_OF_3");
        updateBody.put("scoringRuleId", "setPoints");
        updateBody.put("setValidationRuleId", "standardVolleyball");
        updateBody.put("matchGeneratorId", "roundRobin");
        updateBody.put("organizer", "Updated Organizer"); // E68S01: update organizer

        ResponseEntity<TournamentResponse> updateResponse =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + newId),
                        org.springframework.http.HttpMethod.PUT,
                        new org.springframework.http.HttpEntity<>(updateBody),
                        TournamentResponse.class);

        assertThat(updateResponse.getStatusCode())
                .as("PUT must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody()).isNotNull();
        assertThat(updateResponse.getBody().organizer())
                .as("E68S01 AC2: response must contain the updated organizer")
                .isEqualTo("Updated Organizer");

        // DEC-26 Rule 2 — assertj-db independent verifier
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table tournamentTable = assertDb.table("tournament").build();
            Object organizer =
                    tournamentTable.getRowsList().stream()
                            .filter(row -> newId.equals(row.getColumnValue("ID").getValue()))
                            .findFirst()
                            .map(row -> row.getColumnValue("ORGANIZER").getValue())
                            .orElse(null);
            assertThat(organizer)
                    .as(
                            "E68S01 AC2: tournament.organizer column must equal 'Updated Organizer'"
                                    + " after PUT (assertj-db)")
                    .isEqualTo("Updated Organizer");
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // E68S01: blank organizer rejected (AC5 + AC7)
    // =========================================================================

    /**
     * E68S01 AC5 + AC7 RED-first: POST with blank {@code organizer} → 400 validation error.
     *
     * <p>The organizer field has {@code @NotBlank} on the DTO — blank/whitespace must be rejected
     * by bean validation with HTTP 400.
     */
    @Test
    @DisplayName("E68S01 AC5+AC7: POST with blank organizer → 400 validation error")
    void createWithBlankOrganizer_returns400() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "E68S01 IT Blank Organizer");
        body.put("appointment", null);
        body.put("teamCount", 4);
        body.put("fieldCount", 2);
        body.put("matchFormat", "BEST_OF_3");
        body.put("scoringRuleId", "setPoints");
        body.put("setValidationRuleId", "standardVolleyball");
        body.put("matchGeneratorId", "roundRobin");
        body.put("plannedStartTime", null);
        body.put("organizer", "   "); // blank (whitespace-only)

        ResponseEntity<String> response =
                authed.postForEntity(new URI(baseUrl + "/api/tournaments"), body, String.class);

        assertThat(response.getStatusCode())
                .as("E68S01 AC5: POST with blank organizer must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // E68S01: end-to-end certificate — organizer in response (AC3 + AC7)
    // =========================================================================

    /**
     * E68S01 AC3 + AC7: a tournament created with organizer="Certificate Organizer" returns that
     * value in the GET response (which is the same value the certificate rendering binds to {@code
     * {{tom_organizer}}} via the {@code TournamentResponse.organizer()} field).
     *
     * <p>The certificate-rendering code itself is not changed by this story (out of scope per AC3).
     * This test confirms the persisted value round-trips through the API response, which is the
     * source of truth the certificate template uses.
     */
    @Test
    @DisplayName(
            "E68S01 AC3+AC7: tournament saved with organizer='Certificate Organizer' returns that"
                    + " value in GET (end-to-end certificate chain)")
    void createWithOrganizer_getReflectsOrganizerForCertificate() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "E68S01 IT Certificate Organizer E2E");
        body.put("appointment", null);
        body.put("teamCount", 4);
        body.put("fieldCount", 2);
        body.put("matchFormat", "BEST_OF_3");
        body.put("scoringRuleId", "setPoints");
        body.put("setValidationRuleId", "standardVolleyball");
        body.put("matchGeneratorId", "roundRobin");
        body.put("plannedStartTime", null);
        body.put("organizer", "Certificate Organizer");

        ResponseEntity<TournamentResponse> createResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), body, TournamentResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID newId = createResponse.getBody().id();

        // GET the tournament and verify organizer is returned
        ResponseEntity<TournamentResponse> getResponse =
                authed.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + newId), TournamentResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().organizer())
                .as(
                        "E68S01 AC3: GET must return organizer='Certificate Organizer' — value"
                                + " the certificate template binds to {{tom_organizer}}")
                .isEqualTo("Certificate Organizer");
    }

    // =========================================================================
    // Test-local AdminCredentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
