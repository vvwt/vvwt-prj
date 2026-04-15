package de.vvwt.tm.domain.activity;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.domain.AssignmentRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Spring {@code @Service} implementation of {@link ActivityAssignmentService}.
 *
 * <p>Dispatches each {@link ActivityType} to the appropriate rule implementation based on
 * {@link ActivityType#getAssignmentRule()}. V1 supports {@link AssignmentRule#FIRST_FREE_ROUND}
 * only — any other rule value triggers {@link UnsupportedAssignmentRuleException} (AC8).
 *
 * <p>Each activity type is processed independently — there is no shared mutable state
 * between activity types (AC5).
 *
 * <p>The service makes no direct database queries (AC10). All inputs are pre-loaded by
 * the caller.
 *
 * <h2>Determinism (AC7)</h2>
 * <p>Given identical inputs, the service always produces identical outputs. Tie-breaking
 * within a lap's free-team set uses UUID natural ordering (ascending), which is total and
 * stable.
 *
 * @see FirstFreeRoundAssigner
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S04.story.md">Story E08S04</a>
 */
@Service
public class ActivityAssignmentServiceImpl implements ActivityAssignmentService {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityAssignmentServiceImpl.class);

    /** Stateless; safe to share. */
    private final FirstFreeRoundAssigner firstFreeRoundAssigner = new FirstFreeRoundAssigner();

    /**
     * {@inheritDoc}
     *
     * <p>Processes each activity type in the order provided. Returns an
     * {@link ActivityAssignmentResult} containing all assignments and the set of teams
     * that could not be assigned per type.
     *
     * @throws IllegalArgumentException         if any required parameter is null or totalLapCount &lt; 1
     * @throws UnsupportedAssignmentRuleException if an activity type references an unknown rule (AC8)
     */
    @Override
    public ActivityAssignmentResult assignActivities(
            List<ActivityType> activityTypes,
            Map<Integer, Set<UUID>> matchSchedule,
            Map<Integer, Set<UUID>> refereeSchedule,
            int totalLapCount,
            Set<UUID> allTeamIds) {

        // Input validation
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

        // Short-circuit: no activity types → return empty result
        if (activityTypes.isEmpty()) {
            LOG.debug("ActivityAssignmentService: no activity types provided — returning empty result.");
            return new ActivityAssignmentResult(Map.of(), Map.of());
        }

        LapSchedule lapSchedule = new LapSchedule(matchSchedule, refereeSchedule);

        // LinkedHashMap preserves the order of activityTypes (stable for tests and templates)
        Map<ActivityType, List<ActivityAssignment>> resultAssignments = new LinkedHashMap<>();
        Map<ActivityType, Set<UUID>> resultUnassigned = new LinkedHashMap<>();

        for (ActivityType activityType : activityTypes) {
            if (activityType == null) {
                throw new IllegalArgumentException("activityTypes list must not contain null entries");
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
                    ruleResult = firstFreeRoundAssigner.assign(
                            activityType, lapSchedule, totalLapCount, allTeamIds);
                }
                default -> {
                    // Defensive: new enum constants without a switch arm throw immediately (AC8)
                    throw new UnsupportedAssignmentRuleException(ruleName);
                }
            }

            resultAssignments.put(activityType, ruleResult.getAssignments());
            resultUnassigned.put(activityType, ruleResult.getUnassignedTeams());

            LOG.info("ActivityAssignmentService: activityType='{}' rule={} assigned={} unassigned={}",
                    activityType.getName(),
                    rule,
                    ruleResult.getAssignments().size(),
                    ruleResult.getUnassignedTeams().size());
        }

        return new ActivityAssignmentResult(resultAssignments, resultUnassigned);
    }
}
