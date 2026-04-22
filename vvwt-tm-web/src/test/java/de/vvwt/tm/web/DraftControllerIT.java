package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.TournamentService;
import de.vvwt.tm.tournament.internal.dto.draft.DraftApplyResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftRequest;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Minimalist integration tests for {@link DraftController} — relocated to {@code de.vvwt.tm.web} in
 * E22S08 (DEC-40 Clause A Q-1b).
 *
 * <p>Targets the {@code web} module via {@code @ApplicationModuleTest(webEnvironment =
 * RANDOM_PORT)} per DEC-38 Clause C extension (DEC-40 amendment): tests relocated from the {@code
 * tournament} module's test package to the {@code web} module's test package; annotation
 * re-targeted from {@code tournament} to {@code web} module scope.
 *
 * <p>Uses {@code WebModuleTestConfig} (shared web-module test infrastructure) instead of the former
 * {@code TournamentModuleTestConfig}. Real scoring registry beans participate in this context per
 * AC-S08-REVERSE-MOCKITOBEAN-REMOVAL (DEC-38/DEC-40 reverse case): the web module includes {@code
 * scoring} in its {@code allowedDependencies}, so no scoring-registry mock is needed.
 *
 * @see DraftController
 * @see DraftControllerSliceTest
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-38">DEC-38 — @ApplicationModuleTest canon</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S08">E22S08 — relocate to de.vvwt.tm.web</a>
 */
@ApplicationModuleTest(
        mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WebModuleTestConfig.class)
@ActiveProfiles("test")
@DisplayName("DraftController IT — E22S08 web-module (2-test minimalist)")
class DraftControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E22S08DraftControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private TournamentService tournamentService;
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
        UUID tournamentId;
        tenantBinder.bindDefaultTenant();
        try {
            tournamentId =
                    tournamentService
                            .createTournament(
                                    "IT Hallenturnier E22S08",
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

        assertThat(tournamentId).isNotNull();

        DraftSectionRequest section =
                new DraftSectionRequest(1, "team_number", 1, "roundrobin", 0, 0, 15, 1, null);
        DraftRequest draftRequest = new DraftRequest(List.of(section));
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
    // Security: unauthenticated POST → 401
    // =========================================================================

    @Test
    @DisplayName("unauthenticated POST /draft/apply returns 401")
    void unauthenticatedApplyDraft_returns401() throws Exception {
        DraftSectionRequest section =
                new DraftSectionRequest(1, "team_number", 1, "roundrobin", 0, 0, 15, 1, null);
        DraftRequest draftRequest = new DraftRequest(List.of(section));
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
