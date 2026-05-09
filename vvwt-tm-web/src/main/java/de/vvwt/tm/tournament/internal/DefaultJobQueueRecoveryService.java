package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.JobQueueRecoveryService;
import de.vvwt.tm.tournament.events.MatchGenJobScheduledEvent;
import de.vvwt.tm.tournament.events.SlotOptJobScheduledEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Restart-recovery service that reconciles in-flight background jobs after a JVM restart (DEC-55
 * D-8, E51S07).
 *
 * <p>On {@link ApplicationReadyEvent}, scans {@code phase.last_job_state} to find phases that were
 * mid-execution when the previous JVM exited and re-publishes the appropriate Spring events to
 * restart the pipeline:
 *
 * <ul>
 *   <li>{@code phase.status=PENDING} AND avatars exist AND no match rows → re-publish {@link
 *       MatchGenJobScheduledEvent} (match-gen was in-flight)
 *   <li>{@code phase.status=PREPARED} AND {@code phase.optimized=false} AND {@code
 *       tournament.optimize=true} AND no active slot-opt job → re-publish {@link
 *       SlotOptJobScheduledEvent} (slot-opt was in-flight or queued)
 * </ul>
 *
 * <h2>Design properties</h2>
 *
 * <ul>
 *   <li>Stateless: each invocation independently queries the DB — no in-memory state.
 *   <li>Idempotent per invocation: each qualifying phase produces exactly one event per {@link
 *       #recover()} call. ApplicationReadyEvent fires once per startup in production.
 *   <li>Partial-failure tolerant: DB errors on individual phases are caught, logged at ERROR, and
 *       processing continues to the next phase
 *       (AC-ERROR-HANDLING-RECOVERY-SERVICE-PARTIAL-FAILURE).
 *   <li>No new Modulith edges: events are published into the Spring event bus; no direct dependency
 *       on {@code slotopt} or {@code matchgen} implementation packages.
 * </ul>
 *
 * @see MatchGenJobScheduledEvent
 * @see SlotOptJobScheduledEvent
 * @see <a href="DEC-55">DEC-55 D-8 — Restart-Recovery</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate for self-created Spring components</a>
 * @see <a href="E51S07">E51S07 — AC-IMPL-JOB-QUEUE-RECOVERY-SERVICE</a>
 */
@Service
public class DefaultJobQueueRecoveryService implements JobQueueRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultJobQueueRecoveryService.class);

    /**
     * Finds phases in PENDING status where avatars have been persisted but no matches yet exist.
     * These are phases whose match-generation was interrupted mid-execution.
     */
    private static final String SELECT_PENDING_CANDIDATES =
            "SELECT p.id, p.tournament_id, p.last_job_state"
                    + " FROM phase p"
                    + " WHERE p.status = 'PENDING'"
                    + " AND p.last_job_state = 'match_gen_running'";

    /**
     * Finds phases in PREPARED status where slot-opt was in-flight or queued but not yet completed.
     * Only recovers when the tournament has optimize=true (otherwise skip is correct).
     */
    private static final String SELECT_PREPARED_CANDIDATES =
            "SELECT p.id, p.tournament_id, p.last_job_state"
                    + " FROM phase p"
                    + " JOIN tournament t ON t.id = p.tournament_id"
                    + " WHERE p.status = 'PREPARED'"
                    + " AND p.optimized = FALSE"
                    + " AND t.optimize = TRUE"
                    + " AND p.last_job_state = 'slot_opt_running'";

    private static final String COUNT_AVATARS =
            "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?";

    private static final String COUNT_MATCHES = "SELECT COUNT(*) FROM match WHERE phase_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantRegistryPort tenantRegistryPort;
    private final TenantContext tenantContext;

    public DefaultJobQueueRecoveryService(
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher,
            TenantRegistryPort tenantRegistryPort,
            TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
        this.tenantRegistryPort = tenantRegistryPort;
        this.tenantContext = tenantContext;
    }

    /**
     * Triggered on {@link ApplicationReadyEvent} to recover in-flight background jobs after a
     * restart.
     *
     * <p>Scans phases per the DEC-55 D-8 rules and re-publishes events as needed. Iterates over all
     * registered tenants, binding the tenant context for each before querying the per-tenant
     * database.
     */
    @Override
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        log.info(
                "DefaultJobQueueRecoveryService: scanning for in-flight background jobs to"
                        + " recover");

        for (TenantRegistryPort.TenantRecord tenant : tenantRegistryPort.findAll()) {
            try (var scope = tenantContext.bind(tenant.tenantId())) {
                scope.hashCode(); // prevent unused-variable warning (auto-closeable scope)
                recoverPendingPhases();
                recoverPreparedPhases();
            } catch (Exception ex) {
                log.error(
                        "DefaultJobQueueRecoveryService: failed to scan tenant {}: {}",
                        tenant.tenantId(),
                        ex.getMessage());
            }
        }

        log.info("DefaultJobQueueRecoveryService: recovery scan complete");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void recoverPendingPhases() {
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList(SELECT_PENDING_CANDIDATES);

        for (Map<String, Object> row : candidates) {
            UUID phaseId = UUID.fromString(row.get("id").toString());
            UUID tournamentId = UUID.fromString(row.get("tournament_id").toString());

            try {
                Integer avatarCount =
                        jdbcTemplate.queryForObject(
                                COUNT_AVATARS, Integer.class, phaseId.toString());
                Integer matchCount =
                        jdbcTemplate.queryForObject(
                                COUNT_MATCHES, Integer.class, phaseId.toString());

                if (avatarCount != null
                        && avatarCount > 0
                        && (matchCount == null || matchCount == 0)) {
                    log.warn(
                            "DefaultJobQueueRecoveryService: recovering match-gen for phase {}"
                                    + " (tournament {}) — avatars={}, matches={}",
                            phaseId,
                            tournamentId,
                            avatarCount,
                            matchCount);
                    eventPublisher.publishEvent(
                            new MatchGenJobScheduledEvent(tournamentId, phaseId));
                }
            } catch (Exception ex) {
                log.error(
                        "DefaultJobQueueRecoveryService: failed to evaluate PENDING recovery for"
                                + " phase {}: {}",
                        phaseId,
                        ex.getMessage());
            }
        }
    }

    private void recoverPreparedPhases() {
        List<Map<String, Object>> candidates =
                jdbcTemplate.queryForList(SELECT_PREPARED_CANDIDATES);

        for (Map<String, Object> row : candidates) {
            UUID phaseId = UUID.fromString(row.get("id").toString());
            UUID tournamentId = UUID.fromString(row.get("tournament_id").toString());

            try {
                log.warn(
                        "DefaultJobQueueRecoveryService: recovering slot-opt for phase {}"
                                + " (tournament {})",
                        phaseId,
                        tournamentId);
                eventPublisher.publishEvent(new SlotOptJobScheduledEvent(tournamentId, phaseId));
            } catch (Exception ex) {
                log.error(
                        "DefaultJobQueueRecoveryService: failed to evaluate PREPARED recovery for"
                                + " phase {}: {}",
                        phaseId,
                        ex.getMessage());
            }
        }
    }
}
