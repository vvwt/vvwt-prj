package de.vvwt.tm.domain.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.RoundSnapshot;
import de.vvwt.tm.domain.TeamAvatarRating;
import de.vvwt.tm.domain.event.MatchResultChangedEvent;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.RoundSnapshotRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRatingRepository;
import de.vvwt.tm.domain.repo.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Round-end snapshot service — writes a structured snapshot of phase standings to
 * {@code round_snapshots} every time a lap advance is detected (E03S13, D-2/D-10).
 *
 * <h2>Trigger (AC1)</h2>
 * <p>Listens for {@link MatchResultChangedEvent} via
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}. The AFTER_COMMIT phase is
 * crucial: it guarantees the listener only runs after the cascade transaction has already
 * committed, preventing a snapshot referring to uncommitted match state (see story notes).
 *
 * <h2>Lap-advance detection (AC2)</h2>
 * <p>A lap advance is detected when {@code phase.currentLapNumber > event.previousLapNumber}.
 * If no advance occurred, the listener returns immediately — no snapshot is written.
 *
 * <h2>Payload (AC3)</h2>
 * <p>The JSON payload contains:
 * <ul>
 *   <li>{@code tournament_id}, {@code phase_id}, {@code completed_lap_number}, {@code captured_at}</li>
 *   <li>{@code team_standings} — all TeamAvatarRating rows for avatars in the phase, sorted per D-33</li>
 *   <li>{@code matches_in_lap} — all matches in the completed lap with their results</li>
 * </ul>
 * The payload MUST NOT contain PII: team names/descriptions are excluded (AC14). Only UUIDs,
 * numeric scores, and state codes are included.
 *
 * <h2>Duplicate guard (AC5)</h2>
 * <p>If a snapshot for (tournament_id, phase_id, lap_number) already exists, the INSERT is
 * skipped with a WARN log — first-snapshot-wins semantics. This may occur if duplicate events
 * are delivered (rare but possible in a clustered future deployment).
 *
 * <h2>Error isolation (AC9, AC10)</h2>
 * <p>All exceptions thrown within {@link #onMatchResultChanged} are caught and logged at ERROR
 * level. They are never propagated — the cascade transaction has already committed and must not
 * be affected by snapshot failures.
 *
 * <h2>Manual recovery (AC6)</h2>
 * <p>{@link #regenerate(UUID, UUID, int)} can re-create a snapshot on demand if the event-driven
 * path failed or the snapshot row was deleted for debugging purposes.
 *
 * <h2>Tenant scope (AC13)</h2>
 * <p>All repository calls use the active {@link TenantContext}, which is still set on the thread
 * when the AFTER_COMMIT listener fires (same thread as the cascade).
 *
 * @see MatchResultChangedEvent
 * @see RoundSnapshot
 */
@Service
public class RoundSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(RoundSnapshotService.class);

    private final PhaseRepository phaseRepository;
    private final TeamAvatarRatingRepository teamAvatarRatingRepository;
    private final MatchRepository matchRepository;
    private final RoundSnapshotRepository roundSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final TenantContext tenantContext;

    // Micrometer counters (AC12)
    private final Counter snapshotCreatedCounter;
    private final Counter snapshotErrorCounter;

    public RoundSnapshotService(PhaseRepository phaseRepository,
                                TeamAvatarRatingRepository teamAvatarRatingRepository,
                                MatchRepository matchRepository,
                                RoundSnapshotRepository roundSnapshotRepository,
                                ObjectMapper objectMapper,
                                TenantContext tenantContext,
                                MeterRegistry meterRegistry) {
        this.phaseRepository = phaseRepository;
        this.teamAvatarRatingRepository = teamAvatarRatingRepository;
        this.matchRepository = matchRepository;
        this.roundSnapshotRepository = roundSnapshotRepository;
        this.objectMapper = objectMapper;
        this.tenantContext = tenantContext;
        // AC12: Micrometer counters surfaced via Spring Boot Actuator
        this.snapshotCreatedCounter = Counter.builder("round_snapshot_created_total")
                .description("Number of round snapshots successfully created")
                .register(meterRegistry);
        this.snapshotErrorCounter = Counter.builder("round_snapshot_errors_total")
                .description("Number of errors during round snapshot creation")
                .register(meterRegistry);
    }

    // ---------------------------------------------------------------------------
    // Event listener — AFTER_COMMIT (AC1)
    // ---------------------------------------------------------------------------

    /**
     * Handles {@link MatchResultChangedEvent} after the cascade transaction commits.
     *
     * <p>Checks for a lap advance and, if detected, writes a snapshot for the completed lap.
     * All exceptions are caught and logged — they MUST NOT propagate to avoid disturbing
     * other AFTER_COMMIT listeners or the Spring event infrastructure (AC9).
     *
     * @param event the committed match result change event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMatchResultChanged(MatchResultChangedEvent event) {
        try {
            doOnMatchResultChanged(event);
        } catch (Exception ex) {
            snapshotErrorCounter.increment();
            log.error("RoundSnapshotService: unhandled error during snapshot creation "
                    + "[tournament={}, phase={}, correlationId={}] — snapshot may be missing, "
                    + "use regenerate() to recover: {}",
                    event.getTournamentId(), event.getPhaseId(), event.getCorrelationId(),
                    ex.getMessage(), ex);
            // AC9: exception swallowed — cascade has already committed
        }
    }

    private void doOnMatchResultChanged(MatchResultChangedEvent event) {
        // AC2: lap-advance detection
        Optional<Phase> phaseOpt = phaseRepository.findById(event.getPhaseId());
        if (phaseOpt.isEmpty()) {
            log.error("RoundSnapshotService: phase {} not found — cannot check lap advance "
                    + "[tournament={}, correlationId={}]",
                    event.getPhaseId(), event.getTournamentId(), event.getCorrelationId());
            snapshotErrorCounter.increment();
            return;
        }
        Phase phase = phaseOpt.get();
        int currentLap = phase.getCurrentLapNumber();
        int previousLap = event.getPreviousLapNumber();

        if (currentLap <= previousLap) {
            // AC8: no lap advance — no snapshot written
            log.debug("RoundSnapshotService: no lap advance detected (phase.currentLapNumber={}, "
                    + "event.previousLapNumber={}) — skipping snapshot "
                    + "[phase={}, correlationId={}]",
                    currentLap, previousLap, event.getPhaseId(), event.getCorrelationId());
            return;
        }

        // A lap advance occurred — write snapshot for the COMPLETED (previous) lap
        int completedLap = previousLap;
        writeSnapshot(event.getTournamentId(), event.getPhaseId(), completedLap);
    }

    // ---------------------------------------------------------------------------
    // Snapshot writing (AC3, AC4, AC5)
    // ---------------------------------------------------------------------------

    /**
     * Writes a snapshot row for the given (tournament, phase, lap).
     *
     * <p>Implements the duplicate guard: if a snapshot already exists (AC5), logs WARN and returns.
     * On DB write failure (AC10), logs ERROR and returns — no retry.
     *
     * @param tournamentId   the tournament
     * @param phaseId        the phase
     * @param completedLap   the lap number that just completed
     */
    private void writeSnapshot(UUID tournamentId, UUID phaseId, int completedLap) {
        // AC5: duplicate guard — first-snapshot-wins
        Optional<RoundSnapshot> existing = roundSnapshotRepository.findByTournamentPhaseAndLap(
                tournamentId, phaseId, completedLap);
        if (existing.isPresent()) {
            log.warn("RoundSnapshotService: snapshot already exists for "
                    + "[tournament={}, phase={}, lap={}] — skipping (first-snapshot-wins)",
                    tournamentId, phaseId, completedLap);
            return;
        }

        // AC3: build payload
        String payload;
        try {
            payload = buildPayload(tournamentId, phaseId, completedLap);
        } catch (Exception ex) {
            snapshotErrorCounter.increment();
            log.error("RoundSnapshotService: payload generation failed for "
                    + "[tournament={}, phase={}, lap={}]: {}",
                    tournamentId, phaseId, completedLap, ex.getMessage(), ex);
            return;
        }

        // AC4: persist
        UUID snapshotId = UUID.randomUUID();
        UUID tenantId = tenantContext.getTenantId();
        RoundSnapshot snapshot = new RoundSnapshot(
                snapshotId, tenantId, tournamentId, phaseId, completedLap, payload, LocalDateTime.now());
        try {
            roundSnapshotRepository.save(snapshot);
        } catch (Exception ex) {
            snapshotErrorCounter.increment();
            // AC10: DB write failure — log ERROR, do not retry
            log.error("RoundSnapshotService: DB write failed for "
                    + "[tournament={}, phase={}, lap={}]: {}",
                    tournamentId, phaseId, completedLap, ex.getMessage(), ex);
            return;
        }

        snapshotCreatedCounter.increment();
        // AC11: INFO log on success
        log.info("RoundSnapshotService: snapshot created [tournament={}, phase={}, lap={}, "
                + "snapshotId={}, payloadSizeBytes={}]",
                tournamentId, phaseId, completedLap, snapshotId, payload.length());
        // AC11: DEBUG log with full payload
        log.debug("RoundSnapshotService: payload for [tournament={}, phase={}, lap={}]: {}",
                tournamentId, phaseId, completedLap, payload);
    }

    // ---------------------------------------------------------------------------
    // Payload generation (AC3, AC6, AC14)
    // ---------------------------------------------------------------------------

    /**
     * Builds the JSON payload for a snapshot of the given (tournament, phase, lap).
     *
     * <p>The payload is regenerable from current DB state on demand (AC6): the same method is
     * used by both the event-driven path and the {@link #regenerate} recovery path.
     *
     * <p>Payload schema (Delivery decision, E03S13):
     * <pre>
     * {
     *   "tournament_id": "...",
     *   "phase_id": "...",
     *   "completed_lap_number": 0,
     *   "captured_at": "2026-04-12T10:00:00Z",
     *   "team_standings": [
     *     { "rank": 1, "avatar_id": "...", "group_number": 1, "group_position": 1,
     *       "points": 3, "sets_won": 2, "sets_lost": 0, "balls_won": 50, "balls_lost": 25,
     *       "set_quotient": 1.7976931348623157E308, "ball_quotient": 2.0,
     *       "is_without_assessment": false }
     *   ],
     *   "matches_in_lap": [
     *     { "match_id": "...", "member_avatar_1_id": "...", "member_avatar_2_id": "...",
     *       "state": 51, "lap_number": 0, "field_number": 1, "referee_team_id": null }
     *   ]
     * }
     * </pre>
     *
     * <p>No PII: team names/descriptions are excluded per AC14. Only public tournament data
     * (UUIDs, scores, state codes, boolean flags).
     *
     * @param tournamentId the tournament
     * @param phaseId      the phase
     * @param completedLap the completed lap number
     * @return JSON string payload
     * @throws JsonProcessingException if Jackson serialization fails
     */
    String buildPayload(UUID tournamentId, UUID phaseId, int completedLap)
            throws JsonProcessingException {

        // AC3: team_standings — all ratings for avatars in this phase, sorted per D-33
        List<TeamAvatarRating> ratings = teamAvatarRatingRepository.findByPhaseId(phaseId);
        Collections.sort(ratings);  // TeamAvatarRating.compareTo implements D-33 order

        List<Map<String, Object>> teamStandings = new ArrayList<>();
        int rank = 1;
        for (TeamAvatarRating rating : ratings) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("rank", rank++);
            entry.put("avatar_id", rating.getAvatarId().toString());
            // AC14: no PII — avatarId is a UUID, not a name/email/credential
            entry.put("points", rating.getPoints());
            entry.put("sets_won", rating.getSetsWon());
            entry.put("sets_lost", rating.getSetsLost());
            entry.put("balls_won", rating.getBallsWon());
            entry.put("balls_lost", rating.getBallsLost());
            entry.put("set_quotient", rating.getSetQuotient());
            entry.put("ball_quotient", rating.getBallQuotient());
            entry.put("is_without_assessment", rating.isWithoutAssessment());
            teamStandings.add(entry);
        }

        // AC3: matches_in_lap — all matches in the completed lap
        List<Match> matchesInLap = matchRepository.findByPhaseIdAndLapNumber(phaseId, completedLap);
        List<Map<String, Object>> matchEntries = new ArrayList<>();
        for (Match m : matchesInLap) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("match_id", m.getId().toString());
            entry.put("member_avatar_1_id", m.getMemberAvatar1Id().toString());
            entry.put("member_avatar_2_id", m.getMemberAvatar2Id().toString());
            entry.put("state", m.getState());
            entry.put("lap_number", m.getLapNumber());
            entry.put("field_number", m.getFieldNumber());
            // AC14: referee_team_id is a UUID reference, not a name — safe to include
            entry.put("referee_team_id",
                    m.getRefereeTeamId() != null ? m.getRefereeTeamId().toString() : null);
            matchEntries.add(entry);
        }

        // Assemble the root document
        Map<String, Object> doc = new HashMap<>();
        doc.put("tournament_id", tournamentId.toString());
        doc.put("phase_id", phaseId.toString());
        doc.put("completed_lap_number", completedLap);
        doc.put("captured_at", Instant.now().toString());
        doc.put("team_standings", teamStandings);
        doc.put("matches_in_lap", matchEntries);

        return objectMapper.writeValueAsString(doc);
    }

    // ---------------------------------------------------------------------------
    // Manual recovery (AC6)
    // ---------------------------------------------------------------------------

    /**
     * Re-generates and inserts (or replaces) the snapshot for the given (tournament, phase, lap).
     *
     * <p>This is a manual recovery tool for operators when the event-driven snapshot creation
     * failed (e.g., JSON serialization error, DB transient failure) or when a snapshot row was
     * deleted for debugging. It is NOT called automatically — it requires an explicit operator
     * invocation.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>If no existing snapshot: builds payload, inserts new row.</li>
     *   <li>If existing snapshot: deletes it, builds payload, inserts new row (replace semantics).</li>
     * </ul>
     *
     * <p>This method is {@code @Transactional} — the delete + insert are atomic.
     *
     * @param tournamentId the tournament
     * @param phaseId      the phase
     * @param lapNumber    the lap to regenerate the snapshot for
     * @throws IllegalStateException    if no tenant context is active
     * @throws IllegalArgumentException if payload generation fails (no matches / no ratings)
     */
    @Transactional
    public void regenerate(UUID tournamentId, UUID phaseId, int lapNumber) {
        log.info("RoundSnapshotService.regenerate: called for [tournament={}, phase={}, lap={}]",
                tournamentId, phaseId, lapNumber);

        // Delete existing if present (replace semantics for regenerate)
        Optional<RoundSnapshot> existing = roundSnapshotRepository.findByTournamentPhaseAndLap(
                tournamentId, phaseId, lapNumber);
        existing.ifPresent(s -> {
            log.info("RoundSnapshotService.regenerate: deleting existing snapshot id={}", s.getId());
            roundSnapshotRepository.deleteById(s.getId());
        });

        // Build payload from current DB state
        String payload;
        try {
            payload = buildPayload(tournamentId, phaseId, lapNumber);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                    "regenerate: payload build failed for [tournament=" + tournamentId
                    + ", phase=" + phaseId + ", lap=" + lapNumber + "]: " + ex.getMessage(), ex);
        }

        // Insert new snapshot
        UUID snapshotId = UUID.randomUUID();
        UUID tenantId = tenantContext.getTenantId();
        RoundSnapshot snapshot = new RoundSnapshot(
                snapshotId, tenantId, tournamentId, phaseId, lapNumber, payload, LocalDateTime.now());
        roundSnapshotRepository.save(snapshot);

        snapshotCreatedCounter.increment();
        log.info("RoundSnapshotService.regenerate: snapshot created "
                + "[tournament={}, phase={}, lap={}, snapshotId={}, payloadSizeBytes={}]",
                tournamentId, phaseId, lapNumber, snapshotId, payload.length());
    }
}
