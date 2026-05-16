package de.vvwt.tm.tournament.draft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One section (future Phase) within a tournament draft configuration.
 *
 * <p>Carries the parameters for one phase that will be created when the draft is applied. Immutable
 * — all fields set at construction. Jackson deserializes via {@link JsonCreator}-annotated
 * constructor.
 *
 * <p>Inventory: E21S01 line 241. Named-interface sub-package placement by E33S04 (DEC-35 retrofit).
 * Legacy {@code de.vvwt.tm.domain.draft.DraftSection} remains active until E21S13.
 *
 * <h2>E51S15 / E58S02 — distributionMode (distribution_mode in draft_json)</h2>
 *
 * <p>{@link #distributionMode} controls how teams are distributed across groups in Phase-1 and how
 * Phase-1 proposals are computed in {@code DefaultPhaseTransitionService.computePhase1Proposals}:
 *
 * <ul>
 *   <li>{@code "sequential"} (default) — fill Group 1 fully before Group 2 (group = i /
 *       positionsPerGroup + 1, position = i % positionsPerGroup + 1).
 *   <li>{@code "round_robin"} — distribute teams one-per-group before advancing to the next
 *       position (group = i % groupCount + 1, position = i / groupCount + 1).
 * </ul>
 *
 * <p>Persistence: stored in {@code tournament.draft_json} (existing JSON column per DEC-14 H2
 * schema). No Flyway migration needed — additive JSON field. Absent field defaults to {@code
 * "sequential"} via constructor null-guard. Migrated from {@code DistributionMode} enum to {@code
 * String} by E58S02 (DEC-73 D-2).
 *
 * <h2>E58S01 — gameMode migrated from GameMode enum to String (DEC-73 D-5)</h2>
 *
 * <p>{@link #gameMode} is now a {@code String} registry key instead of the removed {@code GameMode}
 * enum. Jackson deserializes it natively as a String. Known values at runtime: {@code
 * "roundRobin"}, {@code "siegerehrung"} — validated against {@link
 * de.vvwt.tm.tournament.MatchGeneratorRegistry} at draft save and apply time (AC6). JSON
 * wire-format is unchanged: the same String values {@code "siegerehrung"} and {@code "roundRobin"}
 * flow through the API as before.
 *
 * @see DraftConfig
 * @see DraftBreak
 * @see <a href="DEC-14">DEC-14 — H2 persistence, draft_json column (no migration)</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-73">DEC-73 D-2 — DistributionMode enum removed; String registry key</a>
 * @see <a href="DEC-73">DEC-73 D-5 — GameMode enum removed; String registry key</a>
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity (phaseId, groupNumber,
 *     groupPosition)</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E51S15">E51S15 — distributionMode feature (sequential default + round-robin
 *     toggle)</a>
 * @see <a href="E58S01">E58S01 — gameMode String migration; AC5</a>
 * @see <a href="E58S02">E58S02 — distributionMode String migration; AC5</a>
 */
public final class DraftSection {

    /** 1-indexed ordering within the draft. Determines the Phase sequenceNumber. */
    private final int sectionNumber;

    /**
     * Team-sorting strategy entering this phase. Valid values: {@code team_number}, {@code
     * placement_group}, {@code group_placement}.
     */
    private final String sortType;

    /** Number of groups to split participating teams into. Must be ≥ 1. */
    private final int groupCount;

    /**
     * Game mode registry key for this phase (e.g., {@code "roundRobin"}, {@code "siegerehrung"}).
     *
     * <p>The last phase in a draft MUST use the registry key of the last-phase generator (i.e.,
     * {@code "siegerehrung"} — enforced at apply time via {@link
     * de.vvwt.tm.tournament.internal.DefaultDraftService#apply}). Registry-membership validation
     * occurs at save and apply time (AC6, E58S01).
     *
     * <p>Migrated from {@code GameMode} enum to {@code String} by E58S01 (DEC-73 D-5).
     *
     * @see <a href="E58S01">E58S01 — AC5 GameMode enum removed</a>
     * @see <a href="DEC-73">DEC-73 D-5</a>
     */
    private final String gameMode;

    /** Pause between rounds within the section, in minutes. Must be ≥ 0. */
    private final int lapBreakTimeMinutes;

    /** Pause after this section and before the next, in minutes. Must be ≥ 0. */
    private final int sectionBreakTimeMinutes;

    /** Duration of each round (lap) in minutes. Must be > 0. */
    private final int lapTimeMinutes;

    /** Sets per match. Must be ≥ 1. */
    private final int setQuantity;

    /** Optional intra-phase breaks. May be empty; never {@code null}. */
    private final List<DraftBreak> breaks;

    /**
     * Team distribution algorithm for Phase-1 avatar assignment. Registry key string.
     *
     * <p>Defaults to {@code "sequential"} when absent/null in draft_json
     * (AC-TEST-DEFAULT-IS-SEQUENTIAL-RED, E51S15). Migrated from {@code DistributionMode} enum to
     * {@code String} by E58S02 (DEC-73 D-2). Known values: {@code "sequential"}, {@code
     * "round_robin"}.
     *
     * @see <a href="E51S15">E51S15 — distributionMode feature</a>
     * @see <a href="E58S02">E58S02 — DistributionMode enum removed</a>
     * @see <a href="DEC-14">DEC-14 — persistence in draft_json JSON column (no Flyway
     *     migration)</a>
     */
    private final String distributionMode;

    /**
     * Jackson-compatible constructor.
     *
     * @param sectionNumber ordering within the draft (≥ 1)
     * @param sortType team-entry sort strategy
     * @param groupCount number of groups (≥ 1)
     * @param gameMode game mode registry key (e.g., {@code "roundRobin"}, {@code "siegerehrung"});
     *     must not be {@code null} or blank; validated against the registry at save/apply time
     *     (AC6)
     * @param lapBreakTimeMinutes pause between laps (≥ 0)
     * @param sectionBreakTimeMinutes pause after section (≥ 0)
     * @param lapTimeMinutes lap duration in minutes (> 0)
     * @param setQuantity sets per match (≥ 1)
     * @param breaks optional intra-phase breaks; {@code null} treated as empty
     * @param distributionMode team distribution algorithm registry key; {@code null} defaults to
     *     {@code "sequential"} (AC-TEST-DEFAULT-IS-SEQUENTIAL-RED, E51S15). Migrated from {@code
     *     DistributionMode} enum to {@code String} by E58S02 (DEC-73 D-2).
     */
    @JsonCreator
    public DraftSection(
            @JsonProperty("sectionNumber") int sectionNumber,
            @JsonProperty("sortType") String sortType,
            @JsonProperty("groupCount") int groupCount,
            @JsonProperty("gameMode") String gameMode,
            @JsonProperty("lapBreakTimeMinutes") int lapBreakTimeMinutes,
            @JsonProperty("sectionBreakTimeMinutes") int sectionBreakTimeMinutes,
            @JsonProperty("lapTimeMinutes") int lapTimeMinutes,
            @JsonProperty("setQuantity") int setQuantity,
            @JsonProperty("breaks") List<DraftBreak> breaks,
            @JsonProperty("distributionMode") String distributionMode) {
        this.sectionNumber = sectionNumber;
        this.sortType = sortType;
        this.groupCount = groupCount;
        this.gameMode = gameMode;
        this.lapBreakTimeMinutes = lapBreakTimeMinutes;
        this.sectionBreakTimeMinutes = sectionBreakTimeMinutes;
        this.lapTimeMinutes = lapTimeMinutes;
        this.setQuantity = setQuantity;
        this.breaks = breaks == null ? List.of() : List.copyOf(breaks);
        // AC-TEST-DEFAULT-IS-SEQUENTIAL-RED: absent/null distributionMode → "sequential"
        this.distributionMode = (distributionMode == null) ? "sequential" : distributionMode;
    }

    /**
     * Legacy 9-parameter constructor (backward compatibility for existing test fixtures and callers
     * that do not specify distributionMode). Defaults distributionMode to {@link
     * DistributionMode#SEQUENTIAL}.
     *
     * @param sectionNumber ordering within the draft (≥ 1)
     * @param sortType team-entry sort strategy
     * @param groupCount number of groups (≥ 1)
     * @param gameMode game mode registry key (e.g., {@code "roundRobin"}, {@code "siegerehrung"})
     * @param lapBreakTimeMinutes pause between laps (≥ 0)
     * @param sectionBreakTimeMinutes pause after section (≥ 0)
     * @param lapTimeMinutes lap duration in minutes (> 0)
     * @param setQuantity sets per match (≥ 1)
     * @param breaks optional intra-phase breaks; {@code null} treated as empty
     */
    public DraftSection(
            int sectionNumber,
            String sortType,
            int groupCount,
            String gameMode,
            int lapBreakTimeMinutes,
            int sectionBreakTimeMinutes,
            int lapTimeMinutes,
            int setQuantity,
            List<DraftBreak> breaks) {
        this(
                sectionNumber,
                sortType,
                groupCount,
                gameMode,
                lapBreakTimeMinutes,
                sectionBreakTimeMinutes,
                lapTimeMinutes,
                setQuantity,
                breaks,
                null); // null → SEQUENTIAL default
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Validates base field constraints.
     *
     * @throws IllegalArgumentException if any field violates its constraint
     */
    public void validate() {
        if (sectionNumber < 1) {
            throw new IllegalArgumentException("sectionNumber must be ≥ 1, got: " + sectionNumber);
        }
        if (sortType == null || sortType.isBlank()) {
            throw new IllegalArgumentException("sortType must not be blank");
        }
        // Note: sortType registry-membership check was moved to
        // DraftConfig.validateSortTypeMembership()
        // which validates against the TeamSortCalculatorRegistry (AC6, E58S03 DEC-73 D-3).
        if (groupCount < 1) {
            throw new IllegalArgumentException("groupCount must be ≥ 1, got: " + groupCount);
        }
        // gameMode is now a String — null/blank check replaces enum null check (E58S01 DEC-73 D-5)
        if (gameMode == null || gameMode.isBlank()) {
            throw new IllegalArgumentException("gameMode must not be null or blank");
        }
        if (lapBreakTimeMinutes < 0) {
            throw new IllegalArgumentException(
                    "lapBreakTimeMinutes must be ≥ 0, got: " + lapBreakTimeMinutes);
        }
        if (sectionBreakTimeMinutes < 0) {
            throw new IllegalArgumentException(
                    "sectionBreakTimeMinutes must be ≥ 0, got: " + sectionBreakTimeMinutes);
        }
        if (lapTimeMinutes <= 0) {
            throw new IllegalArgumentException(
                    "lapTimeMinutes must be > 0, got: " + lapTimeMinutes);
        }
        if (setQuantity < 1) {
            throw new IllegalArgumentException("setQuantity must be ≥ 1, got: " + setQuantity);
        }
        // distributionMode is now a String registry key — null/blank check (E58S02 DEC-73 D-2).
        // Constructor null-guard enforces "sequential" default; explicit null is impossible
        // after construction via @JsonCreator since the constructor assigns the default.
        if (distributionMode == null || distributionMode.isBlank()) {
            throw new IllegalArgumentException("distributionMode must not be null or blank");
        }
    }

    /**
     * Validates break semantics against the total lap count.
     *
     * @param totalLaps the total number of laps for this section
     * @throws IllegalArgumentException if any break violates the constraints
     */
    public void validateBreaks(int totalLaps) {
        Set<Integer> seenPositions = new HashSet<>();
        for (DraftBreak b : breaks) {
            if (b.getDurationMinutes() <= 0) {
                throw new IllegalArgumentException(
                        "Section "
                                + sectionNumber
                                + ": break durationMinutes must be > 0, got: "
                                + b.getDurationMinutes());
            }
            if (b.getAfterLapNumber() < 1) {
                throw new IllegalArgumentException(
                        "Section "
                                + sectionNumber
                                + ": break afterLapNumber must be ≥ 1, got: "
                                + b.getAfterLapNumber());
            }
            if (b.getAfterLapNumber() >= totalLaps) {
                throw new IllegalArgumentException(
                        "Section "
                                + sectionNumber
                                + ": break afterLapNumber "
                                + b.getAfterLapNumber()
                                + " is out of range — must be < total laps ("
                                + totalLaps
                                + ")");
            }
            if (!seenPositions.add(b.getAfterLapNumber())) {
                throw new IllegalArgumentException(
                        "Section "
                                + sectionNumber
                                + ": duplicate break at afterLapNumber "
                                + b.getAfterLapNumber());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getSectionNumber() {
        return sectionNumber;
    }

    public String getSortType() {
        return sortType;
    }

    public int getGroupCount() {
        return groupCount;
    }

    /**
     * Returns the game mode registry key for this phase (e.g., {@code "roundRobin"}, {@code
     * "siegerehrung"}).
     *
     * <p>Migrated from returning {@code GameMode} enum to returning {@code String} by E58S01
     * (DEC-73 D-5). Wire-format is unchanged — the same String values flow through the JSON API.
     *
     * @return the game mode registry key; never {@code null}
     * @see <a href="E58S01">E58S01 — AC5 GameMode enum removed</a>
     */
    public String getGameMode() {
        return gameMode;
    }

    public int getLapBreakTimeMinutes() {
        return lapBreakTimeMinutes;
    }

    public int getSectionBreakTimeMinutes() {
        return sectionBreakTimeMinutes;
    }

    public int getLapTimeMinutes() {
        return lapTimeMinutes;
    }

    public int getSetQuantity() {
        return setQuantity;
    }

    public List<DraftBreak> getBreaks() {
        return breaks;
    }

    /**
     * Returns the team distribution algorithm registry key for Phase-1 avatar assignment.
     *
     * <p>Migrated from returning {@code DistributionMode} enum to returning {@code String} by
     * E58S02 (DEC-73 D-2). Known values: {@code "sequential"}, {@code "round_robin"}.
     *
     * @return the registry key; never {@code null} (defaults to {@code "sequential"} when
     *     absent/null in draft_json)
     * @see <a href="E51S15">E51S15 — distributionMode feature</a>
     * @see <a href="E58S02">E58S02 — DistributionMode enum removed</a>
     */
    public String getDistributionMode() {
        return distributionMode;
    }
}
