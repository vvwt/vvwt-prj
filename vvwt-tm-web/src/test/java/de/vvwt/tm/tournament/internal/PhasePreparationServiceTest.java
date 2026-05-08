package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchGenerator;
import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PhasePreparationService#generateMatches} (E21S08 / E51S06).
 *
 * <p>E51S06 refactor: {@code preparePhase()} removed (dead code per DEC-55 D-10). This test file
 * now covers only {@link PhasePreparationService#generateMatches}, which is retained for the E51S03
 * match-gen background job.
 *
 * <p>The {@code RefereeAssigner} dependency was also removed from the constructor (E51S06).
 *
 * @see PhasePreparationService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S08">E21S08 — inventory row 180</a>
 * @see <a href="E51S06">E51S06 — preparePhase dead-code removal + constructor update</a>
 */
class PhasePreparationServiceTest {

    private PhaseRepository phaseRepository;
    private MatchRepository matchRepository;
    private TeamAvatarRepository teamAvatarRepository;
    private MatchGeneratorRegistry matchGeneratorRegistry;
    private PhasePreparationService service;

    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        phaseRepository = mock(PhaseRepository.class);
        matchRepository = mock(MatchRepository.class);
        teamAvatarRepository = mock(TeamAvatarRepository.class);
        matchGeneratorRegistry = mock(MatchGeneratorRegistry.class);

        // E51S06: 4-arg constructor (refereeAssigner removed)
        service =
                new PhasePreparationService(
                        phaseRepository,
                        matchRepository,
                        teamAvatarRepository,
                        matchGeneratorRegistry);

        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
    }

    // -------------------------------------------------------------------------
    // generateMatches — null phaseId → IAE
    // -------------------------------------------------------------------------

    @Test
    void generateMatches_nullPhaseId_throwsIAE() {
        assertThatThrownBy(() -> service.generateMatches(null, "roundRobinNew"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // generateMatches — null generatorKey → IAE
    // -------------------------------------------------------------------------

    @Test
    void generateMatches_nullGeneratorKey_throwsIAE() {
        assertThatThrownBy(() -> service.generateMatches(phaseId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // generateMatches — phase not found → IAE
    // -------------------------------------------------------------------------

    @Test
    void generateMatches_phaseNotFound_throwsIAE() {
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateMatches(phaseId, "roundRobinNew"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(phaseId.toString());
    }

    // -------------------------------------------------------------------------
    // generateMatches — orchestration: resolves generator, generates, persists
    // -------------------------------------------------------------------------

    @Test
    void generateMatches_orchestration_fullFlow() {
        Phase phase = buildPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        List<TeamAvatar> avatars =
                List.of(buildAvatar(UUID.randomUUID()), buildAvatar(UUID.randomUUID()));
        when(teamAvatarRepository.findByTournamentIdAndPhaseId(any(UUID.class), eq(phaseId)))
                .thenReturn(avatars);

        MatchGenerator generator = mock(MatchGenerator.class);
        when(generator.getBeanId()).thenReturn("roundRobinNew");
        when(matchGeneratorRegistry.get("roundRobinNew")).thenReturn(generator);

        Match generatedMatch = buildMatch();
        when(generator.generate(eq(phase), eq(avatars))).thenReturn(List.of(generatedMatch));
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generateMatches(phaseId, "roundRobinNew");

        verify(matchGeneratorRegistry).get("roundRobinNew");
        verify(generator).generate(phase, avatars);
        verify(matchRepository).save(generatedMatch);
    }

    // -------------------------------------------------------------------------
    // generateMatches — idempotent: existing matches cleared before re-generation
    // -------------------------------------------------------------------------

    @Test
    void generateMatches_existingMatchesCleared() {
        Phase phase = buildPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        Match existingMatch = buildMatch();
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(existingMatch));

        List<TeamAvatar> avatars = List.of(buildAvatar(UUID.randomUUID()));
        when(teamAvatarRepository.findByTournamentIdAndPhaseId(any(UUID.class), eq(phaseId)))
                .thenReturn(avatars);

        MatchGenerator generator = mock(MatchGenerator.class);
        when(generator.getBeanId()).thenReturn("roundRobinNew");
        when(matchGeneratorRegistry.get("roundRobinNew")).thenReturn(generator);
        when(generator.generate(any(), any())).thenReturn(List.of());

        service.generateMatches(phaseId, "roundRobinNew");

        verify(matchRepository).deleteByPhaseId(phaseId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Phase buildPhase() {
        Phase p = new Phase();
        p.setId(phaseId);
        p.setTournamentId(tournamentId);
        p.setStatus("PENDING");
        return p;
    }

    private Match buildMatch() {
        Match m = new Match();
        m.setId(UUID.randomUUID());
        m.setTournamentId(tournamentId);
        m.setPhaseId(phaseId);
        m.setMemberAvatar1Id(UUID.randomUUID());
        m.setMemberAvatar2Id(UUID.randomUUID());
        m.setState(MatchState.OPEN.getLegacyCode());
        m.setSetLimit(MatchFormat.BEST_OF_3.getMaxSets());
        return m;
    }

    private TeamAvatar buildAvatar(UUID id) {
        TeamAvatar a = new TeamAvatar();
        a.setId(id);
        a.setPhaseId(phaseId);
        a.setTeamId(UUID.randomUUID());
        return a;
    }
}
