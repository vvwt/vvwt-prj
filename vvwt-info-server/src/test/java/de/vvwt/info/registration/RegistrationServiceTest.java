package de.vvwt.info.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.info.dto.registration.AlgorithmDescriptor;
import de.vvwt.info.dto.registration.RegistrationRequest;
import de.vvwt.info.dto.registration.RegistrationResponse;
import de.vvwt.info.persistence.algorithm.AlgorithmRegistryDao;
import de.vvwt.info.persistence.algorithm.AlgorithmRegistryRecord;
import de.vvwt.info.persistence.audit.AuditLogDao;
import de.vvwt.info.persistence.audit.AuditLogRecord;
import de.vvwt.info.persistence.audit.RejectionReason;
import de.vvwt.info.persistence.audit.SignatureOutcome;
import de.vvwt.info.persistence.tenant.TenantDao;
import de.vvwt.info.persistence.tenant.TenantRecord;
import de.vvwt.info.registration.config.RegistrationProperties;
import de.vvwt.info.registration.internal.DefaultRegistrationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link RegistrationService}.
 *
 * <p>DEC-22 Q-1a TDD RED-first (AC1). Service unit tests isolate business logic from persistence
 * using mocks. All acceptance-criterion paths are covered here; RegistrationControllerIT covers the
 * full Spring context integration.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC1,
 *     AC8–AC14</a>
 */
class RegistrationServiceTest {

    private AlgorithmRegistryDao algorithmRegistryDao;
    private TenantDao tenantDao;
    private AuditLogDao auditLogDao;
    private InvitationTokenPool tokenPool;
    private RegistrationService service;
    private Clock fixedClock;

    private static final String ENCODED_PUBLIC_KEY =
            Base64.getEncoder().encodeToString(new byte[44]);

    /** Convenience factory for RegistrationRequest with invitation_token. */
    private static RegistrationRequest req(String algorithmId, String publicKey, String invToken) {
        return new RegistrationRequest(algorithmId, publicKey, null, invToken);
    }

    private static RegistrationRequest req(String algorithmId) {
        return req(algorithmId, ENCODED_PUBLIC_KEY, null);
    }

    @BeforeEach
    void setUp() {
        algorithmRegistryDao = mock(AlgorithmRegistryDao.class);
        tenantDao = mock(TenantDao.class);
        auditLogDao = mock(AuditLogDao.class);
        tokenPool = mock(InvitationTokenPool.class);
        fixedClock = Clock.fixed(Instant.parse("2026-04-27T12:00:00Z"), ZoneOffset.UTC);

        var props = new RegistrationProperties();
        props.getRegistration().setMode("OPEN_FCFS");
        props.getTenant().setMaxTenants(1);

        service =
                new DefaultRegistrationService(
                        algorithmRegistryDao, tenantDao, auditLogDao, tokenPool, props, fixedClock);

        when(auditLogDao.append(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // GET /algorithms
    // -------------------------------------------------------------------------

    @Test
    void listAlgorithms_returnsActiveAlgorithmsOrderedByAlgorithmId() {
        var ed25519 = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        when(algorithmRegistryDao.findAll()).thenReturn(List.of(ed25519));

        List<AlgorithmDescriptor> result = service.listAlgorithms();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).algorithm_id()).isEqualTo("Ed25519");
    }

    @Test
    void listAlgorithms_excludesInactiveAlgorithms() {
        var active = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        var inactive = new AlgorithmRegistryRecord("RSA", "RSA-2048", null, false, null);
        when(algorithmRegistryDao.findAll()).thenReturn(List.of(active, inactive));

        List<AlgorithmDescriptor> result = service.listAlgorithms();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).algorithm_id()).isEqualTo("Ed25519");
    }

    @Test
    void listAlgorithms_orderedByAlgorithmId() {
        var b = new AlgorithmRegistryRecord("Zzz", "Zzz", null, true, null);
        var a = new AlgorithmRegistryRecord("Aaa", "Aaa", null, true, null);
        when(algorithmRegistryDao.findAll()).thenReturn(List.of(b, a));

        List<AlgorithmDescriptor> result = service.listAlgorithms();

        assertThat(result)
                .extracting(AlgorithmDescriptor::algorithm_id)
                .containsExactly("Aaa", "Zzz");
    }

    // -------------------------------------------------------------------------
    // POST /register — Algorithm validation
    // -------------------------------------------------------------------------

    @Test
    void register_unknownAlgorithm_throwsAlgorithmUnknownException() {
        when(algorithmRegistryDao.findById("UNKNOWN")).thenReturn(Optional.empty());

        assertThat(service.register("tenant-1", req("UNKNOWN"), "127.0.0.1", null))
                .isInstanceOf(RegistrationService.AlgorithmUnknownException.class);
    }

    @Test
    void register_deprecatedAlgorithm_throwsAlgorithmDeprecatedException() {
        // Deprecated: deprecation_date is yesterday (past DEC-48 boundary)
        var yesterday = LocalDate.now(fixedClock).minusDays(1);
        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", yesterday, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));

        assertThat(service.register("tenant-1", req("Ed25519"), "127.0.0.1", null))
                .isInstanceOf(RegistrationService.AlgorithmDeprecatedException.class);
    }

    @Test
    void register_algorithmDeprecatedToday_isAccepted_dec48() {
        // DEC-48: deprecation_date = today → accepted (entire deprecation day is the LAST day)
        var today = LocalDate.now(fixedClock);
        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", today, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));
        when(tenantDao.findById("tenant-1")).thenReturn(Optional.empty());
        when(tenantDao.count()).thenReturn(0L);
        when(tenantDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Object result = service.register("tenant-1", req("Ed25519"), "127.0.0.1", null);

        assertThat(result).isInstanceOf(RegistrationResponse.class);
    }

    @Test
    void register_algorithmDeprecatedTomorrow_isAccepted() {
        var tomorrow = LocalDate.now(fixedClock).plusDays(1);
        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", tomorrow, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));
        when(tenantDao.findById("tenant-1")).thenReturn(Optional.empty());
        when(tenantDao.count()).thenReturn(0L);
        when(tenantDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Object result = service.register("tenant-1", req("Ed25519"), "127.0.0.1", null);

        assertThat(result).isInstanceOf(RegistrationResponse.class);
    }

    // -------------------------------------------------------------------------
    // POST /register — First-key-wins (AC8)
    // -------------------------------------------------------------------------

    @Test
    void register_secondKeyForSameTenant_throwsKeyMismatchException() {
        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));

        byte[] existingKey = new byte[44];
        var existing =
                new TenantRecord(
                        "tenant-1", existingKey, "Ed25519", LocalDateTime.now(), "ACTIVE", false);
        when(tenantDao.findById("tenant-1")).thenReturn(Optional.of(existing));

        byte[] newKey = new byte[44];
        newKey[0] = 1;
        var request = req("Ed25519", Base64.getEncoder().encodeToString(newKey), null);

        assertThat(service.register("tenant-1", request, "127.0.0.1", null))
                .isInstanceOf(RegistrationService.KeyMismatchException.class);
    }

    @Test
    void register_sameKeyForSameTenant_returnsSuccess() {
        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));

        byte[] key = new byte[44];
        var existing =
                new TenantRecord("tenant-1", key, "Ed25519", LocalDateTime.now(), "ACTIVE", false);
        when(tenantDao.findById("tenant-1")).thenReturn(Optional.of(existing));

        var request = req("Ed25519", Base64.getEncoder().encodeToString(key), null);
        Object result = service.register("tenant-1", request, "127.0.0.1", null);

        assertThat(result).isInstanceOf(RegistrationResponse.class);
    }

    // -------------------------------------------------------------------------
    // POST /register — Self-host single-tenant rejection (AC9)
    // -------------------------------------------------------------------------

    @Test
    void register_selfHost_secondTenant_throwsTenantLimitExceededException() {
        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));
        when(tenantDao.findById("tenant-2")).thenReturn(Optional.empty());
        when(tenantDao.count()).thenReturn(1L);

        assertThat(service.register("tenant-2", req("Ed25519"), "127.0.0.1", null))
                .isInstanceOf(RegistrationService.TenantLimitExceededException.class);
    }

    @Test
    void register_selfHost_firstTenant_accepted() {
        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));
        when(tenantDao.findById("tenant-1")).thenReturn(Optional.empty());
        when(tenantDao.count()).thenReturn(0L);
        when(tenantDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Object result = service.register("tenant-1", req("Ed25519"), "127.0.0.1", null);

        assertThat(result).isInstanceOf(RegistrationResponse.class);
        verify(tenantDao).save(any());
    }

    // -------------------------------------------------------------------------
    // POST /register — Audit-log (AC13)
    // -------------------------------------------------------------------------

    @Test
    void register_accepted_auditLogWrittenWithSignatureOutcomeNA() {
        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));
        when(tenantDao.findById("tenant-1")).thenReturn(Optional.empty());
        when(tenantDao.count()).thenReturn(0L);
        when(tenantDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.register("tenant-1", req("Ed25519"), "127.0.0.1", "req-123");

        var captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogDao).append(captor.capture());
        assertThat(captor.getValue().signatureOutcome()).isEqualTo(SignatureOutcome.NA);
        assertThat(captor.getValue().rejectionReason()).isNull();
        assertThat(captor.getValue().tenantId()).isEqualTo("tenant-1");
        assertThat(captor.getValue().requestId()).isEqualTo("req-123");
    }

    @Test
    void register_rejected_algorithmUnknown_auditLogWrittenWithAlgorithmUnknownReason() {
        when(algorithmRegistryDao.findById("UNKNOWN")).thenReturn(Optional.empty());

        service.register("tenant-1", req("UNKNOWN"), "127.0.0.1", null);

        var captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogDao).append(captor.capture());
        assertThat(captor.getValue().rejectionReason())
                .isEqualTo(RejectionReason.ALGORITHM_UNKNOWN);
    }

    // -------------------------------------------------------------------------
    // POST /register — Algorithm warning (AC12)
    // -------------------------------------------------------------------------

    @Test
    void register_algorithmWithFutureDeprecation_includesAlgorithmWarning() {
        var futureDate = LocalDate.now(fixedClock).plusDays(30);
        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", futureDate, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));
        when(tenantDao.findById("tenant-1")).thenReturn(Optional.empty());
        when(tenantDao.count()).thenReturn(0L);
        when(tenantDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Object result = service.register("tenant-1", req("Ed25519"), "127.0.0.1", null);

        assertThat(result).isInstanceOf(RegistrationResponse.class);
        var response = (RegistrationResponse) result;
        assertThat(response.algorithm_warning()).isNotNull();
        assertThat(response.algorithm_warning().algorithm_id()).isEqualTo("Ed25519");
        assertThat(response.algorithm_warning().deprecation_date()).isEqualTo(futureDate);
        assertThat(response.algorithm_warning().days_remaining()).isEqualTo(30);
    }

    @Test
    void register_algorithmNoDeprecation_noAlgorithmWarning() {
        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));
        when(tenantDao.findById("tenant-1")).thenReturn(Optional.empty());
        when(tenantDao.count()).thenReturn(0L);
        when(tenantDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Object result = service.register("tenant-1", req("Ed25519"), "127.0.0.1", null);

        assertThat(result).isInstanceOf(RegistrationResponse.class);
        assertThat(((RegistrationResponse) result).algorithm_warning()).isNull();
    }

    // -------------------------------------------------------------------------
    // POST /register — Primary profile (INVITATION_ONLY) (AC10)
    // -------------------------------------------------------------------------

    @Test
    void register_primaryProfile_validInvitationToken_accepted() {
        var props = new RegistrationProperties();
        props.getRegistration().setMode("INVITATION_ONLY");
        props.getTenant().setMaxTenants(-1);
        service =
                new DefaultRegistrationService(
                        algorithmRegistryDao, tenantDao, auditLogDao, tokenPool, props, fixedClock);
        when(auditLogDao.append(any())).thenAnswer(inv -> inv.getArgument(0));

        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));
        when(tenantDao.findById("tenant-1")).thenReturn(Optional.empty());
        when(tenantDao.count()).thenReturn(0L);
        when(tenantDao.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tokenPool.isAvailable("valid-token")).thenReturn(true);

        var request = req("Ed25519", ENCODED_PUBLIC_KEY, "valid-token");
        Object result = service.register("tenant-1", request, "127.0.0.1", null);

        assertThat(result).isInstanceOf(RegistrationResponse.class);
        verify(tokenPool).consumeToken(eq("valid-token"), eq("tenant-1"));
    }

    @Test
    void register_primaryProfile_invalidInvitationToken_throwsInvitationInvalidException() {
        var props = new RegistrationProperties();
        props.getRegistration().setMode("INVITATION_ONLY");
        props.getTenant().setMaxTenants(-1);
        service =
                new DefaultRegistrationService(
                        algorithmRegistryDao, tenantDao, auditLogDao, tokenPool, props, fixedClock);
        when(auditLogDao.append(any())).thenAnswer(inv -> inv.getArgument(0));

        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));
        when(tenantDao.findById("tenant-1")).thenReturn(Optional.empty());
        when(tokenPool.isAvailable("bad-token")).thenReturn(false);

        var request = req("Ed25519", ENCODED_PUBLIC_KEY, "bad-token");

        assertThat(service.register("tenant-1", request, "127.0.0.1", null))
                .isInstanceOf(RegistrationService.InvitationInvalidException.class);
    }

    @Test
    void register_primaryProfile_missingInvitationToken_throwsInvitationInvalidException() {
        var props = new RegistrationProperties();
        props.getRegistration().setMode("INVITATION_ONLY");
        props.getTenant().setMaxTenants(-1);
        service =
                new DefaultRegistrationService(
                        algorithmRegistryDao, tenantDao, auditLogDao, tokenPool, props, fixedClock);
        when(auditLogDao.append(any())).thenAnswer(inv -> inv.getArgument(0));

        var algo = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        when(algorithmRegistryDao.findById("Ed25519")).thenReturn(Optional.of(algo));
        when(tenantDao.findById("tenant-1")).thenReturn(Optional.empty());
        when(tokenPool.isAvailable(null)).thenReturn(false);

        var request = req("Ed25519", ENCODED_PUBLIC_KEY, null); // no invitation token

        assertThat(service.register("tenant-1", request, "127.0.0.1", null))
                .isInstanceOf(RegistrationService.InvitationInvalidException.class);
    }
}
