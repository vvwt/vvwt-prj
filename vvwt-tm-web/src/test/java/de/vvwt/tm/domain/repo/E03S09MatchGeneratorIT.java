package de.vvwt.tm.domain.repo;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.generator.MatchGenerator;
import de.vvwt.tm.domain.generator.MatchGeneratorRegistry;
import de.vvwt.tm.domain.generator.RoundRobinMatchGenerator;
import de.vvwt.tm.domain.rules.TournamentRuleResolver;
import de.vvwt.tm.tenant.DefaultTenantProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Integration test for E03S09 — RoundRobinMatchGenerator end-to-end (AC11, AC6, AC7, AC14).
 *
 * <p>Lives in {@code de.vvwt.tm.domain.repo} to access the package-private
 * {@link TenantContext#set(UUID)} and {@link TenantContext#clear()} methods
 * (same pattern as {@link E03S05RepositoryIT}).
 *
 * <p>Creates Tournament, Phase, 9 Teams, and 9 TeamAvatars in the H2 in-memory DB.
 * Invokes the generator via the registry; persists results via MatchRepository.
 * Asserts 36 Match rows with the correct field values.
 *
 * @see RoundRobinMatchGenerator
 * @see MatchGeneratorRegistry
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S09.story.md">Story E03S09</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e03s09db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@DisplayName("RoundRobinMatchGenerator integration tests (E03S09)")
class E03S09MatchGeneratorIT {

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private DefaultTenantProvider defaultTenantProvider;

    @Autowired
    private TournamentRepository tournamentRepository;

    @Autowired
    private PhaseRepository phaseRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamAvatarRepository teamAvatarRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private MatchGeneratorRegistry matchGeneratorRegistry;

    @Autowired
    private TournamentRuleResolver ruleResolver;

    private UUID defaultTenantId;

    @BeforeEach
    void setUp() {
        defaultTenantId = defaultTenantProvider.getDefaultTenantId();
        tenantContext.set(defaultTenantId);
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }

    // =========================================================================
    // AC11 — end-to-end: 9 avatars → 36 persisted matches
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("AC11: generate + persist 36 matches for N=9 avatars; assert all field values")
    void endToEndN9() {
        LocalDateTime now = LocalDateTime.now();

        // 1. Create Tournament (BEST_OF_3)
        UUID tournamentId = UUID.randomUUID();
        Tournament tournament = new Tournament(
                tournamentId, defaultTenantId, "E03S09 IT Tournament",
                "BEST_OF_3", "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", now);
        tournamentRepository.save(tournament);

        // 2. Create Phase
        UUID phaseId = UUID.randomUUID();
        Phase phase = new Phase(phaseId, defaultTenantId, tournamentId,
                1, "Vorrunde", "PENDING", 0, now);
        phaseRepository.save(phase);

        // 3. Create 9 Teams
        List<UUID> teamIds = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            UUID teamId = UUID.randomUUID();
            teamIds.add(teamId);
            Team team = new Team(teamId, defaultTenantId, tournamentId, i,
                    "Team " + i, true, false, false, now);
            teamRepository.save(team);
        }

        // 4. Create 9 TeamAvatars (group_number=1, positions 1..9)
        List<TeamAvatar> avatars = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            UUID avatarId = UUID.randomUUID();
            TeamAvatar avatar = new TeamAvatar(
                    avatarId, defaultTenantId, tournamentId, phaseId,
                    1, i + 1, teamIds.get(i), "Group 1 Pos " + (i + 1), now);
            teamAvatarRepository.save(avatar);
            avatars.add(avatar);
        }

        // 5. Invoke generator via registry
        MatchGenerator generator = matchGeneratorRegistry.get("roundRobin");
        List<Match> generated = generator.generate(phase, avatars);
        assertThat(generated).hasSize(36);

        // 6. Persist matches
        for (Match match : generated) {
            matchRepository.save(match);
        }

        // 7. Query and assert
        List<Match> persisted = matchRepository.findByPhaseId(phaseId);
        assertThat(persisted).hasSize(36);

        for (Match match : persisted) {
            assertThat(match.getTenantId()).isEqualTo(defaultTenantId);
            assertThat(match.getTournamentId()).isEqualTo(tournamentId);
            assertThat(match.getPhaseId()).isEqualTo(phaseId);
            assertThat(match.getState()).isEqualTo(0);      // OPEN
            assertThat(match.getSetLimit()).isEqualTo(3);   // BEST_OF_3 maxSets=3
            assertThat(match.getLapNumber()).isNull();
            assertThat(match.getFieldNumber()).isNull();
        }

        // Verify: no phantom UUIDs in persisted rows
        UUID phantom = new UUID(0L, 0L);
        for (Match match : persisted) {
            assertThat(match.getMemberAvatar1Id()).isNotEqualTo(phantom);
            assertThat(match.getMemberAvatar2Id()).isNotEqualTo(phantom);
        }
    }

    // =========================================================================
    // AC11 — error: fewer than 2 avatars → 0 matches generated
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("AC11 / AC12: 1 avatar → 0 matches generated and persisted")
    void oneAvatarProducesNoMatches() {
        LocalDateTime now2 = LocalDateTime.now();
        UUID tournamentId = UUID.randomUUID();
        Tournament tournament = new Tournament(
                tournamentId, defaultTenantId, "E03S09 1-avatar Tournament",
                "BEST_OF_1", "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", now2);
        tournamentRepository.save(tournament);

        UUID phaseId = UUID.randomUUID();
        Phase phase = new Phase(phaseId, defaultTenantId, tournamentId,
                1, "Vorrunde", "PENDING", 0, now2);
        phaseRepository.save(phase);

        UUID teamId = UUID.randomUUID();
        teamRepository.save(new Team(teamId, defaultTenantId, tournamentId, 1, "Team 1",
                true, false, false, now2));

        UUID avatarId = UUID.randomUUID();
        TeamAvatar avatar = new TeamAvatar(avatarId, defaultTenantId, tournamentId, phaseId,
                1, 1, teamId, "Slot 1", now2);
        teamAvatarRepository.save(avatar);

        MatchGenerator generator = matchGeneratorRegistry.get("roundRobin");
        List<Match> generated = generator.generate(phase, List.of(avatar));
        assertThat(generated).isEmpty();
    }

    // =========================================================================
    // AC14 — unknown generator id fails at resolve time
    // =========================================================================

    @Test
    @DisplayName("AC14: unknown matchGeneratorId → IllegalArgumentException naming the id and known ids")
    void unknownGeneratorIdFails() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> matchGeneratorRegistry.get("unknownGenerator"))
                .withMessageContaining("unknownGenerator")
                .withMessageContaining("roundRobin");
    }

    // =========================================================================
    // AC6 — registry bean is wired and contains 'roundRobin'
    // =========================================================================

    @Test
    @DisplayName("AC6: MatchGeneratorRegistry contains 'roundRobin'")
    void registryContainsRoundRobin() {
        assertThat(matchGeneratorRegistry.knownIds()).contains("roundRobin");
    }

    // =========================================================================
    // AC7 — TournamentRuleResolver.resolveMatchGenerator works
    // =========================================================================

    @Test
    @DisplayName("AC7: TournamentRuleResolver.resolveMatchGenerator resolves 'roundRobin'")
    void ruleResolverResolvesMatchGenerator() {
        Tournament t = new Tournament();
        t.setMatchGeneratorId("roundRobin");
        MatchGenerator gen = ruleResolver.resolveMatchGenerator(t);
        assertThat(gen).isInstanceOf(RoundRobinMatchGenerator.class);
        assertThat(gen.getBeanId()).isEqualTo("roundRobin");
    }

    @Test
    @DisplayName("AC7: TournamentRuleResolver.resolveMatchGenerator with unknown id fails")
    void ruleResolverUnknownIdFails() {
        Tournament t = new Tournament();
        t.setMatchGeneratorId("swiss");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ruleResolver.resolveMatchGenerator(t))
                .withMessageContaining("swiss");
    }

    @Test
    @DisplayName("AC7: TournamentRuleResolver.resolveMatchGenerator with null tournament fails")
    void ruleResolverNullTournamentFails() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ruleResolver.resolveMatchGenerator(null))
                .withMessageContaining("tournament");
    }
}
