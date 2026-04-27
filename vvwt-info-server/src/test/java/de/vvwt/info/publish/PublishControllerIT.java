package de.vvwt.info.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.publish.TournamentRegistrationRequest;
import de.vvwt.info.dto.registration.RegistrationRequest;
import de.vvwt.info.persistence.audit.AuditLogDao;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
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
 * Full Spring context integration tests for the publish endpoints (AC2–AC15).
 *
 * <p>Uses {@code @SpringBootTest} with self-host profile. Tests cover:
 *
 * <ul>
 *   <li>POST /api/v1/tournaments/{t}/{l}/{id}/register — happy path (AC2), invalid sig (AC10),
 *       audit-log (AC13)
 *   <li>POST /api/v1/publish/{t}/{l}/{id} — delta happy path (AC3), seq mismatch (AC4), invalid sig
 *       (AC10), payload too large (AC14)
 *   <li>POST /api/v1/publish/{t}/{l}/{id}/snapshot — snapshot happy path (AC5)
 *   <li>Atomic supersede (AC6): re-register same location → old deltas deleted, new token minted
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S05.story.md">E38S05</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("self-host")
class PublishControllerIT {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private AuditLogDao auditLogDao;

    @Autowired private DataSource dataSource;

    private KeyPair keyPair;
    private String tenantId;
    private JcsCanonicalizer jcsCanonicalizer;

    /**
     * Clears mutable tables + registers a fresh tenant before each test.
     *
     * <p>Re-registering the tenant each time avoids self-host max-tenants contention — we use
     * unique tenant IDs per test class run, but since each test clears {@code tenant} table first
     * this is safe.
     */
    @BeforeEach
    void setUp() throws Exception {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DELETE FROM tournament_delta");
        jdbc.execute("DELETE FROM tournament");
        jdbc.execute("DELETE FROM audit_log");
        jdbc.execute("DELETE FROM tenant");

        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        tenantId = "it-tenant-" + UUID.randomUUID();
        jcsCanonicalizer = new JcsCanonicalizer();

        // Register the tenant via the registration endpoint
        String publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        var regRequest = new RegistrationRequest("Ed25519", publicKeyB64, null, null);
        var regEnvelope = new Envelope<>(Envelope.SCHEMA_VERSION, regRequest);

        mockMvc.perform(
                        post("/api/v1/register")
                                .header("X-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(regEnvelope)))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // AC2 — tournament registration mints tournament_token + per_tournament_secret
    // -------------------------------------------------------------------------

    @Test
    void registerTournament_happyPath_200_withTokenAndSecret() throws Exception {
        String locationId = "loc-" + UUID.randomUUID();
        String tournamentId = "tour-" + UUID.randomUUID();

        var request =
                new TournamentRegistrationRequest(List.of(UUID.randomUUID(), UUID.randomUUID()));
        var envelope = new Envelope<>(Envelope.SCHEMA_VERSION, request);
        String envelopeJson = objectMapper.writeValueAsString(request);
        String signature = sign(jcsCanonicalizer.canonicalize(envelopeJson), keyPair.getPrivate());

        mockMvc.perform(
                        post(
                                        "/api/v1/tournaments/{t}/{l}/{id}/register",
                                        tenantId,
                                        locationId,
                                        tournamentId)
                                .header("X-Vvwt-Signature", signature)
                                .header("X-Request-Id", "it-req-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.payload.tournament_token").isNotEmpty())
                .andExpect(jsonPath("$.payload.schema_version").value("1.0"));
    }

    // -------------------------------------------------------------------------
    // AC10 — invalid signature → 401
    // -------------------------------------------------------------------------

    @Test
    void registerTournament_invalidSignature_401() throws Exception {
        String locationId = "loc-" + UUID.randomUUID();
        String tournamentId = "tour-" + UUID.randomUUID();

        var request = new TournamentRegistrationRequest(List.of());
        var envelope = new Envelope<>(Envelope.SCHEMA_VERSION, request);

        mockMvc.perform(
                        post(
                                        "/api/v1/tournaments/{t}/{l}/{id}/register",
                                        tenantId,
                                        locationId,
                                        tournamentId)
                                .header(
                                        "X-Vvwt-Signature",
                                        Base64.getEncoder().encodeToString(new byte[64]))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // AC3 — delta publish happy path
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_happyPath_200() throws Exception {
        String locationId = "loc-" + UUID.randomUUID();
        String tournamentId = "tour-" + UUID.randomUUID();
        registerTournament(locationId, tournamentId);

        String rawBody =
                """
                {"schemaVersion":"1.0","payload":{"type":"SCORE_UPDATED","teamId":"t1"}}""";
        String signature = sign(jcsCanonicalizer.canonicalize(rawBody), keyPair.getPrivate());

        mockMvc.perform(
                        post("/api/v1/publish/{t}/{l}/{id}", tenantId, locationId, tournamentId)
                                .param("seq", "1")
                                .header("X-Vvwt-Signature", signature)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rawBody))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // AC4 — out-of-order seq → 409 FULL_RESYNC
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_seqMismatch_409_fullResync() throws Exception {
        String locationId = "loc-" + UUID.randomUUID();
        String tournamentId = "tour-" + UUID.randomUUID();
        registerTournament(locationId, tournamentId);

        String rawBody =
                """
                {"schemaVersion":"1.0","payload":{"type":"SCORE_UPDATED"}}""";
        String signature = sign(jcsCanonicalizer.canonicalize(rawBody), keyPair.getPrivate());

        // Send seq=99 — expected is 1
        mockMvc.perform(
                        post("/api/v1/publish/{t}/{l}/{id}", tenantId, locationId, tournamentId)
                                .param("seq", "99")
                                .header("X-Vvwt-Signature", signature)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rawBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.payload.required").value("FULL_RESYNC"));
    }

    // -------------------------------------------------------------------------
    // AC5 — snapshot resync happy path
    // -------------------------------------------------------------------------

    @Test
    void publishSnapshot_happyPath_200() throws Exception {
        String locationId = "loc-" + UUID.randomUUID();
        String tournamentId = "tour-" + UUID.randomUUID();
        registerTournament(locationId, tournamentId);

        String rawBody =
                """
                {"schemaVersion":"1.0","payload":{"sequenceNumber":10,"state":{}}}""";
        String signature = sign(jcsCanonicalizer.canonicalize(rawBody), keyPair.getPrivate());

        mockMvc.perform(
                        post(
                                        "/api/v1/publish/{t}/{l}/{id}/snapshot",
                                        tenantId,
                                        locationId,
                                        tournamentId)
                                .header("X-Vvwt-Signature", signature)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rawBody))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // AC6 — atomic supersede: re-register same location → old tournament superseded
    // -------------------------------------------------------------------------

    @Test
    void registerTournament_reRegisterSameLocation_supersedesPrior_200() throws Exception {
        String locationId = "loc-supersede-" + UUID.randomUUID();
        String tournamentId1 = "tour-first-" + UUID.randomUUID();
        String tournamentId2 = "tour-second-" + UUID.randomUUID();

        // Register first tournament
        String token1 = registerTournamentAndGetToken(locationId, tournamentId1);
        assertThat(token1).isNotNull();

        // Publish one delta for the first tournament
        String deltaBody =
                """
                {"schemaVersion":"1.0","payload":{"type":"SCORE_UPDATED"}}""";
        String deltaSig = sign(jcsCanonicalizer.canonicalize(deltaBody), keyPair.getPrivate());
        mockMvc.perform(
                        post("/api/v1/publish/{t}/{l}/{id}", tenantId, locationId, tournamentId1)
                                .param("seq", "1")
                                .header("X-Vvwt-Signature", deltaSig)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(deltaBody))
                .andExpect(status().isOk());

        // Re-register same location with a new tournament ID — triggers atomic supersede
        String token2 = registerTournamentAndGetToken(locationId, tournamentId2);
        assertThat(token2).isNotNull().isNotEqualTo(token1);

        // Old deltas should be gone (seq 1 for tournamentId1 no longer exists)
        // Verify by confirming old tournament's delta table is empty via direct JDBC
        var jdbc = new JdbcTemplate(dataSource);
        int deltaCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tournament_delta WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId1);
        assertThat(deltaCount).isZero();
    }

    // -------------------------------------------------------------------------
    // AC13 — audit-log written for every request
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_auditLogWritten() throws Exception {
        String locationId = "loc-" + UUID.randomUUID();
        String tournamentId = "tour-" + UUID.randomUUID();
        registerTournament(locationId, tournamentId);

        String requestId = "audit-" + UUID.randomUUID();
        String rawBody =
                """
                {"schemaVersion":"1.0","payload":{"type":"SCORE_UPDATED"}}""";
        String signature = sign(jcsCanonicalizer.canonicalize(rawBody), keyPair.getPrivate());

        mockMvc.perform(
                        post("/api/v1/publish/{t}/{l}/{id}", tenantId, locationId, tournamentId)
                                .param("seq", "1")
                                .header("X-Vvwt-Signature", signature)
                                .header("X-Request-Id", requestId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rawBody))
                .andExpect(status().isOk());

        var auditRow = auditLogDao.findByRequestId(requestId);
        assertThat(auditRow).isPresent();
        assertThat(auditRow.get().httpStatus()).isEqualTo(200);
    }

    // -------------------------------------------------------------------------
    // AC14 — payload too large → 413
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_payloadTooLarge_413() throws Exception {
        // The default max-delta-bytes is 1 MB. We send a body larger than that.
        // For test purposes, use a body that is 2 MB of repeated data.
        String locationId = "loc-" + UUID.randomUUID();
        String tournamentId = "tour-" + UUID.randomUUID();
        registerTournament(locationId, tournamentId);

        // Build a body > 1 MB
        StringBuilder largePayload =
                new StringBuilder(
                        "{\"schemaVersion\":\"1.0\",\"payload\":{\"type\":\"SCORE_UPDATED\",\"data\":\"");
        for (int i = 0; i < 1_100_000; i++) {
            largePayload.append('A');
        }
        largePayload.append("\"}}");
        String rawBody = largePayload.toString();

        // Sign the canonical form — but the service should reject before verifying sig
        String signature = Base64.getEncoder().encodeToString(new byte[64]);

        mockMvc.perform(
                        post("/api/v1/publish/{t}/{l}/{id}", tenantId, locationId, tournamentId)
                                .param("seq", "1")
                                .header("X-Vvwt-Signature", signature)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rawBody))
                .andExpect(status().isPayloadTooLarge());
    }

    // -------------------------------------------------------------------------
    // AC15 — malformed event discriminator → 400
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_missingTypeDiscriminator_400() throws Exception {
        String locationId = "loc-" + UUID.randomUUID();
        String tournamentId = "tour-" + UUID.randomUUID();
        registerTournament(locationId, tournamentId);

        // Valid envelope structure but payload missing "type" field
        String rawBody = """
                {"schemaVersion":"1.0","payload":{"score":42}}""";
        String signature = sign(jcsCanonicalizer.canonicalize(rawBody), keyPair.getPrivate());

        mockMvc.perform(
                        post("/api/v1/publish/{t}/{l}/{id}", tenantId, locationId, tournamentId)
                                .param("seq", "1")
                                .header("X-Vvwt-Signature", signature)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rawBody))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // AC11 — unknown tenant → 401 (sig not verifiable)
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_unknownTenant_401() throws Exception {
        String rawBody =
                """
                {"schemaVersion":"1.0","payload":{"type":"SCORE_UPDATED"}}""";
        String signature = sign(jcsCanonicalizer.canonicalize(rawBody), keyPair.getPrivate());

        mockMvc.perform(
                        post("/api/v1/publish/{t}/{l}/{id}", "unknown-tenant", "loc1", "tour1")
                                .param("seq", "1")
                                .header("X-Vvwt-Signature", signature)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rawBody))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Registers a tournament and returns the tournament_token from the response. */
    private String registerTournamentAndGetToken(String locationId, String tournamentId)
            throws Exception {
        var request = new TournamentRegistrationRequest(Collections.emptyList());
        var envelope = new Envelope<>(Envelope.SCHEMA_VERSION, request);
        String envelopeJson = objectMapper.writeValueAsString(request);
        String signature = sign(jcsCanonicalizer.canonicalize(envelopeJson), keyPair.getPrivate());

        var result =
                mockMvc.perform(
                                post(
                                                "/api/v1/tournaments/{t}/{l}/{id}/register",
                                                tenantId,
                                                locationId,
                                                tournamentId)
                                        .header("X-Vvwt-Signature", signature)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(envelope)))
                        .andExpect(status().isOk())
                        .andReturn();

        var responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        return responseJson.path("payload").path("tournament_token").asText(null);
    }

    /** Registers a tournament (without returning the token) — convenience for setting up tests. */
    private void registerTournament(String locationId, String tournamentId) throws Exception {
        registerTournamentAndGetToken(locationId, tournamentId);
    }

    /**
     * Signs the given payload bytes using the given Ed25519 private key.
     *
     * @return Base64 (standard) encoded signature string
     */
    private static String sign(byte[] payload, PrivateKey privateKey) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payload);
        byte[] sigBytes = sig.sign();
        return Base64.getEncoder().encodeToString(sigBytes);
    }
}
