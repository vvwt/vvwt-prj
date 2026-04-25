package de.vvwt.tm.print;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.Tournament;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public interface for Laufzettel assembly — print bounded context (E24S02).
 *
 * <p>Assembles per-team {@link LaufzettelRow} lists for the Laufzettel (team schedule) print
 * template. Joins data from match schedule, referee assignments, activity assignments, and timeline
 * into flat row lists per team. Has no database access — all inputs are pre-loaded by the caller.
 *
 * <p>Placed at the public module surface ({@code de.vvwt.tm.print}) per DEC-35 § clause 1
 * (service interfaces at public Modulith package). Implementation at
 * {@code de.vvwt.tm.print.internal.DefaultLaufzettelAssembler} per DEC-35 naming canon.
 * No {@code I}-prefix per DEC-35.
 *
 * <p>This is the fresh reconstruction per DEC-22 Reconstruction-in-Place. The legacy
 * {@code de.vvwt.tm.infrastructure.print.LaufzettelAssembler} concrete class is UNTOUCHED
 * until E24S07 atomic cutover. Two beans coexist in Spring context post-S02:
 * legacy {@code laufzettelAssembler} and fresh {@code defaultLaufzettelAssembler} (different
 * simple-class-names — no collision per Spring default bean-name derivation).
 *
 * <h2>Round state priority (AC6)</h2>
 *
 * <p>Each round yields exactly one row per team: PLAYING &gt; REFEREEING &gt; ACTIVITY &gt; FREE.
 *
 * <h2>Break rows (AC9)</h2>
 *
 * <p>LAP_BREAK entries are skipped (implicit gap). INTRA_PHASE_BREAK and SECTION_BREAK become
 * {@link LaufzettelRow#breakRow} entries.
 *
 * <h2>No-start-time path (AC10)</h2>
 *
 * <p>If {@code tournament.getPlannedStartTime()} is null, all time windows default to {@code ""}
 * and {@link #hasTime(Tournament)} returns {@code false}.
 *
 * <h2>Multi-phase support (AC11)</h2>
 *
 * <p>A phase header row is inserted before each phase's rounds when more than one phase is present.
 *
 * @see LaufzettelRow
 * @see de.vvwt.tm.print.internal.DefaultLaufzettelAssembler
 */
public interface LaufzettelAssembler {

    /**
     * Assembles a map of team UUID → list of Laufzettel rows for all teams.
     *
     * <p>Uses default lap time and lap break for timeline calculation. For accurate time windows
     * with explicit per-phase configuration, use
     * {@link #assembleWithPhaseConfig(Tournament, List, List, Map, Map, Map, List, int, Map, Map)}.
     *
     * @param tournament the tournament (for start time)
     * @param phases phases sorted by sequenceNumber ascending; must not be null
     * @param teams all participating teams; must not be null
     * @param avatarsByPhase phaseId → TeamAvatars; must not be null
     * @param matchesByPhase phaseId → Matches; must not be null
     * @param breaksByPhase phaseId → PhaseBreaks; must not be null
     * @param activityTypes activity types to assign; must not be null
     * @param sectionBreakMinutes section break between phases in minutes (≥ 0)
     * @return map of teamId → ordered list of {@link LaufzettelRow}; never null
     */
    Map<UUID, List<LaufzettelRow>> assemble(
            Tournament tournament,
            List<Phase> phases,
            List<Team> teams,
            Map<UUID, List<TeamAvatar>> avatarsByPhase,
            Map<UUID, List<Match>> matchesByPhase,
            Map<UUID, List<PhaseBreak>> breaksByPhase,
            List<ActivityType> activityTypes,
            int sectionBreakMinutes);

    /**
     * Assembles Laufzettel rows with explicit per-phase lap time and break time overrides.
     *
     * @param tournament the tournament (for start time)
     * @param phases phases sorted by sequenceNumber ascending; must not be null
     * @param teams all participating teams; must not be null
     * @param avatarsByPhase phaseId → TeamAvatars; must not be null
     * @param matchesByPhase phaseId → Matches; must not be null
     * @param breaksByPhase phaseId → PhaseBreaks; must not be null
     * @param activityTypes activity types to assign; must not be null
     * @param sectionBreakMinutes section break between phases in minutes (≥ 0)
     * @param lapTimeByPhase phaseId → lap time in minutes; missing entries fall back to default
     * @param lapBreakByPhase phaseId → lap break time in minutes; missing entries fall back to default
     * @return map of teamId → ordered list of {@link LaufzettelRow}; never null
     */
    Map<UUID, List<LaufzettelRow>> assembleWithPhaseConfig(
            Tournament tournament,
            List<Phase> phases,
            List<Team> teams,
            Map<UUID, List<TeamAvatar>> avatarsByPhase,
            Map<UUID, List<Match>> matchesByPhase,
            Map<UUID, List<PhaseBreak>> breaksByPhase,
            List<ActivityType> activityTypes,
            int sectionBreakMinutes,
            Map<UUID, Integer> lapTimeByPhase,
            Map<UUID, Integer> lapBreakByPhase);

    /**
     * Returns whether the tournament has a planned start time (controls time column display).
     *
     * @param tournament the tournament to check
     * @return {@code true} if {@code plannedStartTime} is non-null
     */
    boolean hasTime(Tournament tournament);
}
