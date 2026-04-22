package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.dto.DeviceRegisterRequest;
import de.vvwt.tm.tournament.internal.dto.DeviceRegisterResponse;
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
 * Minimalist integration tests for {@link DeviceController} (E21S06,
 * AC-REST-IT-HAPPY-DeviceController + AC-REST-IT-SEC-DeviceController).
 *
 * <h2>Approach C minimalist-IT</h2>
 *
 * <p>Exactly 2 {@code @Test} methods:
 *
 * <ol>
 *   <li>Happy-path authenticated POST /register → 201 + assertj-db independent DB verification
 *       (DEC-26 Rule 2)
 *   <li>Unauthenticated request → 201 (public endpoint)
 * </ol>
 *
 * <p>Slice tests ({@link DeviceControllerSliceTest}) cover all other scenarios.
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
 * {@code de.vvwt.tm.web} as the module under test. {@link TournamentModuleTestConfig} is imported
 * via its FQN from the {@code tournament} test package.
 *
 * @see DeviceController
 * @see DeviceControllerSliceTest
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S07">E22S07 — Relocate DeviceController to de.vvwt.tm.web</a>
 */
@ApplicationModuleTest(
        mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WebModuleTestConfig.class)
@ActiveProfiles("test")
@DisplayName("DeviceController IT — E21S06 AC-REST-IT (2-test minimalist)")
class DeviceControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S06DeviceControllerIT01";

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
    // Happy-path: authenticated POST /register → 201, row verified via assertj-db
    // =========================================================================

    @Test
    @DisplayName(
            "authenticated POST /api/devices/register creates SCORING_TABLET; assertj-db"
                    + " verifies row")
    void authenticatedPostRegisterCreatesDeviceAndPersistsRow() throws Exception {
        DeviceRegisterRequest request = new DeviceRegisterRequest("SCORING_TABLET");

        ResponseEntity<DeviceRegisterResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/devices/register"),
                        request,
                        DeviceRegisterResponse.class);

        assertThat(response.getStatusCode())
                .as("POST /register must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().deviceToken()).isNotBlank();

        // DEC-26 Rule 2 — assertj-db independent verifier
        UUID newId = response.getBody().id();
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table devicesTable = assertDb.table("devices").build();
            List<Object> ids =
                    devicesTable.getRowsList().stream()
                            .map(row -> row.getColumnValue("ID").getValue())
                            .toList();
            assertThat(ids)
                    .as("devices table must contain the newly registered device UUID")
                    .contains(newId);
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // Security: unauthenticated POST → 201 (register is public — devices self-register)
    // =========================================================================

    @Test
    @DisplayName("unauthenticated POST /api/devices/register returns 201 (public endpoint)")
    void unauthenticatedPostRegisterReturns201() throws Exception {
        // POST /api/devices/register is public (permitAll) — scoring tablets register without
        // admin credentials (E21S13 cutover: transitional /api/tm/devices/register was not
        // in the permit-list, so this test previously returned 401 on the transitional URL;
        // after URL normalization to /api/devices/register the correct behavior is 201).
        DeviceRegisterRequest request = new DeviceRegisterRequest("SCORING_TABLET");

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/devices/register"), request, String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated register must return 201 (public endpoint)")
                .isEqualTo(HttpStatus.CREATED);
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
