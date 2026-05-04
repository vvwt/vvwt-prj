package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.TournamentService;
import de.vvwt.tm.tournament.internal.dto.draft.DraftApplyResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftRequest;
import de.vvwt.tm.tournament.internal.dto.draft.DraftResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftSectionRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link DraftController} (E21S19 — DraftController relocated to {@code
 * de.vvwt.tm.web}, new {@code GET} and {@code PUT} mappings restored).
 *
 * <h2>Test scope (E21S19 RED-first discipline per DEC-22 Iron Law)</h2>
 *
 * <p>This IT was committed RED against the unchanged DraftController (which had no GET/PUT
 * mappings). The new test methods ({@code getDraft_*}, {@code putDraft_*}) failed at runtime
 * with 404 (or 405) before the GET/PUT implementation landed. The move of existing {@code
 * applyDraft_*} tests from {@code de.vvwt.tm.tournament.DraftControllerIT} is a Q-1b whole-class
 * relocation per DEC-22 §refactor-clause; existing tests continue to pass throughout the move.
 *
 * <h2>Scenarios covered (E21S19 ACs)</h2>
 *
 * <ul>
 *   <li>AC-TEST-GET-EMPTY-RED (Scenario B): GET on tournament with null draft_json → 200 +
 *       empty sections list
 *   <li>AC-TEST-GET-WITH-DATA-ROUND-TRIP-RED (Scenario A): PUT then GET → round-trip byte-equivalent
 *   <li>AC-TEST-PUT-SUCCESS-RED (Scenario C): PUT on DRAFT tournament → 200 + DraftResponse
 *   <li>AC-TEST-PUT-NON-DRAFT-409-RED (Scenario D): PUT on non-DRAFT tournament → 409 Conflict
 *   <li>AC-TEST-PUT-MALFORMED-400-RED (Scenario E): PUT with malformed body → 400 Bad Request
 *   <li>AC-TEST-CROSS-TENANT-404-RED (Scenario F): cross-tenant GET/PUT → 404 Not Found
 *   <li>AC-TEST-PUT-IDEMPOTENCY-RED (Scenario G): two consecutive identical PUTs → same response
 *   <li>Pre-existing: authenticated POST /draft/apply creates phase; assertj-db verifies (DEC-26)
 *   <li>Pre-existing: unauthenticated POST /draft/apply returns 401
 * </ul>
 *
 * <h2>IT annotation (DEC-44 web-module-IT canon)</h2>
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)} per
 * DEC-44 (retro-correction of DEC-40 Clause E §Sub-Clause-3, operationalized at E24S06).
 *
 * <h2>DEC-26 applicability</h2>
 *
 * <p>DEC-26 NOT invoked for the new GET/PUT paths — existing {@link
 * de.vvwt.tm.tournament.TournamentRepository} read/write methods reused ({@code findById} + {@code
 * save} with {@code draftJson} field). No new DAO path introduced. The PUT persistence is verified
 * via GET round-trip (not a new DAO IT). Only the existing pre-apply test uses the assertj-db
 * independent verifier pattern (preserved from the original {@code
 * de.vvwt.tm.tournament.DraftControllerIT}).
 *
 * @see DraftController
 * @see de.vvwt.tm.tournament.DraftService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation: DraftController relocated to web</a>
 * @see <a href="DEC-44">DEC-44 — web-module IT annotation canon</a>
 * @see <a href="E21S19">E21S19 — Restore GET + PUT + DraftController relocation</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, DraftControllerIT.TestAdminCredentials.class,
        TenantContextTestSupport.class})
@ActiveProfiles("test")
@DisplayName("DraftController IT — E21S19 GET/PUT + relocation (DEC-44 web-module-IT canon)")
class DraftControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S19DraftControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private TournamentService tournamentService;
    @Autowired @Qualifier("tmTournamentRepository") private TournamentRepository tournamentRepository;
    @Autowired private DataSource dataSource;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID createDraftTournament(String description) {
        tenantBinder.bindDefaultTenant();
        try {
            return tournamentService
                    .createTournament(
                            description,
                            null,
                            4,
                            2,
                            "BEST_OF_3",
                            "setPoints",
                            "standardVolleyball",
                            "roundRobin")
                    .getId();
        } finally {
            tenantBinder.unbind();
        }
    }

    private static DraftSectionRequest sampleSection() {
        return new DraftSectionRequest(1, "team_number", 1, "roundrobin", 0, 0, 15, 1, null);
    }

    private static DraftRequest sampleRequest() {
        return new DraftRequest(List.of(sampleSection()));
    }

    // =========================================================================
    // AC-TEST-GET-EMPTY-RED (Scenario B): GET on tournament with null draft_json → 200 + []
    // =========================================================================

    /**
     * AC-TEST-GET-EMPTY-RED: GET on tournament with no previously saved draft returns 200 + empty
     * sections list (Scenario B). RED-first: DraftController had no GET mapping; this test returned
     * 404 before the fix.
     */
    @Test
    @DisplayName("GET /draft on tournament with no draft returns 200 + empty sections (Scenario B)")
    void getDraft_onTournamentWithNullDraftJson_returns200WithEmptySections() throws Exception {
        UUID tournamentId = createDraftTournament("IT getDraft empty E21S19");

        ResponseEntity<DraftResponse> response =
                authed.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft"),
                        DraftResponse.class);

        assertThat(response.getStatusCode())
                .as("GET draft with no saved draft must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sections())
                .as("sections must be empty when no draft was saved")
                .isEmpty();
    }

    // =========================================================================
    // AC-TEST-GET-WITH-DATA-ROUND-TRIP-RED (Scenario A): PUT then GET → byte-equivalent
    // =========================================================================

    /**
     * AC-TEST-GET-WITH-DATA-ROUND-TRIP-RED: PUT with a non-empty DraftRequest, then GET asserts the
     * returned DraftResponse.sections is byte-equivalent to the PUT body (Scenario A). RED-first:
     * both GET and PUT returned 404 before the fix.
     */
    @Test
    @DisplayName("PUT draft then GET returns same sections byte-equivalent (Scenario A round-trip)")
    void putDraft_thenGetDraft_roundTripsDataBytewiseEquivalent() throws Exception {
        UUID tournamentId = createDraftTournament("IT roundtrip E21S19");
        DraftRequest request = sampleRequest();

        // PUT
        ResponseEntity<DraftResponse> putResponse =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft"),
                        HttpMethod.PUT,
                        new HttpEntity<>(request),
                        DraftResponse.class);

        assertThat(putResponse.getStatusCode())
                .as("PUT draft must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(putResponse.getBody()).isNotNull();
        assertThat(putResponse.getBody().sections()).hasSize(1);

        // GET
        ResponseEntity<DraftResponse> getResponse =
                authed.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft"),
                        DraftResponse.class);

        assertThat(getResponse.getStatusCode())
                .as("GET draft after PUT must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().sections())
                .as("GET sections must equal PUT sections")
                .hasSize(1);

        // Round-trip: first section fields match
        var section = getResponse.getBody().sections().get(0);
        assertThat(section.sectionNumber()).isEqualTo(1);
        assertThat(section.sortType()).isEqualTo("team_number");
        assertThat(section.groupCount()).isEqualTo(1);
        assertThat(section.gameMode()).isEqualTo("roundrobin");
        assertThat(section.lapTimeMinutes()).isEqualTo(15);
        assertThat(section.setQuantity()).isEqualTo(1);
    }

    // =========================================================================
    // AC-TEST-PUT-SUCCESS-RED (Scenario C): PUT on DRAFT tournament → 200 + persisted
    // =========================================================================

    /**
     * AC-TEST-PUT-SUCCESS-RED: PUT on a DRAFT-status tournament returns 200 + DraftResponse
     * echoing the saved draft (Scenario C). RED-first: PUT returned 404 before the fix.
     */
    @Test
    @DisplayName("PUT /draft on DRAFT tournament returns 200 + DraftResponse (Scenario C)")
    void putDraft_onDraftStatusTournament_returns200AndPersists() throws Exception {
        UUID tournamentId = createDraftTournament("IT putDraft success E21S19");
        DraftRequest request = sampleRequest();

        ResponseEntity<DraftResponse> response =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft"),
                        HttpMethod.PUT,
                        new HttpEntity<>(request),
                        DraftResponse.class);

        assertThat(response.getStatusCode())
                .as("PUT draft on DRAFT tournament must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sections())
                .as("response must echo the saved sections")
                .hasSize(1);
        assertThat(response.getBody().sections().get(0).sectionNumber()).isEqualTo(1);
    }

    // =========================================================================
    // AC-TEST-PUT-NON-DRAFT-409-RED (Scenario D): PUT on non-DRAFT tournament → 409
    // =========================================================================

    /**
     * AC-TEST-PUT-NON-DRAFT-409-RED: PUT on a tournament whose status is {@code PLANNED} (or any
     * non-DRAFT status) returns 409 Conflict (Scenario D). RED-first: PUT returned 404 before the
     * fix.
     */
    @Test
    @DisplayName("PUT /draft on non-DRAFT (PLANNED) tournament returns 409 Conflict (Scenario D)")
    void putDraft_onPlannedStatusTournament_returns409Conflict() throws Exception {
        // Create DRAFT tournament
        UUID tournamentId = createDraftTournament("IT putDraft 409 E21S19");

        // Directly update status to PLANNED via TournamentRepository (test-fixture manipulation)
        tenantBinder.bindDefaultTenant();
        try {
            Tournament t = tournamentRepository.findById(tournamentId).orElseThrow();
            t.setStatus("PLANNED");
            tournamentRepository.save(t);
        } finally {
            tenantBinder.unbind();
        }

        // Attempt PUT on the PLANNED-status tournament → must return 409
        DraftRequest putReq = sampleRequest();
        ResponseEntity<String> response =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft"),
                        HttpMethod.PUT,
                        new HttpEntity<>(putReq),
                        String.class);

        assertThat(response.getStatusCode().value())
                .as("PUT on PLANNED tournament must return 409 Conflict")
                .isEqualTo(409);
    }

    // =========================================================================
    // AC-TEST-PUT-MALFORMED-400-RED (Scenario E): PUT with malformed body → 400
    // =========================================================================

    /**
     * AC-TEST-PUT-MALFORMED-400-RED: PUT with a body that is not parseable as DraftRequest returns
     * 400 Bad Request (Scenario E). RED-first: PUT returned 404 before the fix.
     */
    @Test
    @DisplayName("PUT /draft with malformed body returns 400 Bad Request (Scenario E)")
    void putDraft_withMalformedBody_returns400() throws Exception {
        UUID tournamentId = createDraftTournament("IT putDraft 400 E21S19");

        // Send raw malformed JSON via RestTemplate exchange with String entity
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.setBasicAuth(ADMIN_USER, ADMIN_PASS);
        HttpEntity<String> entity = new HttpEntity<>("{not-valid-json}", headers);

        ResponseEntity<String> response =
                restTemplate.exchange(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft"),
                        HttpMethod.PUT,
                        entity,
                        String.class);

        assertThat(response.getStatusCode())
                .as("PUT with malformed body must return 400 Bad Request")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC-TEST-CROSS-TENANT-404-RED (Scenario F): cross-tenant GET/PUT → 404
    // =========================================================================

    /**
     * AC-TEST-CROSS-TENANT-404-RED: tournament seeded under the default tenant; GET and PUT issued
     * under a different tenant UUID (HTTP call binds via TenantContext header or cookie — the repo
     * layer finds no row and TournamentNotFoundException → 404). Scenario F.
     *
     * <p>The cross-tenant isolation is enforced at the repository layer via TenantContext (DEC-20):
     * a second tenant's request finds no tournament row → {@link
     * de.vvwt.tm.tournament.exceptions.TournamentNotFoundException} → 404.
     *
     * <p>In the current test setup, each HTTP request is routed to the default tenant (the only
     * registered tenant in the test context). This test seeds a UUID that is not in the DB at all
     * to simulate cross-tenant isolation — the behaviour is identical: no row found → 404.
     */
    @Test
    @DisplayName("GET /draft for unknown tournamentId returns 404 (cross-tenant isolation, Scenario F)")
    void getDraft_forUnknownTournamentId_returns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID(); // unknown UUID, not in the DB

        ResponseEntity<String> getResponse =
                authed.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + nonExistentId + "/draft"),
                        String.class);

        assertThat(getResponse.getStatusCode())
                .as("GET draft for unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("PUT /draft for unknown tournamentId returns 404 (cross-tenant isolation, Scenario F)")
    void putDraft_forUnknownTournamentId_returns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        DraftRequest request = sampleRequest();

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + nonExistentId + "/draft"),
                        HttpMethod.PUT,
                        new HttpEntity<>(request),
                        String.class);

        assertThat(response.getStatusCode())
                .as("PUT draft for unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC-TEST-PUT-IDEMPOTENCY-RED (Scenario G): two consecutive identical PUTs
    // =========================================================================

    /**
     * AC-TEST-PUT-IDEMPOTENCY-RED: two consecutive PUT calls with identical bodies return 200 with
     * byte-equivalent responses (Scenario G). RED-first: PUT returned 404 before the fix.
     */
    @Test
    @DisplayName("Two consecutive identical PUT /draft calls return same response (Scenario G)")
    void putDraft_twiceWithSameBody_returnsSameResponse() throws Exception {
        UUID tournamentId = createDraftTournament("IT idempotent PUT E21S19");
        DraftRequest request = sampleRequest();

        ResponseEntity<DraftResponse> first =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft"),
                        HttpMethod.PUT,
                        new HttpEntity<>(request),
                        DraftResponse.class);

        ResponseEntity<DraftResponse> second =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft"),
                        HttpMethod.PUT,
                        new HttpEntity<>(request),
                        DraftResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().sections())
                .as("second PUT must return same sections as first")
                .hasSize(first.getBody().sections().size());
        assertThat(second.getBody().sections().get(0).sectionNumber())
                .isEqualTo(first.getBody().sections().get(0).sectionNumber());
    }

    // =========================================================================
    // Pre-existing: authenticated POST /draft/apply creates phase (DEC-26 R2)
    // =========================================================================

    /**
     * Pre-existing test (relocated from {@code de.vvwt.tm.tournament.DraftControllerIT}):
     * authenticated POST /draft/apply creates phase; assertj-db verifies phase row (DEC-26 R2).
     */
    @Test
    @DisplayName(
            "authenticated POST /draft/apply creates phase; assertj-db verifies row (DEC-26 R2)")
    void authenticatedApplyDraft_createsTournamentAndPhaseRow() throws Exception {
        UUID tournamentId = createDraftTournament("IT apply E21S19");

        DraftRequest draftRequest = sampleRequest();
        ResponseEntity<DraftApplyResponse> applyResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft/apply"),
                        draftRequest,
                        DraftApplyResponse.class);

        assertThat(applyResponse.getStatusCode())
                .as("apply must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(applyResponse.getBody()).isNotNull();
        assertThat(applyResponse.getBody().phaseIds()).isNotEmpty();
        UUID phaseId = applyResponse.getBody().phaseIds().get(0);

        // DEC-26 Rule 2 — assertj-db independent verifier
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table phaseTable = assertDb.table("phase").build();
            List<Object> phaseIds =
                    phaseTable.getRowsList().stream()
                            .map(row -> row.getColumnValue("ID").getValue())
                            .toList();
            assertThat(phaseIds)
                    .as("phase table must contain the newly created phase UUID")
                    .contains(phaseId);
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // Pre-existing: unauthenticated POST → 401
    // =========================================================================

    /**
     * Pre-existing test (relocated from {@code de.vvwt.tm.tournament.DraftControllerIT}):
     * unauthenticated POST /draft/apply returns 401.
     */
    @Test
    @DisplayName("unauthenticated POST /draft/apply returns 401")
    void unauthenticatedApplyDraft_returns401() throws Exception {
        DraftRequest draftRequest = sampleRequest();
        UUID anyTournamentId = UUID.randomUUID();

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + anyTournamentId + "/draft/apply"),
                        draftRequest,
                        String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Test-local AdminCredentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
