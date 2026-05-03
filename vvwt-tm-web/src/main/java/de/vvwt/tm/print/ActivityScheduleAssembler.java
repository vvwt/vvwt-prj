package de.vvwt.tm.print;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.activity.ActivityType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public interface for the activity schedule assembler (Mannschaftsfoto-Übersicht).
 *
 * <p>Assembles the row list for the activity schedule print template. The implementation ({@link
 * de.vvwt.tm.print.internal.DefaultActivityScheduleAssembler}) lives in the internal package per
 * DEC-35.
 *
 * <p>Method-set: EXACTLY one public method enumerated from the legacy {@code
 * ActivityScheduleAssembler} (deleted at E24S07 atomic cutover) and its callers
 * (PrintController.activityScheduleAssembler.assemble) — AC-METHOD-SET-ENUMERATION.
 *
 * @see ActivityScheduleModel
 * @see ActivityScheduleRow
 * @see de.vvwt.tm.print.internal.DefaultActivityScheduleAssembler
 * @since E24S03
 */
public interface ActivityScheduleAssembler {

    /**
     * Assembles the activity schedule for a single activity type.
     *
     * @param tournament the tournament (for start time and tenant context)
     * @param phases all phases sorted by {@code sequenceNumber} ascending; must not be null
     * @param teams all teams participating; must not be null
     * @param avatarsByPhase phaseId → TeamAvatars; must not be null
     * @param matchesByPhase phaseId → Matches; must not be null
     * @param breaksByPhase phaseId → PhaseBreaks; must not be null
     * @param activityTypes ALL activity types for this tournament; must not be null
     * @param targetType the specific activity type whose schedule to render; must not be null
     * @return assembled model with rows, summary, and unassigned teams; never null
     */
    ActivityScheduleModel assemble(
            Tournament tournament,
            List<Phase> phases,
            List<Team> teams,
            Map<UUID, List<TeamAvatar>> avatarsByPhase,
            Map<UUID, List<Match>> matchesByPhase,
            Map<UUID, List<PhaseBreak>> breaksByPhase,
            List<ActivityType> activityTypes,
            ActivityType targetType);
}
