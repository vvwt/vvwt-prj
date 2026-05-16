package de.vvwt.info.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.info.crypto.SignatureVerifier;
import de.vvwt.info.crypto.SignatureVerifierRegistry;
import de.vvwt.info.dto.publish.TournamentRegistrationRequest;
import de.vvwt.info.dto.publish.TournamentRegistrationResponse;
import de.vvwt.info.persistence.audit.AuditLogDao;
import de.vvwt.info.persistence.audit.AuditLogRecord;
import de.vvwt.info.persistence.tenant.TenantDao;
import de.vvwt.info.persistence.tenant.TenantRecord;
import de.vvwt.info.persistence.tournament.TournamentDao;
import de.vvwt.info.persistence.tournament.TournamentDeltaDao;
import de.vvwt.info.persistence.tournament.TournamentRecord;
import de.vvwt.info.publish.config.PublishProperties;
import de.vvwt.info.publish.internal.DefaultJcsCanonicalizer;
import de.vvwt.info.publish.internal.DefaultPublishService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PublishService} — per-request signature verification, seq monotonicity,
 * supersede, payload-size guards.
 *
 * <p>DEC-22 Iron Law: tests written RED-first before production code. Uses Mockito for
 * infrastructure collaborators (DAO, registry) per DEC-36 (cross-package tests use interfaces).
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S05.story.md">E38S05</a>
 */
class PublishServiceTest {

    private TenantDao tenantDao;
    private TournamentDao tournamentDao;
    private TournamentDeltaDao tournamentDeltaDao;
    private AuditLogDao auditLogDao;
    private SignatureVerifierRegistry signatureVerifierRegistry;
    private SignatureVerifier signatureVerifier;
    private JcsCanonicalizer jcsCanonicalizer;
    private PublishProperties publishProperties;
    private Clock clock;
    private PublishService service;

    @BeforeEach
    void setUp() {
        tenantDao = mock(TenantDao.class);
        tournamentDao = mock(TournamentDao.class);
        tournamentDeltaDao = mock(TournamentDeltaDao.class);
        auditLogDao = mock(AuditLogDao.class);
        signatureVerifierRegistry = mock(SignatureVerifierRegistry.class);
        signatureVerifier = mock(SignatureVerifier.class);
        jcsCanonicalizer = new DefaultJcsCanonicalizer();
        publishProperties = new PublishProperties();
        clock = Clock.fixed(Instant.parse("2026-04-27T10:00:00Z"), ZoneOffset.UTC);

        when(signatureVerifierRegistry.resolve(anyString())).thenReturn(signatureVerifier);
        when(auditLogDao.append(any())).thenAnswer(inv -> inv.getArgument(0));

        service =
                new DefaultPublishService(
                        tenantDao,
                        tournamentDao,
                        tournamentDeltaDao,
                        auditLogDao,
                        signatureVerifierRegistry,
                        jcsCanonicalizer,
                        publishProperties,
                        clock);
    }

    // -------------------------------------------------------------------------
    // AC10 — invalid signature → 401 SignatureInvalidException
    // -------------------------------------------------------------------------

    @Test
    void registerTournament_invalidSignature_returnsSignatureInvalidException() throws Exception {
        TenantRecord tenant = fakeTenant("t1", "Ed25519");
        when(tenantDao.findById("t1")).thenReturn(Optional.of(tenant));
        when(signatureVerifier.verify(any(), any(), any())).thenReturn(false);

        var request = new TournamentRegistrationRequest(List.of());
        String envelopeJson = "{}";

        Object result =
                service.registerTournament(
                        "t1",
                        "loc1",
                        "tour1",
                        envelopeJson,
                        request,
                        "aW52YWxpZA==",
                        "req-1",
                        "127.0.0.1");

        assertThat(result).isInstanceOf(PublishService.SignatureInvalidException.class);
    }

    @Test
    void registerTournament_tenantNotFound_returnsSignatureInvalidException() {
        when(tenantDao.findById("t-unknown")).thenReturn(Optional.empty());
        var request = new TournamentRegistrationRequest(List.of());

        Object result =
                service.registerTournament(
                        "t-unknown", "loc1", "tour1", "{}", request, "sig", "req-2", "127.0.0.1");

        assertThat(result).isInstanceOf(PublishService.SignatureInvalidException.class);
    }

    // -------------------------------------------------------------------------
    // AC2 — successful tournament registration mints tournament_token + per_tournament_secret
    // -------------------------------------------------------------------------

    @Test
    void registerTournament_newTournament_returnsTournamentRegistrationResponse() throws Exception {
        TenantRecord tenant = fakeTenant("t1", "Ed25519");
        when(tenantDao.findById("t1")).thenReturn(Optional.of(tenant));
        when(tenantDao.findDefaultTenant()).thenReturn(Optional.of(tenant));
        when(signatureVerifier.verify(any(), any(), any())).thenReturn(true);
        when(tournamentDao.findActiveTournament("t1", "loc1")).thenReturn(Optional.empty());
        when(tournamentDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new TournamentRegistrationRequest(List.of(UUID.randomUUID()));
        String envelopeJson = "{}";

        Object result =
                service.registerTournament(
                        "t1",
                        "loc1",
                        "tour1",
                        envelopeJson,
                        request,
                        "dGVzdA==",
                        "req-3",
                        "127.0.0.1");

        assertThat(result).isInstanceOf(TournamentRegistrationResponse.class);
        TournamentRegistrationResponse resp = (TournamentRegistrationResponse) result;
        assertThat(resp.tournamentToken()).isNotNull().isNotEmpty();
        assertThat(resp.perTournamentSecret()).hasSize(32);
        assertThat(resp.schemaVersion()).isEqualTo("1.0");
    }

    // -------------------------------------------------------------------------
    // AC3 — delta publish happy path
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_happyPath_returnsNull() throws Exception {
        TenantRecord tenant = fakeTenant("t1", "Ed25519");
        TournamentRecord tournament = fakeTournament("t1", "loc1", "tour1", 5L);
        when(tenantDao.findById("t1")).thenReturn(Optional.of(tenant));
        when(signatureVerifier.verify(any(), any(), any())).thenReturn(true);
        when(tournamentDao.findById("tour1")).thenReturn(Optional.of(tournament));
        when(tournamentDeltaDao.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tournamentDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Object result =
                service.publishDelta(
                        "t1",
                        "loc1",
                        "tour1",
                        "{\"schemaVersion\":\"1.0\",\"payload\":{\"type\":\"SCORE_UPDATED\"}}",
                        6L, // seq = last+1
                        "{\"type\":\"SCORE_UPDATED\"}",
                        "SCORE_UPDATED",
                        "dGVzdA==",
                        "req-4",
                        "127.0.0.1");

        assertThat(result).isNull();
    }

    // -------------------------------------------------------------------------
    // AC4 — out-of-order seq → SeqMismatchException
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_seqMismatch_returnsSeqMismatchException() throws Exception {
        TenantRecord tenant = fakeTenant("t1", "Ed25519");
        TournamentRecord tournament = fakeTournament("t1", "loc1", "tour1", 5L);
        when(tenantDao.findById("t1")).thenReturn(Optional.of(tenant));
        when(signatureVerifier.verify(any(), any(), any())).thenReturn(true);
        when(tournamentDao.findById("tour1")).thenReturn(Optional.of(tournament));

        Object result =
                service.publishDelta(
                        "t1",
                        "loc1",
                        "tour1",
                        "{}",
                        4L, // wrong seq (expected 6)
                        "{}",
                        "SCORE_UPDATED",
                        "dGVzdA==",
                        "req-5",
                        "127.0.0.1");

        assertThat(result).isInstanceOf(PublishService.SeqMismatchException.class);
        assertThat(((PublishService.SeqMismatchException) result).getLastAppliedSeq())
                .isEqualTo(5L);
    }

    // -------------------------------------------------------------------------
    // AC4 — unknown tournament → TournamentNotFoundException
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_unknownTournament_returnsTournamentNotFoundException() throws Exception {
        TenantRecord tenant = fakeTenant("t1", "Ed25519");
        when(tenantDao.findById("t1")).thenReturn(Optional.of(tenant));
        when(signatureVerifier.verify(any(), any(), any())).thenReturn(true);
        when(tournamentDao.findById("tour-unknown")).thenReturn(Optional.empty());

        Object result =
                service.publishDelta(
                        "t1",
                        "loc1",
                        "tour-unknown",
                        "{}",
                        1L,
                        "{}",
                        "SCORE_UPDATED",
                        "dGVzdA==",
                        "req-6",
                        "127.0.0.1");

        assertThat(result).isInstanceOf(PublishService.TournamentNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // AC14 — payload too large → PayloadTooLargeException
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_payloadTooLarge_returnsPayloadTooLargeException() throws Exception {
        publishProperties.setMaxDeltaBytes(10L); // tiny limit for test

        TenantRecord tenant = fakeTenant("t1", "Ed25519");
        when(tenantDao.findById("t1")).thenReturn(Optional.of(tenant));

        // Body longer than 10 bytes
        Object result =
                service.publishDelta(
                        "t1",
                        "loc1",
                        "tour1",
                        "{\"schemaVersion\":\"1.0\",\"payload\":{\"type\":\"SCORE_UPDATED\"}}",
                        1L,
                        "{}",
                        "SCORE_UPDATED",
                        "dGVzdA==",
                        "req-7",
                        "127.0.0.1");

        assertThat(result).isInstanceOf(PublishService.PayloadTooLargeException.class);
    }

    // -------------------------------------------------------------------------
    // AC5 — snapshot happy path
    // -------------------------------------------------------------------------

    @Test
    void publishSnapshot_happyPath_returnsNull() throws Exception {
        TenantRecord tenant = fakeTenant("t1", "Ed25519");
        TournamentRecord tournament = fakeTournament("t1", "loc1", "tour1", 5L);
        when(tenantDao.findById("t1")).thenReturn(Optional.of(tenant));
        when(signatureVerifier.verify(any(), any(), any())).thenReturn(true);
        when(tournamentDao.findById("tour1")).thenReturn(Optional.of(tournament));
        when(tournamentDeltaDao.findDeltasSince(anyString(), any(long.class)))
                .thenReturn(Collections.emptyList());
        when(tournamentDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Object result =
                service.publishSnapshot(
                        "t1",
                        "loc1",
                        "tour1",
                        "{\"schemaVersion\":\"1.0\",\"payload\":{\"sequenceNumber\":10}}",
                        10L,
                        "{\"sequenceNumber\":10}",
                        "dGVzdA==",
                        "req-8",
                        "127.0.0.1");

        assertThat(result).isNull();
    }

    // -------------------------------------------------------------------------
    // AC13 — audit log written for every request
    // -------------------------------------------------------------------------

    @Test
    void registerTournament_writesAuditLog() throws Exception {
        TenantRecord tenant = fakeTenant("t1", "Ed25519");
        when(tenantDao.findById("t1")).thenReturn(Optional.of(tenant));
        when(signatureVerifier.verify(any(), any(), any())).thenReturn(false);

        service.registerTournament(
                "t1",
                "loc1",
                "tour1",
                "{}",
                new TournamentRegistrationRequest(List.of()),
                "aW52YWxpZA==",
                "req-audit",
                "10.0.0.1");

        // Audit log should have been called (via writeAuditLog which calls auditLogDao.append)
        verify(auditLogDao).append(any(AuditLogRecord.class));
    }

    // -------------------------------------------------------------------------
    // AC11 — single-key guarantee: different tenant's sig is rejected
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_signedWithWrongKey_returnsSignatureInvalidException() throws Exception {
        TenantRecord tenant = fakeTenant("t1", "Ed25519");
        when(tenantDao.findById("t1")).thenReturn(Optional.of(tenant));
        when(signatureVerifier.verify(any(), any(), any())).thenReturn(false);

        Object result =
                service.publishDelta(
                        "t1",
                        "loc1",
                        "tour1",
                        "{}",
                        1L,
                        "{}",
                        "SCORE_UPDATED",
                        "d3JvbmdrZXk=",
                        "req-9",
                        "127.0.0.1");

        assertThat(result).isInstanceOf(PublishService.SignatureInvalidException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TenantRecord fakeTenant(String tenantId, String algorithmId) {
        return new TenantRecord(
                tenantId, new byte[32], algorithmId, LocalDateTime.now(clock), "ACTIVE", true);
    }

    private TournamentRecord fakeTournament(
            String tenantId, String locationId, String tournamentId, long lastSeq) {
        return new TournamentRecord(
                tournamentId,
                tenantId,
                locationId,
                "tok-" + tournamentId,
                new byte[32],
                null,
                lastSeq,
                LocalDateTime.now(clock),
                null);
    }
}
