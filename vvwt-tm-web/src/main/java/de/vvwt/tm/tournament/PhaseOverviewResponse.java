package de.vvwt.tm.tournament;

import java.util.Map;
import java.util.UUID;

/**
 * Bounded-context-owned query-shape DTO for a single phase overview entry (E48S05, DEC-40 Clause B
 * Pattern A).
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
 * @param status lifecycle status name — one of {@code PENDING}, {@code ACTIVE}, {@code COMPLETED}
 * @param gameMode game mode from {@code draft_json} section matching this phase's sequenceNumber;
 *     {@code null} when {@code draft_json} is absent, unparseable, or section not found
 *     (AC-PHASE-LIST-DEFENSIVE)
 * @param currentLapNumber active lap index (starts at 0)
 * @param matchCountsByState match counts grouped by state name; key is the {@link
 *     de.vvwt.tm.tournament.MatchState} name; value is the count. Missing keys imply zero count
 * @see PhaseQueryService
 * @see <a href="DEC-40">DEC-40 — Pattern A bounded-context-owned query-shape DTO</a>
 * @see <a href="E48S05">E48S05 — AC-IMPL-PHASE-LIST-ENDPOINT</a>
 */
public record PhaseOverviewResponse(
        UUID id,
        int sequenceNumber,
        String description,
        String status,
        String gameMode,
        int currentLapNumber,
        Map<String, Long> matchCountsByState) {}
