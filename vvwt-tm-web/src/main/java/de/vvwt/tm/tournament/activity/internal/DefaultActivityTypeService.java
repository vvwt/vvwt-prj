package de.vvwt.tm.tournament.activity.internal;

import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.activity.ActivityType;
import de.vvwt.tm.tournament.activity.ActivityTypeRepository;
import de.vvwt.tm.tournament.activity.ActivityTypeService;
import de.vvwt.tm.tournament.activity.AssignmentRule;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link ActivityTypeService} (DEC-35 naming canon).
 *
 * <p>Domain service for {@link ActivityType} operations (E08S02, AC4–AC8).
 *
 * <p><b>E45S01 relocation note:</b> Relocated and refactored from {@code
 * de.vvwt.tm.domain.ActivityTypeService} (concrete class) into DEC-35 hexagonal-pragma layout:
 * public interface in {@code tournament.activity}; this implementation in {@code
 * tournament.activity.internal}. Behavior is byte-equivalent — no logic changes.
 *
 * @see ActivityTypeService
 */
@Service
public class DefaultActivityTypeService implements ActivityTypeService {

    private final ActivityTypeRepository activityTypeRepository;
    private final TournamentRepository tournamentRepository;
    private final MessageSource messageSource;

    public DefaultActivityTypeService(
            ActivityTypeRepository activityTypeRepository,
            TournamentRepository tournamentRepository,
            MessageSource messageSource) {
        this.activityTypeRepository = activityTypeRepository;
        this.tournamentRepository = tournamentRepository;
        this.messageSource = messageSource;
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Override
    public ActivityType create(
            UUID tournamentId,
            String name,
            String assignmentRule,
            Integer capacityPerRound,
            int sortOrder) {
        // Ownership check: tournament must exist and belong to the active tenant
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));

        validateAssignmentRule(assignmentRule);
        validateCapacity(capacityPerRound);

        if (activityTypeRepository.existsByTournamentIdAndName(tournamentId, name)) {
            String message =
                    messageSource.getMessage(
                            "error.activityType.duplicateName", null, Locale.getDefault());
            throw new ConflictException(message);
        }

        ActivityType activityType =
                new ActivityType(
                        UUID.randomUUID(),
                        tournamentId,
                        name,
                        assignmentRule,
                        capacityPerRound,
                        sortOrder);
        return activityTypeRepository.save(activityType);
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    @Override
    public ActivityType update(
            UUID tournamentId,
            UUID id,
            String name,
            String assignmentRule,
            Integer capacityPerRound,
            int sortOrder) {
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));

        ActivityType existing =
                activityTypeRepository
                        .findById(id)
                        .orElseThrow(
                                () -> new NoSuchElementException("ActivityType not found: " + id));

        validateAssignmentRule(assignmentRule);
        validateCapacity(capacityPerRound);

        boolean nameConflict =
                activityTypeRepository.findByTournamentId(tournamentId).stream()
                        .anyMatch(at -> name.equals(at.getName()) && !id.equals(at.getId()));
        if (nameConflict) {
            String message =
                    messageSource.getMessage(
                            "error.activityType.duplicateName", null, Locale.getDefault());
            throw new ConflictException(message);
        }

        existing.setName(name);
        existing.setAssignmentRule(assignmentRule);
        existing.setCapacityPerRound(capacityPerRound);
        existing.setSortOrder(sortOrder);
        return activityTypeRepository.save(existing);
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    @Override
    public void delete(UUID tournamentId, UUID id) {
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));

        activityTypeRepository
                .findById(id)
                .orElseThrow(() -> new NoSuchElementException("ActivityType not found: " + id));

        activityTypeRepository.deleteById(id);
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    @Override
    public List<ActivityType> findByTournamentId(UUID tournamentId) {
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));
        return activityTypeRepository.findByTournamentId(tournamentId);
    }

    // -------------------------------------------------------------------------
    // Private validation helpers
    // -------------------------------------------------------------------------

    private void validateAssignmentRule(String assignmentRule) {
        String supported =
                Arrays.stream(AssignmentRule.values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", "));
        try {
            AssignmentRule.valueOf(assignmentRule);
        } catch (IllegalArgumentException | NullPointerException e) {
            String message =
                    messageSource.getMessage(
                            "error.activityType.unknownRule",
                            new Object[] {assignmentRule, supported},
                            Locale.getDefault());
            throw new IllegalArgumentException(message);
        }
    }

    private void validateCapacity(Integer capacityPerRound) {
        if (capacityPerRound != null && capacityPerRound <= 0) {
            String message =
                    messageSource.getMessage(
                            "error.activityType.invalidCapacity", null, Locale.getDefault());
            throw new IllegalArgumentException(message);
        }
    }
}
