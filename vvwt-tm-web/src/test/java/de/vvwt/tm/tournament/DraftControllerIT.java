package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftApplyResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftRequest;
import de.vvwt.tm.tournament.internal.dto.draft.DraftSectionRequest;
import java.net.URI;
import java.util.List;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Minimalist integration tests for {@link DraftController} (E21S07,
 * AC-REST-IT-HAPPY-DraftController + AC-REST-IT-SEC-DraftController).
 *
 * <h2>Approach C minimalist-IT (C-13)</h2>
 *
 * <p>Exactly 2 {@code @Test} methods:
 *
 * <ol>
 *   <li>Happy-path: create tournament → apply draft → assertj-db verifies phase row (DEC-26 Rule
 *       2)
 *   <li>Unauthenticated request → 401 (security gate)
 * </ol>
 *
 * <h2>IT annotation choice (AC-IT-ANNOTATION)</h2>
 *
 * <p>Uses {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} per conventions.md (d) — matches
 * E20S02/E21S02 canon exactly.
 *
 * <h2>assertj-db independent verifier (DEC-26 Rule 2)</h2>
 *
 * <p>Phase row is verified via {@code AssertDbConnection.table("phase")} — not via
 * {@link PhaseRepository} or a GET endpoint. Controller is NOT the instrument of its own
 * verification.
 *
 * @see DraftController
 * @see DraftControllerSliceTest
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            DraftControllerIT.TestAdminCredentials.class
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("DraftController IT — E21S07 AC-REST-IT (2-test minimalist)")
class DraftControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S07DraftControllerIT01";

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
    // Happy-path: create tournament → apply draft → assertj-db verifies phase row
    // =========================================================================

    @Test
    @DisplayName(
            "authenticated POST /draft/apply creates phase; assertj-db verifies row (DEC-26 R2)")
    void authenticatedApplyDraft_createsTournamentAndPhaseRow() throws Exception {
        // Step 1: create a tournament in DRAFT status via TournamentController
        var tournamentRequest =
                new TournamentCreateRequest(
                        "IT Hallenturnier E21S07",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin");
        ResponseEntity<TournamentResponse> tournamentResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tm/tournaments"),
                        tournamentRequest,
                        TournamentResponse.class);
        assertThat(tournamentResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        java.util.UUID tournamentId = tournamentResponse.getBody().id();
        assertThat(tournamentId).isNotNull();

        // Step 2: apply draft (one section, 1 group, roundrobin)
        DraftSectionRequest section =
                new DraftSectionRequest(1, "team_number", 1, "roundrobin", 0, 0, 15, 1, null);
        DraftRequest draftRequest = new DraftRequest(List.of(section));
        ResponseEntity<DraftApplyResponse> applyResponse =
                authed.postForEntity(
                        new URI(
                                baseUrl
                                        + "/api/tm/tournaments/"
                                        + tournamentId
                                        + "/draft/apply"),
                        draftRequest,
                        DraftApplyResponse.class);

        assertThat(applyResponse.getStatusCode())
                .as("apply must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(applyResponse.getBody()).isNotNull();
        assertThat(applyResponse.getBody().phaseIds()).isNotEmpty();
        java.util.UUID phaseId = applyResponse.getBody().phaseIds().get(0);

        // Step 3: DEC-26 Rule 2 — assertj-db independent verifier
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
    // Security: unauthenticated POST → 401
    // =========================================================================

    @Test
    @DisplayName("unauthenticated POST /draft/apply returns 401")
    void unauthenticatedApplyDraft_returns401() throws Exception {
        DraftSectionRequest section =
                new DraftSectionRequest(1, "team_number", 1, "roundrobin", 0, 0, 15, 1, null);
        DraftRequest draftRequest = new DraftRequest(List.of(section));
        java.util.UUID anyTournamentId = java.util.UUID.randomUUID();

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(
                                baseUrl
                                        + "/api/tm/tournaments/"
                                        + anyTournamentId
                                        + "/draft/apply"),
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
