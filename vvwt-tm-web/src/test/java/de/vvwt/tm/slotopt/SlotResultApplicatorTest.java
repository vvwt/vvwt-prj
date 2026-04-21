package de.vvwt.tm.slotopt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link SlotResultApplicator} — covers AC12 of story E04S02. */
@ExtendWith(MockitoExtension.class)
class SlotResultApplicatorTest {

    @Mock private MatchRepository matchRepository;

    @Mock private TeamAvatarRepository teamAvatarRepository;

    private SlotResultApplicator applicator;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final int FIELD_COUNT = 3;

    @BeforeEach
    void setUp() {
        applicator = new SlotResultApplicator(matchRepository);
    }

    // -------------------------------------------------------------------------
    // AC12 — result application: N=6, fieldCount=3, rank=0
    //   All 15 matches receive non-null (lapNumber, fieldNumber)
    //   No avatar plays twice in the same lap
    //   5 laps × 3 fields = 15 match slots
    // -------------------------------------------------------------------------

    @Test
    void applyResult_sixAvatars_threeFields_allMatchesGetSlots() {
        UUID phaseId = UUID.randomUUID();
        List<TeamAvatar> avatars =
                buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}, {1, 3}, {2, 1}, {2, 2}, {2, 3}});

        // Build mapper (no Spring context needed — use production code directly)
        PhaseToRawPhaseDefMapper mapperInstance = buildTestMapper(phaseId, avatars);

        MappingResult mapping = mapperInstance.map(phaseId);
        int n = mapping.avatarCount();
        assertThat(n).isEqualTo(6);

        // Apply with rank=0 (identity permutation)
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        applicator.applyResult(0L, FIELD_COUNT, mapping);

        // Verify all 15 matches were saved
        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository, times(15)).save(captor.capture());

        List<Match> saved = captor.getAllValues();

        // AC12: all matches have non-null (lapNumber, fieldNumber)
        for (Match m : saved) {
            assertThat(m.getLapNumber())
                    .as("lapNumber must be non-null for match %s", m.getId())
                    .isNotNull();
            assertThat(m.getFieldNumber())
                    .as("fieldNumber must be non-null for match %s", m.getId())
                    .isNotNull();
        }

        // AC12: no avatar plays twice in the same lap
        // Build avatar-ID-to-denseId mapping for the assertion
        Map<UUID, Integer> avatarIdToDenseId = buildAvatarDenseIdMap(avatars, mapping);
        assertNoAvatarPlaysTwiceInSameLap(saved, avatarIdToDenseId, n);

        // AC12: 5 laps × 3 fields = 15 match slots
        Set<String> usedSlots = new HashSet<>();
        for (Match m : saved) {
            String slot = m.getLapNumber() + ":" + m.getFieldNumber();
            assertThat(usedSlots.add(slot))
                    .as(
                            "Slot (%d, %d) must be unique — assigned to two matches",
                            m.getLapNumber(), m.getFieldNumber())
                    .isTrue();
        }
        assertThat(usedSlots).hasSize(15);

        // Verify lap count ≤ (N-1)
        int maxLap = saved.stream().mapToInt(Match::getLapNumber).max().getAsInt();
        assertThat(maxLap).isLessThanOrEqualTo(n - 1);
    }

    // -------------------------------------------------------------------------
    // AC12 — determinism: same rank → same assignment
    // -------------------------------------------------------------------------

    @Test
    void applyResult_determinism_sameRankProducesSameAssignment() {
        UUID phaseId = UUID.randomUUID();
        List<TeamAvatar> avatars =
                buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}, {1, 3}, {2, 1}, {2, 2}, {2, 3}});

        PhaseToRawPhaseDefMapper mapperInstance = buildTestMapper(phaseId, avatars);
        MappingResult mapping = mapperInstance.map(phaseId);

        // Capture first run
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        applicator.applyResult(0L, FIELD_COUNT, mapping);

        ArgumentCaptor<Match> captor1 = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository, times(15)).save(captor1.capture());

        // Reset and re-build mapping with same data
        org.mockito.Mockito.clearInvocations(matchRepository);
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        MappingResult mapping2 = mapperInstance.map(phaseId);
        applicator.applyResult(0L, FIELD_COUNT, mapping2);
        ArgumentCaptor<Match> captor2 = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository, times(15)).save(captor2.capture());

        // Build UUID → (lap, field) maps for both runs
        Map<UUID, String> slots1 = buildSlotMap(captor1.getAllValues());
        Map<UUID, String> slots2 = buildSlotMap(captor2.getAllValues());

        assertThat(slots1).isEqualTo(slots2);
    }

    // -------------------------------------------------------------------------
    // Error handling: null mapping throws IAE
    // -------------------------------------------------------------------------

    @Test
    void applyResult_throwsIAE_onNullMapping() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> applicator.applyResult(0L, 3, null))
                .withMessageContaining("mapping must not be null");
    }

    // -------------------------------------------------------------------------
    // Error handling: fieldCount < 1 throws IAE
    // -------------------------------------------------------------------------

    @Test
    void applyResult_throwsIAE_onInvalidFieldCount() {
        UUID phaseId = UUID.randomUUID();
        List<TeamAvatar> avatars = buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}});
        PhaseToRawPhaseDefMapper mapperInstance = buildTestMapper(phaseId, avatars);
        MappingResult mapping = mapperInstance.map(phaseId);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> applicator.applyResult(0L, 0, mapping))
                .withMessageContaining("fieldCount must be >= 1");
    }

    // -------------------------------------------------------------------------
    // Multiple permutations: all produce valid (lap, field) assignments
    // -------------------------------------------------------------------------

    @Test
    void applyResult_multipleRanks_allProduceValidAssignments() {
        UUID phaseId = UUID.randomUUID();
        List<TeamAvatar> avatars =
                buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}, {1, 3}, {2, 1}, {2, 2}, {2, 3}});
        PhaseToRawPhaseDefMapper mapperInstance = buildTestMapper(phaseId, avatars);

        // Test rank 0, a middle rank, and the last rank for N=6 (6! - 1 = 719)
        long[] testRanks = {0L, 360L, 719L};

        for (long rank : testRanks) {
            org.mockito.Mockito.clearInvocations(matchRepository);
            when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

            MappingResult mapping = mapperInstance.map(phaseId);
            applicator.applyResult(rank, FIELD_COUNT, mapping);

            ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
            verify(matchRepository, times(15)).save(captor.capture());

            List<Match> saved = captor.getAllValues();

            // All slots non-null
            for (Match m : saved) {
                assertThat(m.getLapNumber())
                        .as("rank=%d, match=%s lapNumber null", rank, m.getId())
                        .isNotNull();
                assertThat(m.getFieldNumber())
                        .as("rank=%d, match=%s fieldNumber null", rank, m.getId())
                        .isNotNull();
            }

            // No duplicate slots
            Set<String> usedSlots = new HashSet<>();
            for (Match m : saved) {
                String slot = m.getLapNumber() + ":" + m.getFieldNumber();
                assertThat(usedSlots.add(slot))
                        .as(
                                "rank=%d: duplicate slot (%d,%d)",
                                rank, m.getLapNumber(), m.getFieldNumber())
                        .isTrue();
            }

            // Round constraint: build avatar-ID-to-denseId mapping
            Map<UUID, Integer> avatarIdToDenseId = buildAvatarDenseIdMap(avatars, mapping);
            assertNoAvatarPlaysTwiceInSameLap(saved, avatarIdToDenseId, 6);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link PhaseToRawPhaseDefMapper} backed by mocked repositories that return the given
     * avatars and the C(n,2) all-pair matches.
     */
    private PhaseToRawPhaseDefMapper buildTestMapper(UUID phaseId, List<TeamAvatar> avatars) {
        List<Match> matches = buildAllPairMatches(phaseId, avatars);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        return new PhaseToRawPhaseDefMapper(teamAvatarRepository, matchRepository);
    }

    /** Builds 6 TeamAvatars with the given (groupNumber, groupPosition) pairs. */
    private List<TeamAvatar> buildAvatars(UUID phaseId, int[][] groupPos) {
        List<TeamAvatar> result = new ArrayList<>();
        UUID tournamentId = UUID.randomUUID();
        for (int[] gp : groupPos) {
            TeamAvatar avatar =
                    new TeamAvatar(
                            UUID.randomUUID(),
                            TENANT_ID,
                            tournamentId,
                            phaseId,
                            gp[0],
                            gp[1],
                            UUID.randomUUID(),
                            null,
                            null);
            result.add(avatar);
        }
        return result;
    }

    private List<Match> buildAllPairMatches(UUID phaseId, List<TeamAvatar> avatars) {
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < avatars.size(); i++) {
            for (int j = i + 1; j < avatars.size(); j++) {
                matches.add(
                        new Match(
                                UUID.randomUUID(),
                                TENANT_ID,
                                TOURNAMENT_ID,
                                phaseId,
                                avatars.get(i).getId(),
                                avatars.get(j).getId(),
                                MatchState.OPEN.getLegacyCode(),
                                1,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
            }
        }
        return matches;
    }

    /**
     * Builds a map from avatar UUID to dense ID using the mapping result's denseIdsByRawRow and
     * matchOrder list.
     */
    private Map<UUID, Integer> buildAvatarDenseIdMap(
            List<TeamAvatar> avatars, MappingResult mapping) {
        // We need to reconstruct: avatarId → denseId
        // The matchOrder and denseIdsByRawRow give us: match[i] → denseIds[i][0,1]
        // and match[i].memberAvatar1Id → denseIds[i][0], match[i].memberAvatar2Id → denseIds[i][1]
        Map<UUID, Integer> result = new HashMap<>();
        List<Match> matchOrder = mapping.matchOrder();
        int[][] denseIdsByRawRow = mapping.denseIdsByRawRow();
        for (int i = 0; i < matchOrder.size(); i++) {
            Match m = matchOrder.get(i);
            result.put(m.getMemberAvatar1Id(), denseIdsByRawRow[i][0]);
            result.put(m.getMemberAvatar2Id(), denseIdsByRawRow[i][1]);
        }
        return result;
    }

    /** Asserts that no avatar (by dense ID) appears in two matches within the same lap. */
    private void assertNoAvatarPlaysTwiceInSameLap(
            List<Match> saved, Map<UUID, Integer> avatarIdToDenseId, int n) {
        // lap → Set of dense IDs appearing in that lap
        Map<Integer, Set<Integer>> lapAvatarIds = new HashMap<>();
        for (Match m : saved) {
            int lap = m.getLapNumber();
            Set<Integer> usedIds = lapAvatarIds.computeIfAbsent(lap, k -> new HashSet<>());
            Integer d1 = avatarIdToDenseId.get(m.getMemberAvatar1Id());
            Integer d2 = avatarIdToDenseId.get(m.getMemberAvatar2Id());
            if (d1 != null) {
                assertThat(usedIds.add(d1))
                        .as(
                                "Avatar (denseId=%d) appears twice in lap %d — round constraint"
                                        + " violated",
                                d1, lap)
                        .isTrue();
            }
            if (d2 != null) {
                assertThat(usedIds.add(d2))
                        .as(
                                "Avatar (denseId=%d) appears twice in lap %d — round constraint"
                                        + " violated",
                                d2, lap)
                        .isTrue();
            }
        }
    }

    private Map<UUID, String> buildSlotMap(List<Match> saved) {
        Map<UUID, String> result = new HashMap<>();
        for (Match m : saved) {
            result.put(m.getId(), m.getLapNumber() + ":" + m.getFieldNumber());
        }
        return result;
    }
}
