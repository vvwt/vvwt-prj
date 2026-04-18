package de.vvwt.tm.domain.repo;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.domain.AuditLogEntry;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchOutcome;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.RoundSnapshot;
import de.vvwt.tm.domain.SetResult;
import de.vvwt.tm.domain.SetState;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.TeamAvatarRating;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.repo.AuditLogRepository;
import de.vvwt.tm.domain.repo.MatchOutcomeRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.RoundSnapshotRepository;
import de.vvwt.tm.domain.repo.SetResultRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRatingRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.tenant.TenantRegistryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for E03S05 — Tenant-scoped repository base layer with runtime guard.
 *
 * <p>Uses the "test" profile: in-memory H2 with all Flyway migrations applied.
 * Tests interact with the full Spring context to verify that:
 * <ul>
 *   <li>AC1 — TenantContext bean exists and throws when no tenant is set</li>
 *   <li>AC3 — Tenant spoof rejection: saving with wrong tenantId is rejected</li>
 *   <li>AC4 — All 10 concrete repositories exist and are injectable</li>
 *   <li>AC5 — AuditLogRepository: append-only; deleteById throws</li>
 *   <li>AC6 — Guard fires before SQL when TenantContext is not set</li>
 *   <li>AC7 — Tenant spoof rejected on save</li>
 *   <li>AC8 — Normal CRUD: create, read, update, delete round-trip</li>
 *   <li>AC9 — DefaultTenantContextResolver resolves to default tenant (bean exists)</li>
 *   <li>AC10 — Missing context produces clear exception</li>
 *   <li>AC11 — Concurrent threads do not leak context</li>
 * </ul>
 *
 * <p><strong>Note on @Transactional:</strong> Most tests are marked @Transactional so that
 * DB mutations are rolled back after each test. The concurrent-thread test (AC11) is NOT
 * @Transactional — it manages its own thread pool and cannot use the test transaction
 * (which is pinned to the main test thread). That test uses direct repo calls with explicit
 * TenantContext setup/teardown.
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story E03S05</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Use a dedicated H2 in-memory database with CASE_INSENSITIVE_IDENTIFIERS=TRUE.
        // This is required because Spring Data JDBC 3.x generates quoted SQL identifiers
        // (e.g. "tournament", "TENANT_ID") which H2's default DATABASE_TO_UPPER=TRUE mode
        // cannot match against lowercase-quoted names. CASE_INSENSITIVE_IDENTIFIERS makes
        // H2 treat all identifiers as equal regardless of quoting case.
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e03s05db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class E03S05RepositoryIT {

    // -------------------------------------------------------------------------
    // Injected beans
    // -------------------------------------------------------------------------

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private TenantRegistryPort tenantRegistryPort;

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
    private SetResultRepository setResultRepository;

    @Autowired
    private MatchOutcomeRepository matchOutcomeRepository;

    @Autowired
    private TeamAvatarRatingRepository teamAvatarRatingRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private RoundSnapshotRepository roundSnapshotRepository;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** UUID used as the default tenant in all tests (provided by DefaultTenantBootstrap). */
    private UUID defaultTenantId;

    @BeforeEach
    void setUpTenantContext() {
        defaultTenantId = tenantRegistryPort.findAll().get(0).tenantId();
        tenantContext.set(defaultTenantId);
    }

    @AfterEach
    void clearTenantContext() {
        tenantContext.clear();
    }

    // =========================================================================
    // AC1 — TenantContext bean exists and throws when no tenant is set
    // =========================================================================

    @Test
    void tenantContextBeanExists() {
        assertThat(tenantContext).isNotNull();
    }

    @Test
    void tenantContextThrowsWhenNotSet() {
        tenantContext.clear();  // ensure no tenant active
        assertThatThrownBy(() -> tenantContext.getTenantId())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active TenantContext");
    }

    // =========================================================================
    // AC4 — All 10 concrete repositories exist and are injectable
    // =========================================================================

    @Test
    void allRepositoriesAreInjectable() {
        assertThat(tournamentRepository).isNotNull();
        assertThat(phaseRepository).isNotNull();
        assertThat(teamRepository).isNotNull();
        assertThat(teamAvatarRepository).isNotNull();
        assertThat(matchRepository).isNotNull();
        assertThat(setResultRepository).isNotNull();
        assertThat(matchOutcomeRepository).isNotNull();
        assertThat(teamAvatarRatingRepository).isNotNull();
        assertThat(auditLogRepository).isNotNull();
        assertThat(roundSnapshotRepository).isNotNull();
    }

    // =========================================================================
    // AC6 — Guard fires before SQL when TenantContext is not set
    // =========================================================================

    @Test
    void guardFiresWhenNoTenantContext() {
        tenantContext.clear();  // explicitly clear — no tenant active

        assertThatThrownBy(() -> tournamentRepository.findAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active TenantContext")
                .as("Guard must fire before SQL when TenantContext is not set (AC6)");
    }

    @Test
    void guardFiresOnFindByIdWhenNoTenantContext() {
        tenantContext.clear();

        assertThatThrownBy(() -> tournamentRepository.findById(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active TenantContext");
    }

    // =========================================================================
    // AC7 — Tenant spoof rejected on save
    // =========================================================================

    @Test
    @Transactional
    void tenantSpoofRejectedOnTournamentSave() {
        UUID otherTenant = UUID.randomUUID();  // not the active tenant

        Tournament spoofed = new Tournament(
                UUID.randomUUID(), otherTenant, "Spoofed Tournament",
                MatchFormat.BEST_OF_3.name(), "ruleA", "valA", "genA",
                "DRAFT", null);

        assertThatThrownBy(() -> tournamentRepository.save(spoofed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tenant spoof rejected")
                .hasMessageContaining(otherTenant.toString())
                .as("Save must reject entity with different tenantId (AC7)");
    }

    // =========================================================================
    // AC8 — Normal CRUD: create, read, update, delete round-trip
    // =========================================================================

    @Test
    @Transactional
    void normalCrudCycleForTournament() {
        UUID id = UUID.randomUUID();

        // CREATE
        Tournament created = new Tournament(
                id, defaultTenantId, "Test Tournament",
                MatchFormat.BEST_OF_3.name(), "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now());
        Tournament saved = tournamentRepository.save(created);
        assertThat(saved).isNotNull();

        // READ BACK
        Optional<Tournament> found = tournamentRepository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().getDescription()).isEqualTo("Test Tournament");
        assertThat(found.get().getTenantId()).isEqualTo(defaultTenantId);

        // UPDATE
        found.get().setDescription("Updated Tournament");
        tournamentRepository.save(found.get());

        // READ AGAIN
        Optional<Tournament> updated = tournamentRepository.findById(id);
        assertThat(updated).isPresent();
        assertThat(updated.get().getDescription()).isEqualTo("Updated Tournament");

        // DELETE
        tournamentRepository.deleteById(id);
        assertThat(tournamentRepository.findById(id)).isEmpty();
    }

    // =========================================================================
    // AC5 — AuditLogRepository: append-only; deleteById throws
    // =========================================================================

    @Test
    void auditLogRepositoryDeleteByIdThrows() {
        assertThatThrownBy(() -> auditLogRepository.deleteById(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("append-only")
                .as("AuditLogRepository.deleteById must throw UnsupportedOperationException (AC5)");
    }

    @Test
    @Transactional
    void auditLogRepositoryCanSaveAndQueryByMatchAndSetIndex() {
        // Set up prerequisite chain: tenant → tournament → phase → team → team_avatar → match
        // Required because audit_log has FK to match(id).
        UUID tournamentId = createTournament();
        UUID phaseId = createPhase(tournamentId);
        UUID team1Id = createTeam(tournamentId, 1);
        UUID team2Id = createTeam(tournamentId, 2);
        UUID avatar1Id = createTeamAvatar(tournamentId, phaseId, team1Id, 1, 1);
        UUID avatar2Id = createTeamAvatar(tournamentId, phaseId, team2Id, 1, 2);
        UUID matchId = createMatch(tournamentId, phaseId, avatar1Id, avatar2Id);

        // Append two audit log entries for the same match + different set indices
        UUID entry1Id = UUID.randomUUID();
        AuditLogEntry entry1 = new AuditLogEntry(
                entry1Id, defaultTenantId, matchId, 0,
                null, null, 15, 10,
                null, SetState.WINNER1.getLegacyCode(),
                null, null, LocalDateTime.now(), null, null);
        auditLogRepository.save(entry1);

        UUID entry2Id = UUID.randomUUID();
        AuditLogEntry entry2 = new AuditLogEntry(
                entry2Id, defaultTenantId, matchId, 1,
                null, null, 8, 15,
                null, SetState.WINNER2.getLegacyCode(),
                null, null, LocalDateTime.now(), null, null);
        auditLogRepository.save(entry2);

        // Query by matchId + setIndex=0 — should return only entry1
        List<AuditLogEntry> set0Entries =
                auditLogRepository.findByMatchIdAndSetIndexOrderByChangedAt(matchId, 0);
        assertThat(set0Entries).hasSize(1);
        assertThat(set0Entries.get(0).getId()).isEqualTo(entry1Id);

        // Query by matchId + setIndex=1 — should return only entry2
        List<AuditLogEntry> set1Entries =
                auditLogRepository.findByMatchIdAndSetIndexOrderByChangedAt(matchId, 1);
        assertThat(set1Entries).hasSize(1);
        assertThat(set1Entries.get(0).getId()).isEqualTo(entry2Id);
    }

    // =========================================================================
    // AC4 — Domain-specific queries: findByPhaseId, findByMatchId
    // =========================================================================

    @Test
    @Transactional
    void teamAvatarFindByPhaseId() {
        UUID tournamentId = createTournament();
        UUID phaseId = createPhase(tournamentId);
        UUID team1Id = createTeam(tournamentId, 1);
        UUID team2Id = createTeam(tournamentId, 2);

        UUID avatar1Id = createTeamAvatar(tournamentId, phaseId, team1Id, 1, 1);
        UUID avatar2Id = createTeamAvatar(tournamentId, phaseId, team2Id, 1, 2);

        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);
        assertThat(avatars).hasSize(2);
        assertThat(avatars).extracting(TeamAvatar::getId)
                .containsExactlyInAnyOrder(avatar1Id, avatar2Id);
    }

    @Test
    @Transactional
    void matchFindByPhaseId() {
        UUID tournamentId = createTournament();
        UUID phaseId = createPhase(tournamentId);
        UUID team1Id = createTeam(tournamentId, 1);
        UUID team2Id = createTeam(tournamentId, 2);
        UUID avatar1Id = createTeamAvatar(tournamentId, phaseId, team1Id, 1, 1);
        UUID avatar2Id = createTeamAvatar(tournamentId, phaseId, team2Id, 1, 2);
        UUID matchId = createMatch(tournamentId, phaseId, avatar1Id, avatar2Id);

        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getId()).isEqualTo(matchId);
    }

    @Test
    @Transactional
    void setResultFindByMatchId() {
        UUID tournamentId = createTournament();
        UUID phaseId = createPhase(tournamentId);
        UUID team1Id = createTeam(tournamentId, 1);
        UUID team2Id = createTeam(tournamentId, 2);
        UUID avatar1Id = createTeamAvatar(tournamentId, phaseId, team1Id, 1, 1);
        UUID avatar2Id = createTeamAvatar(tournamentId, phaseId, team2Id, 1, 2);
        UUID matchId = createMatch(tournamentId, phaseId, avatar1Id, avatar2Id);

        SetResult sr = new SetResult(
                matchId, 0, defaultTenantId, phaseId,
                15, 10, SetState.WINNER1.getLegacyCode(),
                null, null);
        setResultRepository.insert(sr);

        List<SetResult> results = setResultRepository.findByMatchId(matchId);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getMatchId()).isEqualTo(matchId);
        assertThat(results.get(0).getSetIndex()).isEqualTo(0);
    }

    // =========================================================================
    // AC9 — DefaultTenantContextResolver bean exists
    // =========================================================================

    @Test
    void defaultTenantContextResolverBeanExists() {
        assertThat(tenantRegistryPort).isNotNull();
        assertThat(tenantRegistryPort.findAll().get(0).tenantId()).isNotNull();
    }

    // =========================================================================
    // AC10 — Missing context produces clear exception
    // =========================================================================

    @Test
    void missingContextProducesClearException() {
        tenantContext.clear();
        assertThatThrownBy(() -> tournamentRepository.findAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active TenantContext")
                .hasMessageContaining("DefaultTenantContextResolver")
                .as("AC10: clear exception naming the missing context must be thrown");
    }

    // =========================================================================
    // AC11 — Concurrent threads do not leak context
    // =========================================================================

    @Test
    void concurrentThreadsDoNotLeakContext() throws Exception {
        // This test does NOT use @Transactional — it manages its own threads.
        // Each thread sets its own TenantContext UUID and verifies it reads back.
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        CountDownLatch bothSet = new CountDownLatch(2);
        CountDownLatch bothVerified = new CountDownLatch(2);

        List<Throwable> errors = new ArrayList<>();

        Runnable threadA = () -> {
            try {
                tenantContext.set(tenantA);
                bothSet.countDown();
                bothSet.await();  // wait for both to be set before verifying
                UUID read = tenantContext.getTenantId();
                assertThat(read).as("Thread A must see tenantA").isEqualTo(tenantA);
                bothVerified.countDown();
            } catch (Throwable t) {
                errors.add(t);
                bothSet.countDown();
                bothVerified.countDown();
            } finally {
                tenantContext.clear();
            }
        };

        Runnable threadB = () -> {
            try {
                tenantContext.set(tenantB);
                bothSet.countDown();
                bothSet.await();
                UUID read = tenantContext.getTenantId();
                assertThat(read).as("Thread B must see tenantB").isEqualTo(tenantB);
                bothVerified.countDown();
            } catch (Throwable t) {
                errors.add(t);
                bothSet.countDown();
                bothVerified.countDown();
            } finally {
                tenantContext.clear();
            }
        };

        var executor = Executors.newFixedThreadPool(2);
        Future<?> fa = executor.submit(threadA);
        Future<?> fb = executor.submit(threadB);
        fa.get();
        fb.get();
        executor.shutdown();

        assertThat(errors).as("No errors in concurrent thread test (AC11)").isEmpty();
    }

    // =========================================================================
    // AC13 — No unscoped escape hatch (compile-time; no findAllUnscoped method)
    // =========================================================================

    @Test
    void tournamentRepositoryHasNoFindAllUnscopedMethod() throws NoSuchMethodException {
        // Verify at runtime that TournamentRepository does not expose findAllUnscoped.
        // This is primarily a compile-time guarantee — this test provides defense in depth.
        boolean found = false;
        for (java.lang.reflect.Method method : tournamentRepository.getClass().getMethods()) {
            if (method.getName().contains("Unscoped")) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .as("AC13: TournamentRepository must not expose any method with 'Unscoped' in the name")
                .isFalse();
    }

    // =========================================================================
    // Helpers — create prerequisite objects
    // =========================================================================

    private UUID createTournament() {
        UUID id = UUID.randomUUID();
        Tournament t = new Tournament(
                id, defaultTenantId, "Test Tournament " + id,
                MatchFormat.BEST_OF_3.name(), "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now());
        tournamentRepository.save(t);
        return id;
    }

    private UUID createPhase(UUID tournamentId) {
        UUID id = UUID.randomUUID();
        Phase p = new Phase(id, defaultTenantId, tournamentId, 1, "Vorrunde", "PENDING", 0,
                LocalDateTime.now());
        phaseRepository.save(p);
        return id;
    }

    private UUID createTeam(UUID tournamentId, int teamNumber) {
        UUID id = UUID.randomUUID();
        Team t = new Team(id, defaultTenantId, tournamentId, teamNumber, "Team " + teamNumber,
                true, false, false, LocalDateTime.now());
        teamRepository.save(t);
        return id;
    }

    private UUID createTeamAvatar(UUID tournamentId, UUID phaseId, UUID teamId,
                                   int groupNumber, int groupPosition) {
        UUID id = UUID.randomUUID();
        TeamAvatar ta = new TeamAvatar(id, defaultTenantId, tournamentId, phaseId,
                groupNumber, groupPosition, teamId, null, LocalDateTime.now());
        teamAvatarRepository.save(ta);
        return id;
    }

    private UUID createMatch(UUID tournamentId, UUID phaseId,
                              UUID avatar1Id, UUID avatar2Id) {
        UUID id = UUID.randomUUID();
        Match m = new Match(id, defaultTenantId, tournamentId, phaseId,
                avatar1Id, avatar2Id,
                MatchState.OPEN.getLegacyCode(), MatchFormat.BEST_OF_3.getMaxSets(),
                null, null, null, null, null, LocalDateTime.now());
        matchRepository.save(m);
        return id;
    }
}
