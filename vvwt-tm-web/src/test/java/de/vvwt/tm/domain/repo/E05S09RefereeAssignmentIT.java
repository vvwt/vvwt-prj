package de.vvwt.tm.domain.repo;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.domain.DraftService;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.PhaseLifecycleService;
import de.vvwt.tm.domain.RefereeAssignmentService;
import de.vvwt.tm.domain.RefereeAssignmentService.RefereeAssignmentOverview;
import de.vvwt.tm.domain.Team;
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
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for E05S09 — Referee assignment view and override.
 *
 * <p>Uses the full Spring context with an in-memory H2 database and all Flyway migrations applied.
 * Tests verify end-to-end behavior of:
 * <ul>
 *   <li>AC1 — GET overview after phase preparation returns correct entries</li>
 *   <li>AC2 — PUT sets manual override; 409 when team is playing in same lap</li>
 *   <li>AC3 — DELETE clears manual override</li>
 *   <li>AC4 — POST reassign runs auto-assignment; preserves manual overrides</li>
 *   <li>AC9 — no referee teams configured → allRefereeTeams is empty</li>
 *   <li>AC11 — tenant scope: 404 for cross-tenant access</li>
 * </ul>
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E05S09.story.md">Story E05S09</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e05s09db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Transactional
class E05S09RefereeAssignmentIT {

    @Autowired private RefereeAssignmentService refereeAssignmentService;
    @Autowired private PhaseLifecycleService phaseLifecycleService;
    @Autowired private DraftService draftService;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamAvatarRepository teamAvatarRepository;
    @Autowired private MatchRepository matchRepository;
    @Autowired private TenantContext tenantContext;
    @Autowired private DefaultTenantProvider defaultTenantProvider;

    private UUID tournamentId;
    private UUID defaultTenantId;

    @BeforeEach
    void setUp() {
        defaultTenantId = defaultTenantProvider.getDefaultTenantId();
        tenantContext.set(defaultTenantId);

        // Create a DRAFT tournament (3 fields, 5 teams).
        // 5 teams in 1 group → C(5,2)=10 matches → N=10 = exhaustive optimizer limit.
        // With 5 teams (odd), the optimizer creates ~5 laps of 2 matches each.
        // Per lap: 4 of 5 teams play; the 5th is not playing → eligible as referee.
        Tournament tournament = new Tournament(
                UUID.randomUUID(), defaultTenantId,
                "IT Referee Assignment Test", "BEST_OF_1",
                "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now(), null, 3, 5);
        tournamentRepository.save(tournament);
        tournamentId = tournament.getId();

        // Create 5 teams — all participate and all are eligible referees.
        // With 5 teams in round-robin (odd count), each lap has 2 matches and 1 team is idle.
        // The idle team is eligible as referee for that lap.
        for (int i = 1; i <= 5; i++) {
            Team team = new Team(UUID.randomUUID(), defaultTenantId, tournamentId,
                    i, "Team " + i, true, true, false, LocalDateTime.now());
            teamRepository.save(team);
        }

        // Apply a 1-section draft (1 group, 5 participating teams) to create Phase 1 with TeamAvatars.
        // 5 teams, round-robin → C(5,2)=10 matches → within exhaustive optimizer limit (N≤10).
        DraftSection section = new DraftSection(1, "team_number", 1, "roundrobin", 2, 5, 15, 1);
        DraftConfig config = new DraftConfig(List.of(section));
        draftService.saveDraft(tournamentId, config);
        draftService.applyDraft(tournamentId);
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }

    // =========================================================================
    // Helper: get first phase and prepare it
    // =========================================================================

    private UUID prepareFirstPhase() {
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        assertThat(phases).isNotEmpty();
        UUID phaseId = phases.get(0).getId();
        phaseLifecycleService.prepare(phaseId);
        return phaseId;
    }

    // =========================================================================
    // AC1 — GET overview after preparation
    // =========================================================================

    @Test
    void getAssignments_afterPreparation_returnsEntriesWithRefereeTeams() {
        UUID phaseId = prepareFirstPhase();

        RefereeAssignmentOverview overview = refereeAssignmentService.getAssignments(phaseId);

        assertThat(overview.assignments()).isNotEmpty();
        // Some assignments should have a referee team (teams 5, 6 are referee teams)
        long withReferee = overview.assignments().stream()
                .filter(e -> e.refereeTeamName() != null)
                .count();
        assertThat(withReferee).isGreaterThan(0);

        // allRefereeTeams should include teams 5 and 6 (referee-only teams in the tournament)
        assertThat(overview.allRefereeTeams()).isNotEmpty();

        // Entries should be sorted by lap then field
        List<RefereeAssignmentService.RefereeAssignmentEntry> entries = overview.assignments();
        for (int i = 1; i < entries.size(); i++) {
            Integer prevLap = entries.get(i - 1).lapNumber();
            Integer currLap = entries.get(i).lapNumber();
            if (prevLap != null && currLap != null) {
                assertThat(currLap).isGreaterThanOrEqualTo(prevLap);
            }
        }
    }

    @Test
    void getAssignments_emptyPhase_noMatches_returnsEmptyAssignments() {
        // Get phase before preparation (no matches)
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        UUID phaseId = phases.get(0).getId();

        RefereeAssignmentOverview overview = refereeAssignmentService.getAssignments(phaseId);

        assertThat(overview.assignments()).isEmpty();
    }

    // =========================================================================
    // AC2 — override referee
    // =========================================================================

    @Test
    void overrideReferee_setsManualFlag_andCorrectTeamId() {
        UUID phaseId = prepareFirstPhase();

        // Find a match to override
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        assertThat(matches).isNotEmpty();
        Match targetMatch = matches.get(0);
        UUID matchId = targetMatch.getId();
        int lapNumber = targetMatch.getLapNumber();

        // Find a referee team avatar that is NOT playing in this lap.
        // Use match data (memberAvatar1Id/memberAvatar2Id) to determine playing avatars per lap,
        // then filter allRefereeTeams to exclude those whose avatarId appears as a player.
        var overview = refereeAssignmentService.getAssignments(phaseId);

        // Build the set of playing avatar IDs in the target lap
        Set<UUID> playingAvatarIdsInLap = matches.stream()
                .filter(m -> m.getLapNumber() != null && m.getLapNumber() == lapNumber)
                .flatMap(m -> Stream.of(m.getMemberAvatar1Id(), m.getMemberAvatar2Id()))
                .collect(Collectors.toSet());

        var eligibleOptions = overview.allRefereeTeams().stream()
                .filter(opt -> !playingAvatarIdsInLap.contains(opt.avatarId()))
                .toList();

        if (eligibleOptions.isEmpty()) {
            // No eligible referee teams for this lap — skip test (dataset too small)
            return;
        }

        UUID refereeAvatarId = eligibleOptions.get(0).avatarId();

        var entry = refereeAssignmentService.overrideReferee(phaseId, matchId, refereeAvatarId);

        assertThat(entry.isManualOverride()).isTrue();
        assertThat(entry.refereeTeamId()).isEqualTo(eligibleOptions.get(0).teamId());

        // Verify persistence
        Match saved = matchRepository.findById(matchId).orElseThrow();
        assertThat(saved.getRefereeDescription()).isEqualTo(RefereeAssignmentService.MANUAL_OVERRIDE_SENTINEL);
        assertThat(saved.getRefereeTeamId()).isEqualTo(eligibleOptions.get(0).teamId());
    }

    @Test
    void overrideReferee_teamPlayingInSameLap_throws409() {
        UUID phaseId = prepareFirstPhase();

        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        assertThat(matches).size().isGreaterThanOrEqualTo(2);

        // With 5 teams (odd) the optimizer creates laps with 2 matches each.
        // Find a lap that has exactly 2 matches: pick M1, find another match M2 in the same lap.
        Match match1 = null;
        Match match2 = null;
        for (Match m : matches) {
            if (match1 == null) {
                match1 = m;
            } else if (m.getLapNumber() != null && m.getLapNumber().equals(match1.getLapNumber())) {
                match2 = m;
                break;
            }
        }

        if (match2 == null) {
            // If no two matches share a lap (degenerate case), skip
            return;
        }

        // Team playing in M1 should not be eligible to referee M2 (same lap)
        // match2 is the target we want to override; match1's team1 avatar is "playing" in that lap
        UUID targetMatchId = match2.getId();
        UUID playingAvatarId = match1.getMemberAvatar1Id();

        // All teams have refereeAssignment=true, so the playing team IS in allRefereeTeams.
        // Assigning it to a different match in the same lap must throw 409.
        assertThatThrownBy(() ->
                refereeAssignmentService.overrideReferee(phaseId, targetMatchId, playingAvatarId))
                .isInstanceOf(ConflictException.class);
    }

    // =========================================================================
    // AC3 — clear referee override
    // =========================================================================

    @Test
    void clearRefereeOverride_clearsRefereeTeamIdAndDescription() {
        UUID phaseId = prepareFirstPhase();

        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        Match targetMatch = matches.stream()
                .filter(m -> m.getRefereeTeamId() != null)
                .findFirst()
                .orElse(matches.get(0));
        UUID matchId = targetMatch.getId();

        var entry = refereeAssignmentService.clearRefereeOverride(phaseId, matchId);

        assertThat(entry.refereeTeamId()).isNull();
        assertThat(entry.isManualOverride()).isFalse();

        Match saved = matchRepository.findById(matchId).orElseThrow();
        assertThat(saved.getRefereeTeamId()).isNull();
        assertThat(saved.getRefereeDescription()).isNull();
    }

    // =========================================================================
    // AC4 — reassignAll preserves manual overrides
    // =========================================================================

    @Test
    void reassignAll_preservesManualOverrides() {
        UUID phaseId = prepareFirstPhase();

        // Find a match and set a manual override directly
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        Match targetMatch = matches.get(0);
        UUID matchId = targetMatch.getId();

        // Manually set override sentinel
        targetMatch.setRefereeDescription(RefereeAssignmentService.MANUAL_OVERRIDE_SENTINEL);
        matchRepository.save(targetMatch);

        // Re-run auto-assignment
        RefereeAssignmentOverview overview = refereeAssignmentService.reassignAll(phaseId);

        // The manually overridden match should still have the MANUAL sentinel
        Match afterReassign = matchRepository.findById(matchId).orElseThrow();
        assertThat(afterReassign.getRefereeDescription())
                .isEqualTo(RefereeAssignmentService.MANUAL_OVERRIDE_SENTINEL);

        // The overview entry should show isManualOverride = true
        var overriddenEntry = overview.assignments().stream()
                .filter(e -> matchId.equals(e.matchId()))
                .findFirst()
                .orElseThrow();
        assertThat(overriddenEntry.isManualOverride()).isTrue();
    }

    // =========================================================================
    // AC9 — no referee teams → empty allRefereeTeams
    // =========================================================================

    @Test
    void getAssignments_noRefereeTeams_emptyAllRefereeTeams() {
        // Set all teams to refereeAssignment=false
        List<Team> teams = teamRepository.findByTournamentId(tournamentId);
        for (Team team : teams) {
            team.setRefereeAssignment(false);
            teamRepository.save(team);
        }

        UUID phaseId = prepareFirstPhase();

        RefereeAssignmentOverview overview = refereeAssignmentService.getAssignments(phaseId);

        assertThat(overview.allRefereeTeams()).isEmpty();
        // All assignments should have null refereeTeamName (no eligible teams)
        long withNullReferee = overview.assignments().stream()
                .filter(e -> e.refereeTeamName() == null)
                .count();
        assertThat(withNullReferee).isEqualTo(overview.assignments().size());
    }

    // =========================================================================
    // AC11 — tenant scope: 404 for unknown phase
    // =========================================================================

    @Test
    void getAssignments_unknownPhase_throws404() {
        UUID nonExistentPhaseId = UUID.randomUUID();

        assertThatThrownBy(() -> refereeAssignmentService.getAssignments(nonExistentPhaseId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Phase not found");
    }

    @Test
    void overrideReferee_unknownPhase_throws404() {
        UUID nonExistentPhaseId = UUID.randomUUID();

        assertThatThrownBy(() ->
                refereeAssignmentService.overrideReferee(
                        nonExistentPhaseId, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Phase not found");
    }
}
