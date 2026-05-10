package de.vvwt.tm.tournament;

import java.util.Map;
import java.util.UUID;

/**
 * Bounded-context-owned query-shape DTO for a single phase overview entry (E48S05, DEC-40 Clause B
 * Pattern A; E51S07 AC-IMPL-PHASELIST-ENDPOINT-EXTENSION).
 *
 * <p>Pattern A applies: the controller ({@code web.TournamentPhasesController}) serializes this
 * record directly via Jackson — no web-tier DTO wrapper needed. The record belongs to the {@code
 * tournament} bounded-context public package because the service method returns it as a
 * domain-projection type (wire shape == projection shape; no field omission, aliasing, or
 * cross-context aggregation).
 *
 * <p>Used by {@link PhaseQueryService#listPhasesWithCounts(UUID)}.
 *
 * @param id phase UUID
 * @param sequenceNumber ordering within the tournament (1-indexed)
 * @param description human-readable phase label (e.g., "Vorrunde", "Finale")
 * @param status lifecycle status name — one of {@code PENDING}, {@code PREPARED}, {@code ASSIGNED},
 *     {@code ACTIVE}, {@code COMPLETED}
 * @param gameMode game mode from {@code draft_json} section matching this phase's sequenceNumber;
 *     {@code null} when {@code draft_json} is absent, unparseable, or section not found
 *     (AC-PHASE-LIST-DEFENSIVE)
 * @param currentLapNumber active lap index (starts at 0)
 * @param matchCountsByState match counts grouped by state name; key is the {@link
 *     de.vvwt.tm.tournament.MatchState} name; value is the count. Missing keys imply zero count
 * @param jobStatus the {@code phase.last_job_state} value — one of {@code "match_gen_running"},
 *     {@code "slot_opt_running"}, {@code "idle"}, {@code "cancelled"}, {@code "failed"}, or {@code
 *     null} if no background job has run for this phase yet (E51S07
 *     AC-IMPL-PHASELIST-ENDPOINT-EXTENSION, AC-ERROR-HANDLING-PHASELIST-ICON-NULL-LAST-JOB-STATE)
 * @param optimized {@code true} when slot-optimization has completed successfully for this phase
 *     ({@code phase.optimized = TRUE} in DB); {@code false} when slot-opt has not completed or has
 *     not run; never {@code null} (H2 BOOLEAN column with DB default FALSE maps to Java {@code
 *     boolean} via JDBC {@code getBoolean}) — additive field, forward-compatible with the
 *     TypeScript {@code PhaseOverview.optimized?: boolean | undefined} declaration (E51S21
 *     AC-GOV-WIRE-FORMAT-ADDITIVE-ONLY, DEC-40 Pattern A, DEC-55 D-9, DEC-59 Clause F)
 * @see PhaseQueryService
 * @see <a href="DEC-40">DEC-40 — Pattern A bounded-context-owned query-shape DTO</a>
 * @see <a href="E48S05">E48S05 — AC-IMPL-PHASE-LIST-ENDPOINT</a>
 * @see <a href="E51S07">E51S07 — AC-IMPL-PHASELIST-ENDPOINT-EXTENSION</a>
 * @see <a href="E51S21">E51S21 — AC-TEST-PHASE-OVERVIEW-RESPONSE-OPTIMIZED-FIELD-RED</a>
 */
public record PhaseOverviewResponse(
        UUID id,
        int sequenceNumber,
        String description,
        String status,
        String gameMode,
        int currentLapNumber,
        Map<String, Long> matchCountsByState,
        String jobStatus,
        boolean optimized) {}
