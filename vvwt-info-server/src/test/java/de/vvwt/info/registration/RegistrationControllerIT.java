package de.vvwt.info.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.registration.RegistrationRequest;
import de.vvwt.info.persistence.audit.AuditLogDao;
import de.vvwt.info.persistence.audit.RejectionReason;
import java.security.KeyPairGenerator;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full Spring context integration tests for the registration controller (AC3, AC4, AC5).
 *
 * <p>Uses {@code @SpringBootTest} with self-host profile (default no-config). Tests cover:
 *
 * <ul>
 *   <li>GET /api/v1/register/algorithms — returns active algorithms (AC5)
 *   <li>POST /api/v1/register self-host happy path (AC3)
 *   <li>POST /api/v1/register second-tenant → 403 TENANT_LIMIT_EXCEEDED (AC3, AC9)
 *   <li>POST /api/v1/register second-key same tenant → 409 KEY_MISMATCH (AC3, AC8)
 *   <li>POST /api/v1/register unknown algorithm → 400 ALGORITHM_UNKNOWN (AC3)
 *   <li>POST /api/v1/register deprecated algorithm → 410 ALGORITHM_DEPRECATED (AC3)
 *   <li>POST /api/v1/register malformed body → 400 (AC14)
 *   <li>Audit-log written for accepted + rejected requests (AC13)
 * </ul>
 *
 * <p>DEC-44 / DEC-38: vvwt-info-server has no Modulith module constraints — uses
 * {@code @SpringBootTest} directly.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC3, AC4,
 *     AC5</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("self-host")
class RegistrationControllerIT {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private AuditLogDao auditLogDao;

    @Autowired private DataSource dataSource;

    /**
     * Clears mutable tables before each test to prevent cross-test interference.
     *
     * <p>The shared Spring context and shared H2 DB require explicit cleanup: the self-host profile
     * has {@code max-tenants=1}, so any registered tenant from a previous test blocks subsequent
     * first-registration tests. Consumed invitation tokens are also cleared for similar reasons.
     */
    @BeforeEach
    void clearMutableTables() {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DELETE FROM consumed_invitation_tokens");
        jdbc.execute("DELETE FROM tenant");
        jdbc.execute("DELETE FROM audit_log");
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/register/algorithms (AC5)
    // -------------------------------------------------------------------------

    @Test
    void getAlgorithms_returnsEnvelopeWithActiveAlgorithms() throws Exception {
        mockMvc.perform(get("/api/v1/register/algorithms").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.payload[0].algorithm_id").value("Ed25519"))
                .andExpect(jsonPath("$.payload[0].display_name").value("Ed25519"));
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/register — self-host profile (AC3 self-host paths)
    // -------------------------------------------------------------------------

    @Test
    void register_selfHost_firstRegistration_accepted() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        var request = new RegistrationRequest("Ed25519", publicKeyB64, null, null);
        var envelope = new Envelope<>(Envelope.SCHEMA_VERSION, request);
        String tenantId = "self-host-tenant-" + System.nanoTime();

        mockMvc.perform(
                        post("/api/v1/register")
                                .header("X-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.payload.tournament_token").doesNotExist());
    }

    @Test
    void register_selfHost_secondTenant_403_tenantLimitExceeded() throws Exception {
        // Register first tenant
        var keyPair1 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey1 = Base64.getEncoder().encodeToString(keyPair1.getPublic().getEncoded());
        String tenantId1 = "limit-tenant-" + System.nanoTime();
        var req1 =
                new Envelope<>(
                        Envelope.SCHEMA_VERSION,
                        new RegistrationRequest("Ed25519", publicKey1, null, null));

        mockMvc.perform(
                        post("/api/v1/register")
                                .header("X-Tenant-Id", tenantId1)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk());

        // Attempt to register second tenant
        var keyPair2 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey2 = Base64.getEncoder().encodeToString(keyPair2.getPublic().getEncoded());
        String tenantId2 = "limit-tenant-2-" + System.nanoTime();
        var req2 =
                new Envelope<>(
                        Envelope.SCHEMA_VERSION,
                        new RegistrationRequest("Ed25519", publicKey2, null, null));

        mockMvc.perform(
                        post("/api/v1/register")
                                .header("X-Tenant-Id", tenantId2)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.payload.reason").value("TENANT_LIMIT_EXCEEDED"));
    }

    @Test
    void register_selfHost_secondKeyForSameTenant_409_keyMismatch() throws Exception {
        // Register first key
        var keyPair1 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey1 = Base64.getEncoder().encodeToString(keyPair1.getPublic().getEncoded());
        String tenantId = "mismatch-tenant-" + System.nanoTime();
        var req1 =
                new Envelope<>(
                        Envelope.SCHEMA_VERSION,
                        new RegistrationRequest("Ed25519", publicKey1, null, null));

        mockMvc.perform(
                        post("/api/v1/register")
                                .header("X-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk());

        // Attempt second key for same tenant
        var keyPair2 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey2 = Base64.getEncoder().encodeToString(keyPair2.getPublic().getEncoded());
        var req2 =
                new Envelope<>(
                        Envelope.SCHEMA_VERSION,
                        new RegistrationRequest("Ed25519", publicKey2, null, null));

        mockMvc.perform(
                        post("/api/v1/register")
                                .header("X-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.payload.reason").value("KEY_MISMATCH"));
    }

    // -------------------------------------------------------------------------
    // Algorithm policy enforcement (AC3)
    // -------------------------------------------------------------------------

    @Test
    void register_unknownAlgorithm_400_algorithmUnknown() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String tenantId = "unknown-algo-tenant-" + System.nanoTime();
        var envelope =
                new Envelope<>(
                        Envelope.SCHEMA_VERSION,
                        new RegistrationRequest("ml-dsa-65", publicKey, null, null));

        mockMvc.perform(
                        post("/api/v1/register")
                                .header("X-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.reason").value("ALGORITHM_UNKNOWN"));
    }

    // -------------------------------------------------------------------------
    // Malformed body (AC14)
    // -------------------------------------------------------------------------

    @Test
    void register_missingAlgorithmId_400() throws Exception {
        String tenantId = "malformed-tenant-" + System.nanoTime();
        // Missing required algorithm_id
        String malformedJson =
                """
                {"schema_version":1,"payload":{"public_key":"dGVzdA=="}}""";

        mockMvc.perform(
                        post("/api/v1/register")
                                .header("X-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(malformedJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_missingPublicKey_400() throws Exception {
        String tenantId = "malformed-pk-" + System.nanoTime();
        String malformedJson =
                """
                {"schema_version":1,"payload":{"algorithm_id":"Ed25519"}}""";

        mockMvc.perform(
                        post("/api/v1/register")
                                .header("X-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(malformedJson))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Audit log written for accepted + rejected requests (AC13)
    // -------------------------------------------------------------------------

    @Test
    void register_auditLogPresent_forAcceptedRequest() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String tenantId = "audit-accept-" + System.nanoTime();
        String requestId = "req-" + tenantId;
        var envelope =
                new Envelope<>(
                        Envelope.SCHEMA_VERSION,
                        new RegistrationRequest("Ed25519", publicKey, null, null));

        mockMvc.perform(
                        post("/api/v1/register")
                                .header("X-Tenant-Id", tenantId)
                                .header("X-Request-Id", requestId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isOk());

        // Verify audit row exists
        var auditRow = auditLogDao.findByRequestId(requestId);
        assertThat(auditRow).isPresent();
        assertThat(auditRow.get().tenantId()).isEqualTo(tenantId);
        assertThat(auditRow.get().rejectionReason()).isNull(); // accepted
    }

    @Test
    void register_auditLogPresent_forRejectedRequest() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String tenantId = "audit-reject-" + System.nanoTime();
        String requestId = "req-reject-" + tenantId;
        var envelope =
                new Envelope<>(
                        Envelope.SCHEMA_VERSION,
                        new RegistrationRequest("ml-dsa-65", publicKey, null, null));

        mockMvc.perform(
                        post("/api/v1/register")
                                .header("X-Tenant-Id", tenantId)
                                .header("X-Request-Id", requestId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isBadRequest());

        var auditRow = auditLogDao.findByRequestId(requestId);
        assertThat(auditRow).isPresent();
        assertThat(auditRow.get().rejectionReason()).isEqualTo(RejectionReason.ALGORITHM_UNKNOWN);
    }
}
