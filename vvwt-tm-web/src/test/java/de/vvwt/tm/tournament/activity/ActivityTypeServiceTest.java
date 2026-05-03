package de.vvwt.tm.tournament.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.activity.internal.DefaultActivityTypeService;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

/**
 * Q-1a TDD tests for {@link ActivityTypeService} (create, update, delete, findByTournamentId).
 *
 * <p>Written RED-first under DEC-22 Iron Law against the relocated production code in {@code
 * de.vvwt.tm.tournament.activity}.
 *
 * <p>This test is in the {@code tournament.activity} package (same as the interface), different
 * from {@code tournament.activity.internal} (where the impl lives). Per DEC-36, the subject is
 * typed and mocked via the public {@link ActivityTypeService} interface. Collaborators are mocked
 * via their public interfaces ({@link ActivityTypeRepository}, {@link TournamentRepository}).
 */
@DisplayName("ActivityTypeService — create/update/delete/findByTournamentId (relocated)")
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

        de.vvwt.tm.tournament.Tournament tournament = mock(de.vvwt.tm.tournament.Tournament.class);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        when(messageSource.getMessage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn("error message");

        service =
                new DefaultActivityTypeService(
                        activityTypeRepository, tournamentRepository, messageSource);
    }

    // =========================================================================
    // create()
    // =========================================================================

    @Test
    @DisplayName("create() — returns saved entity when all validations pass")
    void createReturnsSavedEntity() {
        when(activityTypeRepository.existsByTournamentIdAndName(TOURNAMENT_ID, "Photo"))
                .thenReturn(false);
        ActivityType saved =
                new ActivityType(
                        ACTIVITY_ID,
                        TOURNAMENT_ID,
                        "Photo",
                        "FIRST_FREE_ROUND",
                        null,
                        1,
                        TENANT_ID);
        when(activityTypeRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(saved);

        ActivityType result = service.create(TOURNAMENT_ID, "Photo", "FIRST_FREE_ROUND", null, 1);

        assertThat(result.getName()).isEqualTo("Photo");
    }

    @Test
    @DisplayName("create() — throws NoSuchElementException when tournament not found")
    void createThrowsWhenTournamentNotFound() {
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> service.create(TOURNAMENT_ID, "Photo", "FIRST_FREE_ROUND", null, 1))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("create() — throws ConflictException for duplicate name")
    void createThrowsConflictForDuplicateName() {
        when(activityTypeRepository.existsByTournamentIdAndName(TOURNAMENT_ID, "Photo"))
                .thenReturn(true);

        assertThatThrownBy(
                        () -> service.create(TOURNAMENT_ID, "Photo", "FIRST_FREE_ROUND", null, 1))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("create() — throws IllegalArgumentException for unknown assignment rule")
    void createThrowsForUnknownRule() {
        assertThatThrownBy(() -> service.create(TOURNAMENT_ID, "Photo", "UNKNOWN_RULE", null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create() — throws IllegalArgumentException for capacity <= 0")
    void createThrowsForInvalidCapacity() {
        assertThatThrownBy(() -> service.create(TOURNAMENT_ID, "Photo", "FIRST_FREE_ROUND", 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // update()
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
        when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(existing));

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
                .thenReturn(List.of(self, other));

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
        when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(self));
        when(activityTypeRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(self);

        ActivityType result =
                service.update(TOURNAMENT_ID, ACTIVITY_ID, "SameName", "FIRST_FREE_ROUND", null, 1);

        assertThat(result.getName()).isEqualTo("SameName");
    }

    // =========================================================================
    // delete()
    // =========================================================================

    @Test
    @DisplayName("delete() — deletes the activity type when it exists")
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

    // =========================================================================
    // findByTournamentId()
    // =========================================================================

    @Test
    @DisplayName("findByTournamentId() — returns activity types from repository")
    void findByTournamentIdDelegatesToRepository() {
        ActivityType at =
                new ActivityType(
                        ACTIVITY_ID,
                        TOURNAMENT_ID,
                        "Photo",
                        "FIRST_FREE_ROUND",
                        null,
                        1,
                        TENANT_ID);
        when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(at));

        List<ActivityType> result = service.findByTournamentId(TOURNAMENT_ID);

        assertThat(result).containsExactly(at);
    }

    @Test
    @DisplayName("findByTournamentId() — throws NoSuchElementException when tournament not found")
    void findByTournamentIdThrowsWhenTournamentNotFound() {
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByTournamentId(TOURNAMENT_ID))
                .isInstanceOf(NoSuchElementException.class);
    }
}
