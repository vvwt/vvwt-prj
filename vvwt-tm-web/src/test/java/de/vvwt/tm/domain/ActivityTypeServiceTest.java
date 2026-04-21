package de.vvwt.tm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.domain.repo.ActivityTypeRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

/**
 * Unit tests for {@link ActivityTypeService} update and delete operations (E20S02, AC0 service
 * prerequisite).
 *
 * <p>These methods are new additions required by the E08S06-REST reconstruction under Approach C.
 * Tests follow DEC-22 Iron Law: written RED before the methods were added to the service.
 *
 * @see ActivityTypeService
 */
@DisplayName("ActivityTypeService — update() and delete() unit tests")
class ActivityTypeServiceTest {

    private ActivityTypeRepository activityTypeRepository;
    private TournamentRepository tournamentRepository;
    private MessageSource messageSource;
    private ActivityTypeService service;

    private final UUID TENANT_ID = UUID.randomUUID();
    private final UUID TOURNAMENT_ID = UUID.randomUUID();
    private final UUID ACTIVITY_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        activityTypeRepository = mock(ActivityTypeRepository.class);
        tournamentRepository = mock(TournamentRepository.class);
        messageSource = mock(MessageSource.class);

        // Default: tournament exists
        de.vvwt.tm.tournament.Tournament tournament = mock(de.vvwt.tm.tournament.Tournament.class);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        // Default message source returns fallback strings
        when(messageSource.getMessage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn("error message");

        service =
                new ActivityTypeService(
                        activityTypeRepository, tournamentRepository, messageSource);
    }

    // =========================================================================
    // update() — happy path
    // =========================================================================

    @Test
    @DisplayName("update() — returns updated entity when activity type exists and name is unique")
    void updateReturnsUpdatedEntity() {
        ActivityType existing =
                new ActivityType(
                        ACTIVITY_ID,
                        TOURNAMENT_ID,
                        "OldName",
                        "FIRST_FREE_ROUND",
                        null,
                        1,
                        TENANT_ID);
        when(activityTypeRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(existing));
        // Uniqueness check: no other entry with "NewName" in the tournament
        when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(java.util.List.of(existing));

        ActivityType updated =
                new ActivityType(
                        ACTIVITY_ID, TOURNAMENT_ID, "NewName", "FIRST_FREE_ROUND", 3, 2, TENANT_ID);
        when(activityTypeRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(updated);

        ActivityType result =
                service.update(TOURNAMENT_ID, ACTIVITY_ID, "NewName", "FIRST_FREE_ROUND", 3, 2);

        assertThat(result.getName()).isEqualTo("NewName");
        assertThat(result.getCapacityPerRound()).isEqualTo(3);
    }

    @Test
    @DisplayName("update() — throws NoSuchElementException when activity type not found")
    void updateThrowsWhenActivityNotFound() {
        when(activityTypeRepository.findById(ACTIVITY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.update(
                                        TOURNAMENT_ID,
                                        ACTIVITY_ID,
                                        "Name",
                                        "FIRST_FREE_ROUND",
                                        null,
                                        1))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName(
            "update() — throws ConflictException when name conflicts with a DIFFERENT activity")
    void updateThrowsConflictWhenDuplicateName() {
        ActivityType self =
                new ActivityType(
                        ACTIVITY_ID,
                        TOURNAMENT_ID,
                        "OldName",
                        "FIRST_FREE_ROUND",
                        null,
                        1,
                        TENANT_ID);
        ActivityType other =
                new ActivityType(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        "TakenName",
                        "FIRST_FREE_ROUND",
                        null,
                        2,
                        TENANT_ID);
        when(activityTypeRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(self));
        when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(java.util.List.of(self, other));

        assertThatThrownBy(
                        () ->
                                service.update(
                                        TOURNAMENT_ID,
                                        ACTIVITY_ID,
                                        "TakenName",
                                        "FIRST_FREE_ROUND",
                                        null,
                                        1))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("update() — allows keeping the same name (no conflict with self)")
    void updateAllowsSameName() {
        ActivityType self =
                new ActivityType(
                        ACTIVITY_ID,
                        TOURNAMENT_ID,
                        "SameName",
                        "FIRST_FREE_ROUND",
                        null,
                        1,
                        TENANT_ID);
        when(activityTypeRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(self));
        when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(java.util.List.of(self));

        ActivityType saved =
                new ActivityType(
                        ACTIVITY_ID,
                        TOURNAMENT_ID,
                        "SameName",
                        "FIRST_FREE_ROUND",
                        null,
                        1,
                        TENANT_ID);
        when(activityTypeRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(saved);

        ActivityType result =
                service.update(TOURNAMENT_ID, ACTIVITY_ID, "SameName", "FIRST_FREE_ROUND", null, 1);

        assertThat(result.getName()).isEqualTo("SameName");
    }

    @Test
    @DisplayName("update() — throws IllegalArgumentException for unknown assignment rule")
    void updateThrowsForUnknownRule() {
        ActivityType existing =
                new ActivityType(
                        ACTIVITY_ID, TOURNAMENT_ID, "Name", "FIRST_FREE_ROUND", null, 1, TENANT_ID);
        when(activityTypeRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(
                        () ->
                                service.update(
                                        TOURNAMENT_ID,
                                        ACTIVITY_ID,
                                        "Name",
                                        "UNKNOWN_RULE",
                                        null,
                                        1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("update() — throws IllegalArgumentException for capacity <= 0")
    void updateThrowsForInvalidCapacity() {
        ActivityType existing =
                new ActivityType(
                        ACTIVITY_ID, TOURNAMENT_ID, "Name", "FIRST_FREE_ROUND", null, 1, TENANT_ID);
        when(activityTypeRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(existing));
        when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(java.util.List.of(existing));

        assertThatThrownBy(
                        () ->
                                service.update(
                                        TOURNAMENT_ID,
                                        ACTIVITY_ID,
                                        "Name",
                                        "FIRST_FREE_ROUND",
                                        0,
                                        1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // delete() — happy path
    // =========================================================================

    @Test
    @DisplayName("delete() — deletes the activity type when it exists for the tournament")
    void deleteRemovesExistingActivity() {
        ActivityType existing =
                new ActivityType(
                        ACTIVITY_ID,
                        TOURNAMENT_ID,
                        "ToDelete",
                        "FIRST_FREE_ROUND",
                        null,
                        1,
                        TENANT_ID);
        when(activityTypeRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(existing));

        service.delete(TOURNAMENT_ID, ACTIVITY_ID);

        verify(activityTypeRepository).deleteById(ACTIVITY_ID);
    }

    @Test
    @DisplayName("delete() — throws NoSuchElementException when activity type not found")
    void deleteThrowsWhenNotFound() {
        when(activityTypeRepository.findById(ACTIVITY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(TOURNAMENT_ID, ACTIVITY_ID))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("delete() — throws NoSuchElementException when tournament not found")
    void deleteThrowsWhenTournamentNotFound() {
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(TOURNAMENT_ID, ACTIVITY_ID))
                .isInstanceOf(NoSuchElementException.class);

        verify(activityTypeRepository, never()).deleteById(org.mockito.ArgumentMatchers.any());
    }
}
