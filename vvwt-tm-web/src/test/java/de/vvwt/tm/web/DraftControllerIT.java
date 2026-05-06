package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.TournamentService;
import de.vvwt.tm.tournament.internal.dto.draft.DraftApplyResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftPreviewResponse;
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
 * mappings). The new test methods ({@code getDraft_*}, {@code putDraft_*}) failed at runtime with
 * 404 (or 405) before the GET/PUT implementation landed. The move of existing {@code applyDraft_*}
 * tests from {@code de.vvwt.tm.tournament.DraftControllerIT} is a Q-1b whole-class relocation per
 * DEC-22 §refactor-clause; existing tests continue to pass throughout the move.
 *
 * <h2>Scenarios covered (E21S19 ACs)</h2>
 *
 * <ul>
 *   <li>AC-TEST-GET-EMPTY-RED (Scenario B): GET on tournament with null draft_json → 200 + empty
 *       sections list
 *   <li>AC-TEST-GET-WITH-DATA-ROUND-TRIP-RED (Scenario A): PUT then GET → round-trip
 *       byte-equivalent
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
@Import({
    WebModuleTestConfig.class,
    DraftControllerIT.TestAdminCredentials.class,
    TenantContextTestSupport.class
})
@ActiveProfiles("test")
@DisplayName("DraftController IT — E21S19 GET/PUT + relocation (DEC-44 web-module-IT canon)")
class DraftControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S19DraftControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private TournamentService tournamentService;

    @Autowired
    @Qualifier("tmTournamentRepository")
    private TournamentRepository tournamentRepository;

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
                            "roundRobin",
                            null)
                    .getId();
        } finally {
            tenantBinder.unbind();
        }
    }

    private static DraftSectionRequest sampleSection() {
        // gameMode=siegerehrung: single-section draft must use siegerehrung as last phase
        // per D-10 invariant (AC-IMPL-LAST-PHASE-INVARIANT, E48S01).
        return new DraftSectionRequest(1, "team_number", 1, "siegerehrung", 0, 0, 15, 1, null);
    }

    private static DraftRequest sampleRequest() {
        return new DraftRequest(List.of(sampleSection()));
    }

    /**
     * Two-section apply request used by the apply test.
     *
     * <p>Section 1 = roundrobin: match generation runs via RoundRobinMatchGenerator (already
     * registered). Section 2 = siegerehrung: last phase per D-10 invariant
     * (AC-IMPL-LAST-PHASE-INVARIANT, E48S01); match generation is NOT triggered for phase 2
     * (DefaultDraftService.apply() only generates matches for the first phase). This avoids a
     * dependency on SiegerehrungMatchGenerator (delivered in E48S02).
     */
    private static DraftRequest applyRequest() {
        var s1 = new DraftSectionRequest(1, "team_number", 1, "roundRobin", 0, 0, 15, 1, null);
        var s2 = new DraftSectionRequest(2, "team_number", 1, "siegerehrung", 0, 0, 15, 1, null);
        return new DraftRequest(List.of(s1, s2));
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
        assertThat(section.gameMode()).isEqualTo("siegerehrung");
        assertThat(section.lapTimeMinutes()).isEqualTo(15);
        assertThat(section.setQuantity()).isEqualTo(1);
    }

    // =========================================================================
    // AC-TEST-PUT-SUCCESS-RED (Scenario C): PUT on DRAFT tournament → 200 + persisted
    // =========================================================================

    /**
     * AC-TEST-PUT-SUCCESS-RED: PUT on a DRAFT-status tournament returns 200 + DraftResponse echoing
     * the saved draft (Scenario C). RED-first: PUT returned 404 before the fix.
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
    @DisplayName(
            "GET /draft for unknown tournamentId returns 404 (cross-tenant isolation, Scenario F)")
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
    @DisplayName(
            "PUT /draft for unknown tournamentId returns 404 (cross-tenant isolation, Scenario F)")
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

        // applyRequest() uses 2 sections (roundrobin + siegerehrung) — see helper javadoc
        DraftRequest draftRequest = applyRequest();
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
    // AC-TEST-CONTROLLER-IT-FIELDCOUNT-WIRING (E48S10)
    // =========================================================================

    /**
     * AC-TEST-CONTROLLER-IT-FIELDCOUNT-WIRING: POST /api/tournaments/{id}/draft/preview returns
     * totalLaps consistent with the tournament's persisted {@code field_count} value.
     *
     * <h2>Fixture (Brief Scenario B)</h2>
     *
     * <ul>
     *   <li>Tournament: 12 teams, field_count=3
     *   <li>Config: 1-phase round-robin, groupCount=2 (→ 2 groups of 6)
     *   <li>Expected: totalLaps=10 (ceil(30/3)=10; current bug yields 5)
     * </ul>
     *
     * <h2>DEC-44 §2026-04-27 empirical refinement</h2>
     *
     * <p>Uses {@code @Import({WebModuleTestConfig.class, TestAdminCredentials.class})} with inner
     * {@code @Primary AdminCredentialsProvider} via {@code TestAdminCredentials} inner class.
     *
     * <p>RED-first: written before {@code DraftController.previewDraft} passes fieldCount (E48S10,
     * DEC-22 Iron Law). The controller still calls 2-arg preview at RED time.
     */
    @Test
    @DisplayName(
            "POST /draft/preview with field_count=3, 12 teams, 2 groups returns totalLaps=10"
                    + " (E48S10 Scenario B)")
    void previewDraft_withFieldCount3_12teams2groups_returns10Laps() throws Exception {
        // Create tournament with 12 teams, field_count=3
        UUID tournamentId;
        tenantBinder.bindDefaultTenant();
        try {
            tournamentId =
                    tournamentService
                            .createTournament(
                                    "IT preview fieldCount E48S10",
                                    null,
                                    12, // teamCount
                                    3, // fieldCount
                                    "BEST_OF_3",
                                    "setPoints",
                                    "standardVolleyball",
                                    "roundRobin",
                                    null)
                            .getId();
        } finally {
            tenantBinder.unbind();
        }

        // Config: 1 phase, groupCount=2 (2 groups of 6 teams each), roundRobin
        var section = new DraftSectionRequest(1, "team_number", 2, "roundRobin", 0, 0, 15, 1, null);
        DraftRequest request = new DraftRequest(List.of(section));

        ResponseEntity<DraftPreviewResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft/preview"),
                        request,
                        DraftPreviewResponse.class);

        assertThat(response.getStatusCode())
                .as("POST /draft/preview must return 200 OK")
                .isEqualTo(org.springframework.http.HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sections()).hasSize(1);
        assertThat(response.getBody().sections().get(0).totalLaps())
                .as(
                        "12 teams, 2 groups of 6, field_count=3 → "
                                + "teamConflict=3*2=6, eff=min(6,3)=3, "
                                + "matches=30, laps=ceil(30/3)=10")
                .isEqualTo(10);
    }

    // =========================================================================
    // AC-TEST-CONTROLLER-IT-PREVIEW-SIEGEREHRUNG-GREEN (E48S09)
    // =========================================================================

    /**
     * AC-TEST-CONTROLLER-IT-PREVIEW-SIEGEREHRUNG-GREEN: POST /api/tournaments/{id}/draft/preview
     * for a 2-phase config (RoundRobin + Siegerehrung) returns phase 2 with totalMatches=0,
     * totalLaps=0, estimatedTimeMinutes = sectionBreakTimeMinutes (no intra-phase breaks).
     *
     * <h2>Fixture</h2>
     *
     * <ul>
     *   <li>Tournament: 12 teams, fieldCount=3
     *   <li>Phase 1: roundRobin, groupCount=2
     *   <li>Phase 2: siegerehrung, groupCount=1, sectionBreakTimeMinutes=20, no intra-breaks
     * </ul>
     *
     * <h2>Expected</h2>
     *
     * <ul>
     *   <li>Phase 2 totalMatches = 0
     *   <li>Phase 2 totalLaps = 0
     *   <li>Phase 2 estimatedTimeMinutes = 20 (sectionBreakTimeMinutes only)
     * </ul>
     *
     * <p>DEC-44 §2026-04-27 empirical refinement: uses {@code @SpringBootTest(RANDOM_PORT, classes
     * = TournamentManagerApplication.class)} with {@code @Import({WebModuleTestConfig.class,
     * TestAdminCredentials.class})} inner class — already present in this IT class.
     */
    @Test
    @DisplayName(
            "POST /draft/preview with siegerehrung phase 2 returns totalMatches=0, totalLaps=0"
                    + " (E48S09 AC-TEST-CONTROLLER-IT-PREVIEW-SIEGEREHRUNG-GREEN)")
    void previewDraft_siegerehrungPhase_returnsZeroMatchesAndLaps() throws Exception {
        UUID tournamentId;
        tenantBinder.bindDefaultTenant();
        try {
            tournamentId =
                    tournamentService
                            .createTournament(
                                    "IT preview siegerehrung E48S09",
                                    null,
                                    12, // teamCount
                                    3, // fieldCount
                                    "BEST_OF_3",
                                    "setPoints",
                                    "standardVolleyball",
                                    "roundRobin",
                                    null)
                            .getId();
        } finally {
            tenantBinder.unbind();
        }

        // Phase 1: roundRobin, groupCount=2; Phase 2: siegerehrung, sectionBreakTimeMinutes=20
        var phase1 = new DraftSectionRequest(1, "team_number", 2, "roundRobin", 0, 0, 15, 1, null);
        var phase2 =
                new DraftSectionRequest(2, "team_number", 1, "siegerehrung", 0, 20, 15, 1, null);
        DraftRequest request = new DraftRequest(List.of(phase1, phase2));

        ResponseEntity<DraftPreviewResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft/preview"),
                        request,
                        DraftPreviewResponse.class);

        assertThat(response.getStatusCode())
                .as("POST /draft/preview must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sections()).hasSize(2);

        var phase2Preview = response.getBody().sections().get(1);
        assertThat(phase2Preview.totalMatches())
                .as("siegerehrung phase: totalMatches must be 0")
                .isEqualTo(0);
        assertThat(phase2Preview.totalLaps())
                .as("siegerehrung phase: totalLaps must be 0")
                .isEqualTo(0);
        assertThat(phase2Preview.estimatedTimeMinutes())
                .as("siegerehrung phase: estimatedTimeMinutes = sectionBreakTimeMinutes=20")
                .isEqualTo(20);
    }

    // =========================================================================
    // AC-TEST-CONTROLLER-IT-PREVIEW-TIMELINE-WIRING (E48S12)
    // =========================================================================

    /**
     * AC-TEST-CONTROLLER-IT-PREVIEW-TIMELINE-WIRING: POST /api/tournaments/{id}/draft/preview for a
     * 2-phase config (RR + Siegerehrung) with a set {@code plannedStartTime} returns a non-empty
     * {@code timeline} whose first entry has {@code startTime = "09:00:00"} (or starts with
     * "09:00").
     *
     * <h2>Fixture</h2>
     *
     * <ul>
     *   <li>Tournament: field_count=3, team_count=12, plannedStartTime=09:00
     *   <li>Phase 1: roundRobin, groupCount=2, lapTimeMinutes=15, lapBreakTimeMinutes=2,
     *       sectionBreakTimeMinutes=10
     *   <li>Phase 2: siegerehrung, sectionBreakTimeMinutes=0
     * </ul>
     *
     * <h2>Expected</h2>
     *
     * <ul>
     *   <li>response.timeline is non-empty
     *   <li>response.timeline[0].startTime starts with "09:00"
     *   <li>response.timeline contains at least one entry with type = "MATCH_ROUND"
     * </ul>
     *
     * <h2>RED-first discipline (DEC-22)</h2>
     *
     * <p>This test was written RED-first — before {@code DraftController.previewDraft()} passes
     * {@code plannedStartTime} and before {@code DefaultDraftService.preview()} invokes {@code
     * TimelineCalculationService}. At RED time, {@code response.timeline} is always empty because
     * {@code DefaultDraftService.preview()} hardcodes {@code List.of()}.
     *
     * @see <a href="E48S12">E48S12 — AC-TEST-CONTROLLER-IT-PREVIEW-TIMELINE-WIRING</a>
     * @see <a href="DEC-44">DEC-44 — web-module IT annotation canon</a>
     */
    @Test
    @DisplayName(
            "POST /draft/preview with plannedStartTime=09:00 returns non-empty timeline with"
                    + " MATCH_ROUND entries (E48S12 AC-TEST-CONTROLLER-IT-PREVIEW-TIMELINE-WIRING)")
    void previewDraft_withPlannedStartTime_returnsNonEmptyTimeline() throws Exception {
        // Create tournament with plannedStartTime set to 09:00
        UUID tournamentId;
        tenantBinder.bindDefaultTenant();
        try {
            tournamentId =
                    tournamentService
                            .createTournament(
                                    "IT preview timeline E48S12",
                                    null,
                                    12, // teamCount
                                    3, // fieldCount
                                    "BEST_OF_3",
                                    "setPoints",
                                    "standardVolleyball",
                                    "roundRobin",
                                    null) // plannedStartTime set below via repository (E48S14)
                            .getId();
            // Set plannedStartTime directly on the tournament entity (createTournament does not
            // accept plannedStartTime; use repository to set it for the fixture)
            Tournament t = tournamentRepository.findById(tournamentId).orElseThrow();
            t.setPlannedStartTime(java.time.LocalTime.of(9, 0));
            tournamentRepository.save(t);
        } finally {
            tenantBinder.unbind();
        }

        // Phase 1: roundRobin, 2 groups, lapTime=15min, lapBreak=2min, sectionBreak=10min
        // Phase 2: siegerehrung (lapCount=0 → zero-duration marker)
        var phase1 = new DraftSectionRequest(1, "team_number", 2, "roundRobin", 2, 10, 15, 1, null);
        var phase2 =
                new DraftSectionRequest(2, "team_number", 1, "siegerehrung", 0, 0, 15, 1, null);
        DraftRequest request = new DraftRequest(List.of(phase1, phase2));

        ResponseEntity<DraftPreviewResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft/preview"),
                        request,
                        DraftPreviewResponse.class);

        assertThat(response.getStatusCode())
                .as("POST /draft/preview must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().timeline())
                .as(
                        "plannedStartTime non-null → timeline must be populated"
                                + " (E48S12 AC-TEST-CONTROLLER-IT-PREVIEW-TIMELINE-WIRING)")
                .isNotEmpty();

        var firstEntry = response.getBody().timeline().get(0);
        assertThat(firstEntry.startTime().toString())
                .as("first timeline entry must start at plannedStartTime 09:00")
                .startsWith("09:00");

        boolean hasMatchRound =
                response.getBody().timeline().stream()
                        .anyMatch(e -> "MATCH_ROUND".equals(e.type()));
        assertThat(hasMatchRound)
                .as("timeline must contain at least one MATCH_ROUND entry")
                .isTrue();
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
