package de.vvwt.tm.domain.repo;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.domain.DraftService;
import de.vvwt.tm.domain.MappingAssignment;
import de.vvwt.tm.domain.MappingSuggestion;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.PhaseMappingService;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.draft.DraftConfig;
import de.vvwt.tm.domain.draft.DraftSection;
import de.vvwt.tm.infrastructure.web.ConflictException;
import de.vvwt.tm.tenant.DefaultTenantProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for E05S08 — Team mapping between phases.
 *
 * <p>Uses the full Spring context with an in-memory H2 database and all Flyway migrations applied.
 * Tests verify end-to-end behavior of:
 * <ul>
 *   <li>V10 migration — sort_type and group_count columns exist on phase table</li>
 *   <li>DraftService persists sort_type + group_count when creating phases (E05S08 enhancement)</li>
 *   <li>AC1 — getMappingSuggestion returns 409 when previous phase is not COMPLETED</li>
 *   <li>AC2 — applyMapping creates TeamAvatars and returns 409 if already exist</li>
 *   <li>AC10 — redoMapping deletes existing and re-applies</li>
 *   <li>AC9 — previous phase not COMPLETED → 409</li>
 * </ul>
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E05S08.story.md">Story E05S08</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e05s08db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Transactional
class E05S08MappingIT {

    @Autowired private PhaseMappingService phaseMappingService;
    @Autowired private DraftService draftService;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamAvatarRepository teamAvatarRepository;
    @Autowired private TenantContext tenantContext;
    @Autowired private DefaultTenantProvider defaultTenantProvider;

    private UUID tournamentId;
    private UUID defaultTenantId;
    private UUID phase1Id;
    private UUID phase2Id;

    @BeforeEach
    void setUp() {
        defaultTenantId = defaultTenantProvider.getDefaultTenantId();
        tenantContext.set(defaultTenantId);

        // Create a DRAFT tournament with 4 teams, 2 courts
        Tournament tournament = new Tournament(
                UUID.randomUUID(), defaultTenantId,
                "IT Mapping Test", "BEST_OF_1",
                "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now(), null, 2, 4);
        tournamentRepository.save(tournament);
        tournamentId = tournament.getId();

        // Create 4 participating teams
        for (int i = 1; i <= 4; i++) {
            Team team = new Team(UUID.randomUUID(), defaultTenantId, tournamentId,
                    i, "Team " + i, true, false, false, LocalDateTime.now());
            teamRepository.save(team);
        }

        // Apply a 2-section draft:
        // Section 1 (Phase 1): 2 groups, team_number sort
        // Section 2 (Phase 2): 2 groups, placement_group sort
        DraftSection section1 = new DraftSection(1, "team_number", 2, "roundrobin", 2, 5, 15, 1);
        DraftSection section2 = new DraftSection(2, "placement_group", 2, "roundrobin", 2, 5, 15, 1);
        DraftConfig config = new DraftConfig(List.of(section1, section2));
        draftService.saveDraft(tournamentId, config);
        draftService.applyDraft(tournamentId);  // Creates Phase 1 + Phase 2 + Phase 1 TeamAvatars

        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        phases.sort((a, b) -> Integer.compare(a.getSequenceNumber(), b.getSequenceNumber()));
        phase1Id = phases.get(0).getId();
        phase2Id = phases.get(1).getId();
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }

    // =========================================================================
    // V10 migration — sort_type + group_count columns created
    // =========================================================================

    @Test
    void v10Migration_sortTypeAndGroupCountPersistedOnPhase() {
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        phases.sort((a, b) -> Integer.compare(a.getSequenceNumber(), b.getSequenceNumber()));

        Phase phase1 = phases.get(0);
        Phase phase2 = phases.get(1);

        // Phase 1: created with team_number sort, groupCount=2 (from DraftSection)
        assertThat(phase1.getSortType()).isEqualTo("team_number");
        assertThat(phase1.getGroupCount()).isEqualTo(2);

        // Phase 2: created with placement_group sort, groupCount=2 (from DraftSection)
        assertThat(phase2.getSortType()).isEqualTo("placement_group");
        assertThat(phase2.getGroupCount()).isEqualTo(2);
    }

    // =========================================================================
    // AC9 — previous phase not COMPLETED → 409
    // =========================================================================

    @Test
    void getMappingSuggestion_previousPhaseNotCompleted_throws409() {
        // Phase 1 is still PENDING — cannot generate suggestion for Phase 2
        assertThatThrownBy(() -> phaseMappingService.getMappingSuggestion(phase2Id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not COMPLETED");
    }

    // =========================================================================
    // AC1 — Phase 1 → throws IllegalStateException (no previous phase)
    // =========================================================================

    @Test
    void getMappingSuggestion_phase1_throwsIllegalState() {
        assertThatThrownBy(() -> phaseMappingService.getMappingSuggestion(phase1Id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Phase 1");
    }

    // =========================================================================
    // AC2 — applyMapping creates TeamAvatars for Phase 2
    // =========================================================================

    @Test
    void applyMapping_validAssignments_createsTeamAvatars() {
        // Phase 2 has no TeamAvatars yet (only Phase 1 gets them at apply time)
        List<TeamAvatar> existingPhase2Avatars = teamAvatarRepository.findByPhaseId(phase2Id);
        assertThat(existingPhase2Avatars).isEmpty();

        // Get Phase 1 TeamAvatars to know which teamIds to use
        List<TeamAvatar> phase1Avatars = teamAvatarRepository.findByPhaseId(phase1Id);
        assertThat(phase1Avatars).hasSize(4);  // 4 teams in 2 groups

        // Build valid assignments: map all 4 teams into 2 groups
        List<UUID> teamIds = phase1Avatars.stream().map(TeamAvatar::getTeamId).toList();
        List<MappingAssignment> assignments = List.of(
                new MappingAssignment(teamIds.get(0), 1, 1),
                new MappingAssignment(teamIds.get(1), 1, 2),
                new MappingAssignment(teamIds.get(2), 2, 1),
                new MappingAssignment(teamIds.get(3), 2, 2)
        );

        List<TeamAvatar> created = phaseMappingService.applyMapping(phase2Id, assignments);

        assertThat(created).hasSize(4);
        assertThat(created).allMatch(a -> a.getPhaseId().equals(phase2Id));
        assertThat(created).allMatch(a -> a.getTenantId().equals(defaultTenantId));
    }

    // =========================================================================
    // AC2 — 409 if TeamAvatars already exist
    // =========================================================================

    @Test
    void applyMapping_existingAvatars_throws409() {
        // First, apply a mapping
        List<TeamAvatar> phase1Avatars = teamAvatarRepository.findByPhaseId(phase1Id);
        List<UUID> teamIds = phase1Avatars.stream().map(TeamAvatar::getTeamId).toList();
        List<MappingAssignment> assignments = List.of(
                new MappingAssignment(teamIds.get(0), 1, 1),
                new MappingAssignment(teamIds.get(1), 1, 2),
                new MappingAssignment(teamIds.get(2), 2, 1),
                new MappingAssignment(teamIds.get(3), 2, 2)
        );
        phaseMappingService.applyMapping(phase2Id, assignments);

        // Second apply → should throw 409
        assertThatThrownBy(() -> phaseMappingService.applyMapping(phase2Id, assignments))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exist");
    }

    // =========================================================================
    // AC10 — redoMapping replaces existing TeamAvatars
    // =========================================================================

    @Test
    void redoMapping_replacesPreviousAssignment() {
        List<TeamAvatar> phase1Avatars = teamAvatarRepository.findByPhaseId(phase1Id);
        List<UUID> teamIds = phase1Avatars.stream().map(TeamAvatar::getTeamId).toList();

        // First apply
        List<MappingAssignment> firstAssignment = List.of(
                new MappingAssignment(teamIds.get(0), 1, 1),
                new MappingAssignment(teamIds.get(1), 1, 2),
                new MappingAssignment(teamIds.get(2), 2, 1),
                new MappingAssignment(teamIds.get(3), 2, 2)
        );
        phaseMappingService.applyMapping(phase2Id, firstAssignment);
        assertThat(teamAvatarRepository.findByPhaseId(phase2Id)).hasSize(4);

        // Re-do with swapped positions
        List<MappingAssignment> redoAssignment = List.of(
                new MappingAssignment(teamIds.get(2), 1, 1),
                new MappingAssignment(teamIds.get(3), 1, 2),
                new MappingAssignment(teamIds.get(0), 2, 1),
                new MappingAssignment(teamIds.get(1), 2, 2)
        );
        List<TeamAvatar> redone = phaseMappingService.redoMapping(phase2Id, redoAssignment);

        assertThat(redone).hasSize(4);
        // After re-do, exactly 4 avatars should exist (old ones deleted, new ones created)
        assertThat(teamAvatarRepository.findByPhaseId(phase2Id)).hasSize(4);
        // The new group 1 position 1 should have teamIds.get(2)
        assertThat(redone.stream()
                .filter(a -> a.getGroupNumber() == 1 && a.getGroupPosition() == 1)
                .findFirst()
                .map(TeamAvatar::getTeamId))
                .contains(teamIds.get(2));
    }

    // =========================================================================
    // hasExistingMapping — used by UI for AC10 guard
    // =========================================================================

    @Test
    void hasExistingMapping_noAvatars_returnsFalse() {
        assertThat(phaseMappingService.hasExistingMapping(phase2Id)).isFalse();
    }

    @Test
    void hasExistingMapping_afterApply_returnsTrue() {
        List<TeamAvatar> phase1Avatars = teamAvatarRepository.findByPhaseId(phase1Id);
        List<UUID> teamIds = phase1Avatars.stream().map(TeamAvatar::getTeamId).toList();
        phaseMappingService.applyMapping(phase2Id, List.of(
                new MappingAssignment(teamIds.get(0), 1, 1),
                new MappingAssignment(teamIds.get(1), 1, 2),
                new MappingAssignment(teamIds.get(2), 2, 1),
                new MappingAssignment(teamIds.get(3), 2, 2)
        ));

        assertThat(phaseMappingService.hasExistingMapping(phase2Id)).isTrue();
    }
}
