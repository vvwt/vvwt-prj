package de.vvwt.tm.tournament.activity.internal;

import de.vvwt.tm.tournament.activity.ActivityAssignmentResult;
import de.vvwt.tm.tournament.activity.ActivityAssignmentService;
import de.vvwt.tm.tournament.activity.ActivityType;
import de.vvwt.tm.tournament.activity.AssignmentRule;
import de.vvwt.tm.tournament.activity.UnsupportedAssignmentRuleException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link ActivityAssignmentService} (DEC-35 naming canon).
 *
 * <p>Dispatches each {@link ActivityType} to the appropriate rule implementation based on {@link
 * ActivityType#getAssignmentRule()}. V1 supports {@link AssignmentRule#FIRST_FREE_ROUND} only.
 *
 * <p><b>E45S01 relocation note:</b> Relocated and renamed from {@code
 * de.vvwt.tm.domain.activity.ActivityAssignmentServiceImpl} per DEC-35 naming canon ({@code
 * Default*Service} not {@code *ServiceImpl}). Behavior byte-equivalent.
 *
 * @see ActivityAssignmentService
 * @see FirstFreeRoundAssigner
 */
@Service
public class DefaultActivityAssignmentService implements ActivityAssignmentService {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultActivityAssignmentService.class);

    private final FirstFreeRoundAssigner firstFreeRoundAssigner = new FirstFreeRoundAssigner();

    @Override
    public ActivityAssignmentResult assignActivities(
            List<ActivityType> activityTypes,
            Map<Integer, Set<UUID>> matchSchedule,
            Map<Integer, Set<UUID>> refereeSchedule,
            int totalLapCount,
            Set<UUID> allTeamIds) {

        if (activityTypes == null) {
            throw new IllegalArgumentException("activityTypes must not be null");
        }
        if (matchSchedule == null) {
            throw new IllegalArgumentException("matchSchedule must not be null");
        }
        if (refereeSchedule == null) {
            throw new IllegalArgumentException("refereeSchedule must not be null");
        }
        if (totalLapCount < 1) {
            throw new IllegalArgumentException("totalLapCount must be >= 1, got " + totalLapCount);
        }
        if (allTeamIds == null) {
            throw new IllegalArgumentException("allTeamIds must not be null");
        }

        if (activityTypes.isEmpty()) {
            LOG.debug(
                    "DefaultActivityAssignmentService: no activity types provided — returning empty"
                            + " result.");
            return new ActivityAssignmentResult(Map.of(), Map.of());
        }

        LapSchedule lapSchedule = new LapSchedule(matchSchedule, refereeSchedule);

        Map<ActivityType, List<de.vvwt.tm.tournament.activity.ActivityAssignment>>
                resultAssignments = new LinkedHashMap<>();
        Map<ActivityType, Set<UUID>> resultUnassigned = new LinkedHashMap<>();

        for (ActivityType activityType : activityTypes) {
            if (activityType == null) {
                throw new IllegalArgumentException(
                        "activityTypes list must not contain null entries");
            }

            String ruleName = activityType.getAssignmentRule();
            AssignmentRule rule;
            try {
                rule = AssignmentRule.valueOf(ruleName);
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new UnsupportedAssignmentRuleException(ruleName);
            }

            FirstFreeRoundAssigner.FirstFreeRoundResult ruleResult;
            switch (rule) {
                case FIRST_FREE_ROUND -> {
                    ruleResult =
                            firstFreeRoundAssigner.assign(
                                    activityType, lapSchedule, totalLapCount, allTeamIds);
                }
                default -> {
                    throw new UnsupportedAssignmentRuleException(ruleName);
                }
            }

            resultAssignments.put(activityType, ruleResult.getAssignments());
            resultUnassigned.put(activityType, ruleResult.getUnassignedTeams());

            LOG.info(
                    "DefaultActivityAssignmentService: activityType='{}' rule={} assigned={}"
                            + " unassigned={}",
                    activityType.getName(),
                    rule,
                    ruleResult.getAssignments().size(),
                    ruleResult.getUnassignedTeams().size());
        }

        return new ActivityAssignmentResult(resultAssignments, resultUnassigned);
    }
}
