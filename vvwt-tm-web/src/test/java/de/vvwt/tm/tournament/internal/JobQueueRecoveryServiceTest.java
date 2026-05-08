package de.vvwt.tm.tournament.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.events.MatchGenJobScheduledEvent;
import de.vvwt.tm.tournament.events.SlotOptJobScheduledEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit tests for {@link JobQueueRecoveryService} (E51S07, DEC-55 D-8).
 *
 * <p>RED-first per DEC-22 Iron Law: tests written before production code.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>AC-TEST-RESTART-RECOVERY-PHASE-PENDING-WITH-AVATARS-NO-MATCHES-RED
 *   <li>AC-TEST-RESTART-RECOVERY-PHASE-PREPARED-WITH-OPTIMIZE-TRUE-RED
 *   <li>AC-TEST-RESTART-RECOVERY-IDEMPOTENT-RED
 *   <li>AC-ERROR-HANDLING-RECOVERY-SERVICE-PARTIAL-FAILURE
 *   <li>AC-ERROR-HANDLING-RECOVERY-SERVICE-NO-INFINITE-LOOP
 * </ul>
 *
 * @see JobQueueRecoveryService
 * @see <a href="DEC-55">DEC-55 D-8 — Restart-Recovery</a>
 * @see <a href="E51S07">E51S07 — AC-TEST-RESTART-RECOVERY-*-RED</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JobQueueRecoveryService unit tests — E51S07 DEC-55 D-8")
class JobQueueRecoveryServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TenantRegistryPort tenantRegistryPort;
    @Mock private TenantContext tenantContext;

    private static final UUID DEFAULT_TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final TenantRegistryPort.TenantRecord DEFAULT_TENANT =
            new TenantRegistryPort.TenantRecord(DEFAULT_TENANT_ID, "Default (LAN)", "de");

    private JobQueueRecoveryService service;

    @BeforeEach
    void setUp() {
        // tenantRegistryPort returns one default tenant
        when(tenantRegistryPort.findAll()).thenReturn(List.of(DEFAULT_TENANT));
        // tenantContext.bind() returns a no-op scope
        when(tenantContext.bind(any())).thenReturn(() -> {});
        service =
                new JobQueueRecoveryService(
                        jdbcTemplate, eventPublisher, tenantRegistryPort, tenantContext);
    }

    // =========================================================================
    // AC-TEST-RESTART-RECOVERY-PHASE-PENDING-WITH-AVATARS-NO-MATCHES-RED
    // =========================================================================

    @Test
    @DisplayName(
            "recover(): PENDING phase with avatars and no matches → publishes"
                    + " MatchGenJobScheduledEvent"
                    + " (AC-TEST-RESTART-RECOVERY-PHASE-PENDING-WITH-AVATARS-NO-MATCHES-RED)")
    void recover_pendingPhaseWithAvatarsNoMatches_publishesMatchGenEvent() {
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();

        // Stub: one PENDING-candidate phase row
        when(jdbcTemplate.queryForList(contains("PENDING")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id", phaseId.toString(),
                                        "tournament_id", tournamentId.toString(),
                                        "last_job_state", "match_gen_running")));

        // Stub: avatars exist (count > 0)
        when(jdbcTemplate.queryForObject(
                        contains("team_avatar"), eq(Integer.class), eq(phaseId.toString())))
                .thenReturn(2);

        // Stub: no matches (count = 0)
        when(jdbcTemplate.queryForObject(
                        contains("match"), eq(Integer.class), eq(phaseId.toString())))
                .thenReturn(0);

        // Stub: no PREPARED-candidate phases
        when(jdbcTemplate.queryForList(contains("PREPARED"))).thenReturn(List.of());

        service.recover();

        // Verify MatchGenJobScheduledEvent was published
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

        boolean hasMatchGenEvent =
                eventCaptor.getAllValues().stream()
                        .anyMatch(
                                e ->
                                        e instanceof MatchGenJobScheduledEvent mge
                                                && mge.phaseId().equals(phaseId)
                                                && mge.tournamentId().equals(tournamentId));
        org.assertj.core.api.Assertions.assertThat(hasMatchGenEvent)
                .as("MatchGenJobScheduledEvent must be published for PENDING phase")
                .isTrue();
    }

    // =========================================================================
    // AC-TEST-RESTART-RECOVERY-PHASE-PREPARED-WITH-OPTIMIZE-TRUE-RED
    // =========================================================================

    @Test
    @DisplayName(
            "recover(): PREPARED+optimize=true+not-optimized → publishes SlotOptJobScheduledEvent"
                    + " (AC-TEST-RESTART-RECOVERY-PHASE-PREPARED-WITH-OPTIMIZE-TRUE-RED)")
    void recover_preparedPhaseOptimizeTrueNotOptimized_publishesSlotOptEvent() {
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();

        // Stub: no PENDING-candidate phases
        when(jdbcTemplate.queryForList(contains("PENDING"))).thenReturn(List.of());

        // Stub: one PREPARED-candidate phase row (optimize=true, optimized=false)
        when(jdbcTemplate.queryForList(contains("PREPARED")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id", phaseId.toString(),
                                        "tournament_id", tournamentId.toString(),
                                        "last_job_state", "slot_opt_running")));

        service.recover();

        // Verify SlotOptJobScheduledEvent was published
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

        boolean hasSlotOptEvent =
                eventCaptor.getAllValues().stream()
                        .anyMatch(
                                e ->
                                        e instanceof SlotOptJobScheduledEvent soe
                                                && soe.phaseId().equals(phaseId)
                                                && soe.tournamentId().equals(tournamentId));
        org.assertj.core.api.Assertions.assertThat(hasSlotOptEvent)
                .as(
                        "SlotOptJobScheduledEvent must be published for"
                                + " PREPARED+optimize+not-optimized phase")
                .isTrue();
    }

    // =========================================================================
    // AC-TEST-RESTART-RECOVERY-IDEMPOTENT-RED
    // =========================================================================

    @Test
    @DisplayName(
            "recover() called twice: each event published at most once per invocation"
                    + " (AC-TEST-RESTART-RECOVERY-IDEMPOTENT-RED)")
    void recover_calledTwice_noDuplicateEvents() {
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();

        // First call: returns one PREPARED-candidate phase
        when(jdbcTemplate.queryForList(contains("PENDING"))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("PREPARED")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id", phaseId.toString(),
                                        "tournament_id", tournamentId.toString(),
                                        "last_job_state", "slot_opt_running")));

        service.recover();
        service.recover(); // second call

        // Each call should publish exactly once → 2 total calls to publishEvent for 2 invocations
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());

        long slotOptEventCount =
                eventCaptor.getAllValues().stream()
                        .filter(e -> e instanceof SlotOptJobScheduledEvent)
                        .count();
        org.assertj.core.api.Assertions.assertThat(slotOptEventCount)
                .as("Each recover() invocation publishes exactly one event per candidate phase")
                .isEqualTo(2L);
    }

    // =========================================================================
    // AC-ERROR-HANDLING-RECOVERY-SERVICE-PARTIAL-FAILURE
    // =========================================================================

    @Test
    @DisplayName(
            "recover(): DB error on avatar-count for one phase → continues to next phase, no crash"
                    + " (AC-ERROR-HANDLING-RECOVERY-SERVICE-PARTIAL-FAILURE)")
    void recover_dbErrorOnAvatarCount_continueToNextPhase() {
        UUID tournamentId1 = UUID.randomUUID();
        UUID phaseId1 = UUID.randomUUID();
        UUID tournamentId2 = UUID.randomUUID();
        UUID phaseId2 = UUID.randomUUID();

        // Two PENDING-candidate phases
        when(jdbcTemplate.queryForList(contains("PENDING")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id", phaseId1.toString(),
                                        "tournament_id", tournamentId1.toString(),
                                        "last_job_state", "match_gen_running"),
                                Map.of(
                                        "id", phaseId2.toString(),
                                        "tournament_id", tournamentId2.toString(),
                                        "last_job_state", "match_gen_running")));

        // First phase: avatar count throws DB error
        when(jdbcTemplate.queryForObject(
                        contains("team_avatar"), eq(Integer.class), eq(phaseId1.toString())))
                .thenThrow(new org.springframework.dao.DataAccessException("DB error") {});

        // Second phase: avatars exist, no matches → should still be recovered
        when(jdbcTemplate.queryForObject(
                        contains("team_avatar"), eq(Integer.class), eq(phaseId2.toString())))
                .thenReturn(2);
        when(jdbcTemplate.queryForObject(
                        contains("match"), eq(Integer.class), eq(phaseId2.toString())))
                .thenReturn(0);

        // No PREPARED-candidate phases
        when(jdbcTemplate.queryForList(contains("PREPARED"))).thenReturn(List.of());

        // Must not throw
        org.assertj.core.api.Assertions.assertThatCode(() -> service.recover())
                .as("recover() must not crash on partial DB failure")
                .doesNotThrowAnyException();

        // Phase 2 must still be recovered
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        boolean phase2Recovered =
                eventCaptor.getAllValues().stream()
                        .anyMatch(
                                e ->
                                        e instanceof MatchGenJobScheduledEvent mge
                                                && mge.phaseId().equals(phaseId2));
        org.assertj.core.api.Assertions.assertThat(phase2Recovered)
                .as("Phase 2 must be recovered even after phase 1 DB error")
                .isTrue();
    }

    // =========================================================================
    // AC-ERROR-HANDLING-RECOVERY-SERVICE-NO-INFINITE-LOOP
    // =========================================================================

    @Test
    @DisplayName(
            "recover(): phases NOT matching recovery criteria → no event published"
                    + " (AC-ERROR-HANDLING-RECOVERY-SERVICE-NO-INFINITE-LOOP guard)")
    void recover_noMatchingPhases_noEventPublished() {
        // No PENDING or PREPARED candidates
        when(jdbcTemplate.queryForList(contains("PENDING"))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("PREPARED"))).thenReturn(List.of());

        service.recover();

        verify(eventPublisher, never()).publishEvent(any());
    }
}
