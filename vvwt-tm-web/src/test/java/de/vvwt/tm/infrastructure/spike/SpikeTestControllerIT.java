package de.vvwt.tm.infrastructure.spike;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.net.URI;
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
 * Integration tests for {@link SpikeTestController} — iOS 9 compatibility spike (E06S01).
 *
 * <p>Tests the full HTTP stack:
 *
 * <ul>
 *   <li>Spike echo endpoint accessible without auth (SecurityConfig permits /score/spike/**)
 *   <li>Spike page serving at /score/spike/
 *   <li>Static JS asset accessible at /static/score/spike/spike-utils.js
 * </ul>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            SpikeTestControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e06s01spikedb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("SpikeTestController IT — iOS 9 spike (E06S01)")
class SpikeTestControllerIT {

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    /** AC1: Echo endpoint returns 200 with JSON — no auth required. */
    @Test
    @DisplayName("AC1: GET /score/spike/api/echo returns 200 without auth")
    void echoEndpoint_returns200WithoutAuth() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/spike/api/echo"), String.class);

        assertThat(response.getStatusCode())
                .as(
                        "Echo endpoint must be accessible without auth (SecurityConfig permits"
                                + " /score/spike/**)")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("Echo response must contain status field")
                .contains("\"status\":\"ok\"");
        assertThat(response.getBody())
                .as("Echo response must contain timestamp field")
                .contains("\"timestamp\":");
    }

    /** AC9: Static JS asset served locally from classpath:/static/ (no /static prefix in URL). */
    @Test
    @DisplayName("AC9: GET /score/spike/spike-utils.js returns 200 without auth")
    void spikeUtilsJs_returns200WithoutAuth() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/score/spike/spike-utils.js"), String.class);

        assertThat(response.getStatusCode())
                .as("spike-utils.js must be accessible without auth")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("spike-utils.js must contain ES5 strict mode declaration")
                .contains("\"use strict\"");
    }

    /**
     * AC4/AC7: Spike test page accessible without auth. Spring Boot serves
     * classpath:/static/score/spike/test.html at URL /score/spike/test.html.
     */
    @Test
    @DisplayName("AC4/AC7: GET /score/spike/test.html returns 200 without auth")
    void spikeTestPage_returns200WithoutAuth() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/score/spike/test.html"), String.class);

        assertThat(response.getStatusCode())
                .as("Spike test page must be accessible without auth")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("Spike test page must contain AC references")
                .contains("iOS 9 Compatibility Spike");
    }

    /**
     * Test admin credentials for the Spring Security context. Spike endpoints don't need auth, but
     * the application context requires a valid AdminCredentialsProvider.
     */
    @TestConfiguration
    static class TestAdminCredentials {
        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode("SpikeTestPass01");
            return () -> hash;
        }
    }
}
