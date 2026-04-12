package de.vvwt.tm.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.domain.draft.DraftConfig;
import de.vvwt.tm.domain.draft.DraftPreviewSection;
import de.vvwt.tm.domain.draft.DraftSection;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DraftService} (E05S06).
 *
 * <p>Mocks all dependencies to isolate service logic. Verifies:
 * <ul>
 *   <li>AC2 — saveDraft persists JSON; rejects non-DRAFT tournaments</li>
 *   <li>AC3 — getDraft returns empty config when draft_json is null</li>
 *   <li>AC4 — previewDraft calculates correct values for round-robin</li>
 *   <li>AC5 — applyDraft creates phases; validates preconditions</li>
 *   <li>AC6 — Phase 1 TeamAvatars distributed by round-robin by team_number</li>
 *   <li>AC7 — applyDraft transitions tournament to PLANNED</li>
 *   <li>AC11 — setQuantity validation against matchFormat</li>
 *   <li>AC12 — re-apply rejected when phases already exist</li>
 * </ul>
 *
 * @see <a href="../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story E05S06</a>
 */
@ExtendWith(MockitoExtension.class)
class DraftServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private PhaseRepository phaseRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;

    private DraftService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DraftService(
                tournamentRepository,
                phaseRepository,
                teamRepository,
                teamAvatarRepository,
                objectMapper);
    }

    // =========================================================================
    // AC2 — saveDraft
    // =========================================================================

    @Test
    void saveDraftPersistsDraftJsonOnDraftTournament() {
        Tournament tournament = makeDraftTournament(null);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DraftConfig config = oneSection();
        DraftConfig saved = service.saveDraft(TOURNAMENT_ID, config);

        assertThat(saved.getSections()).hasSize(1);
        ArgumentCaptor<Tournament> captor = ArgumentCaptor.forClass(Tournament.class);
        verify(tournamentRepository).save(captor.capture());
        assertThat(captor.getValue().getDraftJson()).isNotBlank();
    }

    @Test
    void saveDraftRejectsPlannedTournament() {
        Tournament planned = makeTournamentWithStatus("PLANNED", null);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(planned));

        assertThatThrownBy(() -> service.saveDraft(TOURNAMENT_ID, oneSection()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PLANNED");

        verify(tournamentRepository, never()).save(any());
    }

    // =========================================================================
    // AC3 — getDraft
    // =========================================================================

    @Test
    void getDraftReturnsEmptyConfigWhenDraftJsonIsNull() {
        Tournament tournament = makeDraftTournament(null);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        DraftConfig config = service.getDraft(TOURNAMENT_ID);

        assertThat(config.getSections()).isEmpty();
    }

    @Test
    void getDraftDeserializesExistingDraftJson() throws Exception {
        DraftConfig original = oneSection();
        String json = objectMapper.writeValueAsString(original);
        Tournament tournament = makeDraftTournament(json);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        DraftConfig result = service.getDraft(TOURNAMENT_ID);

        assertThat(result.getSections()).hasSize(1);
        assertThat(result.getSections().get(0).getGroupCount()).isEqualTo(2);
    }

    // =========================================================================
    // AC4 — previewDraft
    // =========================================================================

    @Test
    void previewDraftCalculatesCorrectValuesFor8TeamsIn2Groups() throws Exception {
        // 8 teams, 2 groups → 4 teams/group, 6 matches/group, 3 laps
        DraftConfig config = oneSection(); // groupCount=2, lapTimeMinutes=15, lapBreak=5, sectionBreak=10
        String json = objectMapper.writeValueAsString(config);
        Tournament tournament = makeDraftTournament(json);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        List<Team> teams = makeTeams(8);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(teams);

        List<DraftPreviewSection> previews = service.previewDraft(TOURNAMENT_ID);

        assertThat(previews).hasSize(1);
        DraftPreviewSection preview = previews.get(0);
        assertThat(preview.getPhaseNumber()).isEqualTo(1);
        assertThat(preview.getGroupCount()).isEqualTo(2);
        assertThat(preview.getTeamsPerGroup()).isEqualTo(4);
        assertThat(preview.getMatchesPerGroup()).isEqualTo(6); // 4*(4-1)/2 = 6
        assertThat(preview.getTotalLaps()).isEqualTo(3);      // 4-1 = 3
        assertThat(preview.getTotalMatches()).isEqualTo(12);  // 2*6 = 12
        // estimatedTime = 3 laps * 15min + (3-1)*5min break + 10min section break
        //               = 45 + 10 + 10 = 65
        assertThat(preview.getEstimatedTimeMinutes()).isEqualTo(65);
    }

    @Test
    void previewDraftThrowsWhenDraftIsEmpty() {
        // Empty sections → throws before any team repo call
        Tournament tournament = makeDraftTournament(null);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        // teamRepository is NOT called — sections check fires before team count

        assertThatThrownBy(() -> service.previewDraft(TOURNAMENT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty draft");
    }

    // =========================================================================
    // AC5 — applyDraft: basic apply
    // =========================================================================

    @Test
    void applyDraftCreatesPhaseAndTeamAvatars() throws Exception {
        DraftConfig config = oneSection(); // groupCount=2
        String json = objectMapper.writeValueAsString(config);
        Tournament tournament = makeDraftTournament(json);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(makeTeams(4));
        when(phaseRepository.save(any(Phase.class))).thenAnswer(inv -> {
            Phase p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        when(teamAvatarRepository.save(any(TeamAvatar.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tournamentRepository.save(any(Tournament.class))).thenAnswer(inv -> inv.getArgument(0));

        List<UUID> phaseIds = service.applyDraft(TOURNAMENT_ID);

        assertThat(phaseIds).hasSize(1);

        // AC7: tournament saved with PLANNED status and null draftJson
        ArgumentCaptor<Tournament> tournamentCaptor = ArgumentCaptor.forClass(Tournament.class);
        verify(tournamentRepository, atLeastOnce()).save(tournamentCaptor.capture());
        Tournament savedTournament = tournamentCaptor.getAllValues().stream()
                .filter(t -> DraftService.STATUS_PLANNED.equals(t.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected PLANNED status"));
        assertThat(savedTournament.getDraftJson()).isNull();

        // AC6: 4 teams → 4 TeamAvatars created for Phase 1
        verify(teamAvatarRepository, times(4)).save(any(TeamAvatar.class));
    }

    // =========================================================================
    // AC6 — Phase 1 TeamAvatar distribution
    // =========================================================================

    @Test
    void applyDraftDistributesTeamsRoundRobinAcrossGroups() throws Exception {
        // 6 teams, 2 groups → team[0,1,2] → groups 1,2,1, team[3,4,5] → groups 2,1,2
        // i%2+1 groupNumber; i/2+1 groupPosition
        DraftConfig config = oneSection(); // groupCount=2
        String json = objectMapper.writeValueAsString(config);
        Tournament tournament = makeDraftTournament(json);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());
        List<Team> teams = makeTeams(6);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(teams);
        when(phaseRepository.save(any(Phase.class))).thenAnswer(inv -> {
            Phase p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        ArgumentCaptor<TeamAvatar> avatarCaptor = ArgumentCaptor.forClass(TeamAvatar.class);
        when(teamAvatarRepository.save(any(TeamAvatar.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tournamentRepository.save(any(Tournament.class))).thenAnswer(inv -> inv.getArgument(0));

        service.applyDraft(TOURNAMENT_ID);

        verify(teamAvatarRepository, times(6)).save(avatarCaptor.capture());
        List<TeamAvatar> avatars = avatarCaptor.getAllValues();

        // Team 0 → group 1, position 1
        assertThat(avatars.get(0).getGroupNumber()).isEqualTo(1);
        assertThat(avatars.get(0).getGroupPosition()).isEqualTo(1);
        // Team 1 → group 2, position 1
        assertThat(avatars.get(1).getGroupNumber()).isEqualTo(2);
        assertThat(avatars.get(1).getGroupPosition()).isEqualTo(1);
        // Team 2 → group 1, position 2
        assertThat(avatars.get(2).getGroupNumber()).isEqualTo(1);
        assertThat(avatars.get(2).getGroupPosition()).isEqualTo(2);
        // Team 3 → group 2, position 2
        assertThat(avatars.get(3).getGroupNumber()).isEqualTo(2);
        assertThat(avatars.get(3).getGroupPosition()).isEqualTo(2);
        // Team 4 → group 1, position 3
        assertThat(avatars.get(4).getGroupNumber()).isEqualTo(1);
        assertThat(avatars.get(4).getGroupPosition()).isEqualTo(3);
        // Team 5 → group 2, position 3
        assertThat(avatars.get(5).getGroupNumber()).isEqualTo(2);
        assertThat(avatars.get(5).getGroupPosition()).isEqualTo(3);
    }

    // =========================================================================
    // AC5 — applyDraft: precondition failures
    // =========================================================================

    @Test
    void applyDraftRejectsNonDraftTournament() {
        Tournament planned = makeTournamentWithStatus("PLANNED", null);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(planned));

        assertThatThrownBy(() -> service.applyDraft(TOURNAMENT_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PLANNED");
    }

    @Test
    void applyDraftRejectsEmptyDraft() {
        // Empty draft → rejected by sections.isEmpty() check before phases are checked
        Tournament tournament = makeDraftTournament(null);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        // phaseRepository not needed here — sections check fires first

        assertThatThrownBy(() -> service.applyDraft(TOURNAMENT_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no sections");
    }

    @Test
    void applyDraftRejectsFewerThan2ParticipatingTeams() throws Exception {
        DraftConfig config = oneSection();
        String json = objectMapper.writeValueAsString(config);
        Tournament tournament = makeDraftTournament(json);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());
        // Only 1 participating team
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(makeTeams(1));

        assertThatThrownBy(() -> service.applyDraft(TOURNAMENT_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("2 participating teams");
    }

    // =========================================================================
    // AC12 — re-apply prevention
    // =========================================================================

    @Test
    void applyDraftRejectsWhenPhasesAlreadyExist() throws Exception {
        DraftConfig config = oneSection();
        String json = objectMapper.writeValueAsString(config);
        Tournament tournament = makeDraftTournament(json);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        Phase existingPhase = new Phase(UUID.randomUUID(), TENANT_ID, TOURNAMENT_ID,
                1, "Phase 1", "PENDING", 0, LocalDateTime.now());
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(existingPhase));

        assertThatThrownBy(() -> service.applyDraft(TOURNAMENT_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already has");
    }

    // =========================================================================
    // AC11 — setQuantity validation
    // =========================================================================

    @Test
    void applyDraftRejectsSetQuantityExceedingMatchFormatMaxSets() throws Exception {
        // BEST_OF_1 has maxSets=1; setQuantity=3 should fail (AC11)
        // Validation fires before team fetch — teamRepository is NOT stubbed here.
        DraftSection section = new DraftSection(1, "team_number", 2, "roundrobin", 5, 10, 15, 3);
        DraftConfig config = new DraftConfig(List.of(section));
        String json = objectMapper.writeValueAsString(config);
        // Tournament with BEST_OF_1 match format
        Tournament tournament = new Tournament(
                TOURNAMENT_ID, TENANT_ID, "Test", "BEST_OF_1",
                "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now(), null, 1, 8);
        tournament.setDraftJson(json);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());
        // teamRepository NOT called — setQuantity validation fires first

        assertThatThrownBy(() -> service.applyDraft(TOURNAMENT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setQuantity");
    }

    // =========================================================================
    // DraftSection.validate() — AC1 field constraints
    // =========================================================================

    @Test
    void draftSectionValidateThrowsOnZeroGroupCount() {
        DraftSection s = new DraftSection(1, "team_number", 0, "roundrobin", 5, 10, 15, 1);
        assertThatThrownBy(s::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupCount");
    }

    @Test
    void draftSectionValidateThrowsOnNegativeLapBreak() {
        DraftSection s = new DraftSection(1, "team_number", 2, "roundrobin", -1, 10, 15, 1);
        assertThatThrownBy(s::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lapBreakTimeMinutes");
    }

    @Test
    void draftSectionValidateThrowsOnZeroLapTime() {
        DraftSection s = new DraftSection(1, "team_number", 2, "roundrobin", 5, 10, 0, 1);
        assertThatThrownBy(s::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lapTimeMinutes");
    }

    @Test
    void draftSectionValidateThrowsOnInvalidSortType() {
        DraftSection s = new DraftSection(1, "invalid_sort", 2, "roundrobin", 5, 10, 15, 1);
        assertThatThrownBy(s::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sortType");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Tournament makeDraftTournament(String draftJson) {
        Tournament t = makeTournamentWithStatus("DRAFT", draftJson);
        return t;
    }

    private Tournament makeTournamentWithStatus(String status, String draftJson) {
        Tournament t = new Tournament(
                TOURNAMENT_ID, TENANT_ID, "Test Tournament",
                "BEST_OF_3", "setPoints", "standardVolleyball", "roundRobin",
                status, LocalDateTime.now(), null, 1, 8);
        t.setDraftJson(draftJson);
        return t;
    }

    /** Creates a DraftConfig with one section: groupCount=2, lapTime=15, lapBreak=5, sectionBreak=10, setQuantity=1. */
    private DraftConfig oneSection() {
        DraftSection section = new DraftSection(1, "team_number", 2, "roundrobin", 5, 10, 15, 1);
        return new DraftConfig(List.of(section));
    }

    /** Creates N participating teams with sequential teamNumbers 1..N. */
    private List<Team> makeTeams(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(n -> new Team(UUID.randomUUID(), TENANT_ID, TOURNAMENT_ID,
                        n, "Team " + n, true, false, false, LocalDateTime.now()))
                .toList();
    }
}
