package de.vvwt.tm.domain.repo;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.domain.DraftService;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.draft.DraftConfig;
import de.vvwt.tm.domain.draft.DraftPreviewResult;
import de.vvwt.tm.domain.draft.DraftPreviewSection;
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
 * Integration tests for E05S06 — Draft configuration, preview, and apply.
 *
 * <p>Uses the full Spring context with an in-memory H2 database and all Flyway migrations applied.
 * Tests verify end-to-end behavior of:
 * <ul>
 *   <li>AC2 — saveDraft persists JSON to tournament table</li>
 *   <li>AC3 — getDraft returns empty config when no draft is saved</li>
 *   <li>AC4 — previewDraft calculates correct values</li>
 *   <li>AC5 — applyDraft creates Phase entities</li>
 *   <li>AC6 — Phase 1 TeamAvatars distributed correctly</li>
 *   <li>AC7 — tournament status transitions to PLANNED after apply</li>
 *   <li>AC12 — re-apply rejected when phases already exist</li>
 * </ul>
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story E05S06</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e05s06db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Transactional
class E05S06DraftIT {

    @Autowired private DraftService draftService;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamAvatarRepository teamAvatarRepository;
    @Autowired private TenantContext tenantContext;
    @Autowired private DefaultTenantProvider defaultTenantProvider;

    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        UUID defaultTenantId = defaultTenantProvider.getDefaultTenantId();
        tenantContext.set(defaultTenantId);

        // Create a DRAFT tournament
        Tournament tournament = new Tournament(
                UUID.randomUUID(), defaultTenantId,
                "IT Test Tournament", "BEST_OF_3",
                "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now(), null, 4, 8);
        tournamentRepository.save(tournament);
        tournamentId = tournament.getId();

        // Create 8 participating teams
        for (int i = 1; i <= 8; i++) {
            Team team = new Team(UUID.randomUUID(), defaultTenantId, tournamentId,
                    i, "Team " + i, true, false, false, LocalDateTime.now());
            teamRepository.save(team);
        }
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }

    // Helper to expose package-private clear for this test class
    private void clearTenantContext() {
        tenantContext.clear();
    }

    // =========================================================================
    // AC3 — getDraft: empty when no draft configured
    // =========================================================================

    @Test
    void getDraftReturnsEmptyConfigWhenNoDraftSaved() {
        DraftConfig config = draftService.getDraft(tournamentId);

        assertThat(config.getSections()).isEmpty();
    }

    // =========================================================================
    // AC2, AC3 — saveDraft / getDraft round-trip
    // =========================================================================

    @Test
    void saveDraftThenGetDraftReturnsIdenticalConfig() {
        DraftConfig config = makeDraftConfig(2);

        draftService.saveDraft(tournamentId, config);
        DraftConfig retrieved = draftService.getDraft(tournamentId);

        assertThat(retrieved.getSections()).hasSize(2);
        assertThat(retrieved.getSections().get(0).getGroupCount()).isEqualTo(2);
        assertThat(retrieved.getSections().get(1).getGroupCount()).isEqualTo(2);
        assertThat(retrieved.getSections().get(0).getLapTimeMinutes()).isEqualTo(15);
    }

    // =========================================================================
    // AC4 — previewDraft
    // =========================================================================

    @Test
    void previewDraftReturnsCorrectValuesFor8TeamsIn2Groups() {
        DraftConfig config = makeDraftConfig(1);
        draftService.saveDraft(tournamentId, config);

        DraftPreviewResult result = draftService.previewDraft(tournamentId);

        assertThat(result.sections()).hasSize(1);
        DraftPreviewSection preview = result.sections().get(0);
        assertThat(preview.getGroupCount()).isEqualTo(2);
        assertThat(preview.getTeamsPerGroup()).isEqualTo(4);   // 8/2 = 4
        assertThat(preview.getMatchesPerGroup()).isEqualTo(6); // 4*(4-1)/2 = 6
        assertThat(preview.getTotalLaps()).isEqualTo(3);       // 4-1 = 3
        assertThat(preview.getTotalMatches()).isEqualTo(12);   // 2*6 = 12
        // no plannedStartTime → timeline is empty (AC3 — E08S05)
        assertThat(result.timeline()).isEmpty();
    }

    // =========================================================================
    // AC5, AC6, AC7 — applyDraft
    // =========================================================================

    @Test
    void applyDraftCreatesOnePhasePerSection() {
        DraftConfig config = makeDraftConfig(2); // 2 sections → 2 phases
        draftService.saveDraft(tournamentId, config);

        List<UUID> phaseIds = draftService.applyDraft(tournamentId);

        assertThat(phaseIds).hasSize(2);
        assertThat(phaseRepository.findByTournamentId(tournamentId)).hasSize(2);
    }

    @Test
    void applyDraftCreates8TeamAvatarsForPhase1() {
        DraftConfig config = makeDraftConfig(1);
        draftService.saveDraft(tournamentId, config);

        List<UUID> phaseIds = draftService.applyDraft(tournamentId);

        UUID phase1Id = phaseIds.get(0);
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phase1Id);
        assertThat(avatars).hasSize(8); // one per participating team
    }

    @Test
    void applyDraftDistributesTeamsIntoCorrectGroups() {
        DraftConfig config = makeDraftConfig(1); // groupCount=2
        draftService.saveDraft(tournamentId, config);

        List<UUID> phaseIds = draftService.applyDraft(tournamentId);

        UUID phase1Id = phaseIds.get(0);
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phase1Id);

        long group1Count = avatars.stream().filter(a -> a.getGroupNumber() == 1).count();
        long group2Count = avatars.stream().filter(a -> a.getGroupNumber() == 2).count();
        assertThat(group1Count).isEqualTo(4); // 8 teams / 2 groups = 4 each
        assertThat(group2Count).isEqualTo(4);
    }

    @Test
    void applyDraftTransitionsTournamentToPlanned() {
        DraftConfig config = makeDraftConfig(1);
        draftService.saveDraft(tournamentId, config);

        draftService.applyDraft(tournamentId);

        Tournament updated = tournamentRepository.findById(tournamentId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("PLANNED");
        assertThat(updated.getDraftJson()).isNull();
    }

    // =========================================================================
    // AC12 — re-apply prevention
    // =========================================================================

    @Test
    void applyDraftTwiceThrowsConflictException() {
        DraftConfig config = makeDraftConfig(1);
        draftService.saveDraft(tournamentId, config);
        draftService.applyDraft(tournamentId);

        // Tournament is now PLANNED; re-apply should fail on DRAFT status check
        assertThatThrownBy(() -> draftService.applyDraft(tournamentId))
                .isInstanceOf(ConflictException.class);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a draft config with {@code sectionCount} sections, each with:
     * groupCount=2, lapTime=15min, lapBreak=5min, sectionBreak=10min, setQuantity=1.
     */
    private DraftConfig makeDraftConfig(int sectionCount) {
        List<DraftSection> sections = java.util.stream.IntStream.rangeClosed(1, sectionCount)
                .mapToObj(n -> new DraftSection(n, "team_number", 2, "roundrobin", 5, 10, 15, 1, null))
                .toList();
        return new DraftConfig(sections);
    }
}
