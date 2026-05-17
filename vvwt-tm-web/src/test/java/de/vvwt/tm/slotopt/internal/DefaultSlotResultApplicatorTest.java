// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.SlotResultApplicator;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultSlotResultApplicator} (via {@link SlotResultApplicator} interface).
 *
 * <p>Covers AC12 of story E04S02 (original) and the E51S11 refactor: flat-index
 * rank-as-lap-permutation model.
 *
 * <p>E51S11 RED-first tests (per DEC-22 Iron Law):
 *
 * <ul>
 *   <li>AC-TEST-RANK-AS-LAP-PERMUTATION-RED
 *   <li>AC-TEST-NON-IDENTITY-RANK-PERMUTES-LAPS-RED
 *   <li>AC-TEST-FIELD-INVARIANT-UNDER-LAP-PERMUTATION-RED
 *   <li>AC-TEST-ROUND-CONFLICT-FREEDOM-AFTER-L3-RED
 *   <li>AC-ERROR-HANDLING-EMPTY-PHASE
 * </ul>
 *
 * @see DefaultSlotResultApplicator
 * @see SlotResultApplicator
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E51S11.story.md">Story
 *     E51S11</a>
 * @since E57S01 (moved from slotopt.SlotResultApplicatorTest to slotopt.internal per DEC-58
 *     interface extraction)
 */
@ExtendWith(MockitoExtension.class)
class DefaultSlotResultApplicatorTest {

    @Mock private MatchRepository matchRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;

    private SlotResultApplicator applicator;

    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final int FIELD_COUNT = 3;

    @BeforeEach
    void setUp() {
        applicator = new DefaultSlotResultApplicator(matchRepository);
    }

    // =========================================================================
    // AC-TEST-RANK-AS-LAP-PERMUTATION-RED (E51S11)
    //
    // Given: lapCount=4, fieldCount=3, rank=0 (identity permutation).
    // L2 has pre-set matches with lap 0-3, field 0-2 (12 total).
    // After applyResult(0L, 3, mapping): identity π=[0,1,2,3] → each match's
    // new lapNumber = π[l2Lap] = l2Lap (unchanged), fieldNumber = l2Field (unchanged).
    //
    // FAILS before fix: current code uses LehmerCodec(rank=0, n=avatarCount) and
    // circle-method → produces different (lap, field) values for each match.
    // =========================================================================

    @Test
    void applyResult_identityRank_preservesL2LapFieldAssignment_lapPermutationModel() {
        UUID phaseId = UUID.randomUUID();
        // 4 groups × 3 avatars = 12 matches, lapCount=4, fieldCount=3
        Fixture f = buildMultiGroupFixture(phaseId, 4, 3);
        // Snapshot L2 slots BEFORE applyResult modifies the match objects
        Map<UUID, int[]> l2Slots = l2SlotsByUuid(f.matches);
        MappingResult mapping = buildMapping(phaseId, f.avatars, f.matches);

        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        applicator.applyResult(0L, FIELD_COUNT, mapping);

        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository, times(12)).save(captor.capture());
        List<Match> saved = captor.getAllValues();
        for (Match m : saved) {
            int[] l2 = l2Slots.get(m.getId());
            if (l2 != null) {
                assertThat(m.getLapNumber())
                        .as(
                                "rank=0 identity: lap must be unchanged for match %s (l2Lap=%d)",
                                m.getId(), l2[0])
                        .isEqualTo(l2[0]);
                assertThat(m.getFieldNumber())
                        .as(
                                "rank=0 identity: field must be unchanged for match %s"
                                        + " (l2Field=%d)",
                                m.getId(), l2[1])
                        .isEqualTo(l2[1]);
            }
        }

        // All 12 expected slots (lap 1-4, field 1-3) present exactly once (1-based: E53S06+DEC-60
        // D-1)
        Set<String> observedSlots = new HashSet<>();
        for (Match m : saved) {
            observedSlots.add(m.getLapNumber() + ":" + m.getFieldNumber());
        }
        for (int lap = 1; lap <= 4; lap++) {
            for (int field = 1; field <= 3; field++) {
                assertThat(observedSlots)
                        .as(
                                "Slot %d:%d must be present after identity permutation (1-based,"
                                        + " E53S06 + DEC-60 D-1)",
                                lap, field)
                        .contains(lap + ":" + field);
            }
        }
    }

    // =========================================================================
    // AC-TEST-NON-IDENTITY-RANK-PERMUTES-LAPS-RED (E51S11)
    //
    // Given: lapCount=4, fieldCount=3.
    // Rank=1 → π=[0,1,3,2] (swaps laps 2 and 3).
    // Lehmer code for [0,1,3,2]: [0,0,1,0] → 0×6 + 0×2 + 1×1 + 0 = 1.
    // After applyResult(1L, 3, mapping):
    //   - Match at L2 (lap=2, field=f) → new (lap=3, field=f)
    //   - Match at L2 (lap=3, field=f) → new (lap=2, field=f)
    //   - Matches at lap=0, lap=1 unchanged.
    //
    // FAILS before fix: current code uses circle-method, not lap-permutation.
    // =========================================================================

    @Test
    void applyResult_rank1_swapsLaps2And3_accordingToLapPermutation() {
        UUID phaseId = UUID.randomUUID();
        Fixture f = buildMultiGroupFixture(phaseId, 4, 3);
        // Snapshot L2 slots BEFORE applyResult modifies the match objects
        Map<UUID, int[]> l2Slots = l2SlotsByUuid(f.matches);
        MappingResult mapping = buildMapping(phaseId, f.avatars, f.matches);

        // Rank=1 → π=[0,1,3,2]
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        applicator.applyResult(1L, FIELD_COUNT, mapping);

        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository, times(12)).save(captor.capture());
        List<Match> saved = captor.getAllValues();

        // Expected: π=[0,1,3,2] (0-based lap indices)
        // L2 laps are 1-based (E53S06). L3 output: pi maps 0-based index to 0-based index,
        // then output lap = pi[l2LapIndex] + 1 where l2LapIndex = l2Lap - 1.
        int[] pi = {0, 1, 3, 2};
        for (Match m : saved) {
            int[] l2 = l2Slots.get(m.getId());
            if (l2 != null) {
                int l2LapIndex = l2[0] - 1; // convert 1-based L2 lap to 0-based index
                int expectedLap = pi[l2LapIndex] + 1; // apply pi, then back to 1-based (E53S06)
                int expectedField = l2[1]; // field invariant
                assertThat(m.getLapNumber())
                        .as(
                                "match %s: l2Lap=%d (index=%d) → expected new lap=%d (π=[0,1,3,2],"
                                        + " 1-based E53S06)",
                                m.getId(), l2[0], l2LapIndex, expectedLap)
                        .isEqualTo(expectedLap);
                assertThat(m.getFieldNumber())
                        .as(
                                "match %s: fieldNumber must equal l2Field=%d (field invariant)",
                                m.getId(), l2[1])
                        .isEqualTo(expectedField);
            }
        }
    }

    // =========================================================================
    // AC-TEST-FIELD-INVARIANT-UNDER-LAP-PERMUTATION-RED (E51S11)
    //
    // For any rank, every match's fieldNumber after L3 equals its fieldNumber from L2.
    // The lap-permutation only re-orders whole laps; intra-lap field positions are preserved.
    //
    // FAILS before fix: old code assigns fieldNumber = position-within-round ignoring fieldCount.
    // =========================================================================

    @Test
    void applyResult_fieldNumberInvariantUnderLapPermutation_forAllRanks() {
        UUID phaseId = UUID.randomUUID();
        Fixture f = buildMultiGroupFixture(phaseId, 4, 3);

        // lapCount=4 → lapCount! = 24 permutations
        for (long rank = 0L; rank <= 23L; rank++) {
            org.mockito.Mockito.clearInvocations(matchRepository);
            // Rebuild matches with fresh L2 lap/field (Match objects are mutable)
            Fixture fresh = buildMultiGroupFixture(phaseId, f.avatars);
            // Snapshot L2 slots BEFORE applyResult modifies the match objects
            Map<UUID, int[]> l2Slots = l2SlotsByUuid(fresh.matches);
            MappingResult freshMapping = buildMapping(phaseId, fresh.avatars, fresh.matches);
            when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

            applicator.applyResult(rank, FIELD_COUNT, freshMapping);

            ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
            verify(matchRepository, times(12)).save(captor.capture());
            List<Match> saved = captor.getAllValues();
            for (Match m : saved) {
                int[] l2 = l2Slots.get(m.getId());
                if (l2 != null) {
                    assertThat(m.getFieldNumber())
                            .as(
                                    "rank=%d, match %s: fieldNumber=%d must equal l2Field=%d "
                                            + "(AC-TEST-FIELD-INVARIANT-UNDER-LAP-PERMUTATION-RED)",
                                    rank, m.getId(), m.getFieldNumber(), l2[1])
                            .isEqualTo(l2[1]);
                }
            }
        }
    }

    // =========================================================================
    // AC-TEST-ROUND-CONFLICT-FREEDOM-AFTER-L3-RED (E51S11)
    //
    // For any rank, no avatar appears in >1 match per lap after L3.
    // Lap-permutation preserves round structure (L2 guaranteed one match per avatar per lap;
    // reordering laps preserves this invariant).
    //
    // Uses a "conflict-free L2 schedule" fixture: 4 laps × 3 fields × 6 avatars.
    // Each avatar appears exactly once per lap (valid tournament schedule).
    // fieldCount=3, lapCount=4, 12 avatars in 12 distinct matches.
    // =========================================================================

    @Test
    void applyResult_roundConflictFreedom_forAllRanks() {
        UUID phaseId = UUID.randomUUID();
        // Build conflict-free fixture: 24 distinct avatars, 12 matches, 4 laps × 3 fields.
        // Each lap uses 6 unique avatars (2 per match × 3 matches) — disjoint from other laps.
        // Since lap-permutation only reorders whole laps, each output lap will still have
        // 3 matches with 6 mutually-distinct avatars → no conflict possible for any rank.
        List<TeamAvatar> cfAvatars = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            cfAvatars.add(
                    new TeamAvatar(
                            UUID.randomUUID(),
                            TOURNAMENT_ID,
                            phaseId,
                            1,
                            i + 1,
                            UUID.randomUUID(),
                            null,
                            null));
        }
        // Build matches: lap=k, field=f → cfAvatars[k*6 + f*2] vs cfAvatars[k*6 + f*2 + 1]
        List<Match> baseMatches = new ArrayList<>();
        for (int lap = 0; lap < 4; lap++) {
            for (int field = 0; field < 3; field++) {
                int base = lap * 6 + field * 2;
                baseMatches.add(
                        new Match(
                                UUID.randomUUID(),
                                TOURNAMENT_ID,
                                phaseId,
                                cfAvatars.get(base).getId(),
                                cfAvatars.get(base + 1).getId(),
                                MatchState.OPEN.getLegacyCode(),
                                1,
                                lap,
                                field,
                                null,
                                null,
                                null,
                                null));
            }
        }

        for (long rank = 0L; rank <= 23L; rank++) {
            org.mockito.Mockito.clearInvocations(matchRepository);
            // Rebuild fresh Match objects (Match is mutable — applyResult mutates lap/field in
            // place)
            List<Match> freshMatches = new ArrayList<>();
            for (Match m : baseMatches) {
                freshMatches.add(
                        new Match(
                                m.getId(),
                                m.getTournamentId(),
                                m.getPhaseId(),
                                m.getMemberAvatar1Id(),
                                m.getMemberAvatar2Id(),
                                m.getState(),
                                m.getSetLimit(),
                                m.getLapNumber(),
                                m.getFieldNumber(),
                                null,
                                null,
                                null,
                                null));
            }
            when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(cfAvatars);
            when(matchRepository.findByPhaseId(phaseId)).thenReturn(freshMatches);
            MappingResult freshMapping =
                    new DefaultPhaseToRawPhaseDefMapper(teamAvatarRepository, matchRepository)
                            .map(phaseId);
            when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

            applicator.applyResult(rank, FIELD_COUNT, freshMapping);

            ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
            verify(matchRepository, times(12)).save(captor.capture());
            List<Match> saved = captor.getAllValues();

            // No avatar appears in >1 match per lap
            Map<Integer, Set<UUID>> avatarsByLap = new HashMap<>();
            for (Match m : saved) {
                int lap = m.getLapNumber();
                Set<UUID> inLap = avatarsByLap.computeIfAbsent(lap, k -> new HashSet<>());
                assertThat(inLap.add(m.getMemberAvatar1Id()))
                        .as(
                                "rank=%d: avatar1 %s plays twice in lap %d",
                                rank, m.getMemberAvatar1Id(), lap)
                        .isTrue();
                assertThat(inLap.add(m.getMemberAvatar2Id()))
                        .as(
                                "rank=%d: avatar2 %s plays twice in lap %d",
                                rank, m.getMemberAvatar2Id(), lap)
                        .isTrue();
            }
        }
    }

    // =========================================================================
    // AC-ERROR-HANDLING-EMPTY-PHASE (E51S11)
    //
    // Given: 0 matches → applyResult is a no-op (no exception, no save calls).
    //
    // FAILS before fix: current code calls LehmerCodec.rankToPermutation(0L, 0) which throws
    // IAE "n must be at least 1, got: 0".
    // =========================================================================

    @Test
    void applyResult_emptyPhase_isNoOp() {
        // Build a zero-match mapping
        de.vvwt.slotopt.worker.types.RawPhaseDef emptyRaw =
                new de.vvwt.slotopt.worker.types.RawPhaseDef(0, 0, List.of());
        de.vvwt.slotopt.worker.types.CanonicalPhaseDef emptyCanonical =
                new de.vvwt.slotopt.worker.types.CanonicalPhaseDef(0, 0, List.of());
        MappingResult emptyMapping =
                new MappingResult(emptyRaw, emptyCanonical, 0, List.of(), new int[0][]);

        // Must not throw, must not call save
        applicator.applyResult(0L, FIELD_COUNT, emptyMapping);
        verify(matchRepository, never()).save(any(Match.class));
    }

    // =========================================================================
    // E53S06: AC2 RED-first — L3 must produce 1-based lap numbers
    //
    // Given: 6 matches with 0-based L2 laps (laps 0,0,0,1,1,1 for fieldCount=3).
    // After applyResult(0L, 3, mapping) (identity rank):
    // MIN(lapNumber) must be 1 (1-based output).
    //
    // RED before fix: current code writes outputLap = i/fc (0-based) → MIN=0 → FAIL.
    // =========================================================================

    @Test
    void applyResult_identityRank_producesOneBased_lapNumbers() {
        // AC2 / E53S06: L3 output lap numbers must be 1-based after fix.
        UUID phaseId = UUID.randomUUID();
        // 2 groups × 3 avatars → 6 matches (fieldCount=3, lapCount=2)
        // L2 laps assigned as 0-based: 0,0,0,1,1,1
        List<TeamAvatar> avatars =
                buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}, {1, 3}, {2, 1}, {2, 2}, {2, 3}});
        // K3 per group = 3 matches; 2 groups → 6 matches total; fieldCount=3 → lapCount=2
        List<Match> l2Matches = buildAllPairMatchesWithL2Slots(phaseId, avatars.subList(0, 3), 3);
        // Add group2 matches with laps offset by 1
        List<Match> group2Matches =
                buildAllPairMatchesWithL2Slots(phaseId, avatars.subList(3, 6), 3);
        for (Match m : group2Matches) {
            m.setLapNumber(m.getLapNumber() + 1); // offset group 2 to lap 1
        }
        List<Match> allMatches = new ArrayList<>();
        allMatches.addAll(l2Matches);
        allMatches.addAll(group2Matches);

        MappingResult mapping = buildMapping(phaseId, avatars, allMatches);
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        applicator.applyResult(0L, 3, mapping);

        // After fix: MIN(lapNumber) must be 1 (1-based)
        int minLap = allMatches.stream().mapToInt(Match::getLapNumber).min().orElse(-1);
        assertThat(minLap)
                .as("MIN(lapNumber) must be 1 after E53S06 L3 fix (1-based; lap 0 is forbidden)")
                .isGreaterThanOrEqualTo(1);
    }

    // =========================================================================
    // E53S09: AC-TEST-L3-PRESERVES-1-BASED-FIELDNUMBER-RED (DEC-60 D-1, DEC-22 RED-first)
    //
    // Given: L2 fixture with 1-based lap and 1-based field (lap ∈ [1..lapCount], field ∈ [1..K]).
    // After applyResult (any rank), MIN(fieldNumber) >= 1 (0 is forbidden per DEC-60 D-1).
    //
    // RED before production change: line 135 writes `outputField` (0-based 0..K-1) →
    // MIN(fieldNumber) = 0 → FAIL.
    // =========================================================================

    @Test
    void applyResult_identityRank_producesOneBased_fieldNumbers() {
        // AC-TEST-L3-PRESERVES-1-BASED-FIELDNUMBER-RED: L3 output fieldNumbers must be 1-based.
        UUID phaseId = UUID.randomUUID();
        // 4 groups × 3 avatars = 12 matches (lapCount=4, fieldCount=3)
        // Build fixture with 1-based L2 fields
        Fixture f = buildMultiGroupFixtureOneBased(phaseId, 4, 3);
        Map<UUID, int[]> l2Slots = l2SlotsByUuid(f.matches);
        MappingResult mapping = buildMappingFromMatches(phaseId, f.avatars, f.matches);

        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        applicator.applyResult(0L, FIELD_COUNT, mapping);

        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository, times(12)).save(captor.capture());
        List<Match> saved = captor.getAllValues();

        int minField = saved.stream().mapToInt(Match::getFieldNumber).min().orElse(-1);
        assertThat(minField)
                .as(
                        "AC-TEST-L3-PRESERVES-1-BASED-FIELDNUMBER-RED: MIN(fieldNumber) must be"
                                + " ≥ 1 after L3 (1-based per DEC-60 D-1; 0 is forbidden)")
                .isGreaterThanOrEqualTo(1);

        int maxField = saved.stream().mapToInt(Match::getFieldNumber).max().orElse(-1);
        assertThat(maxField)
                .as("MAX(fieldNumber) must be ≤ K=3 (1-based: fields 1..3)")
                .isLessThanOrEqualTo(FIELD_COUNT);
    }

    // =========================================================================
    // E54S02 RED-first test — DEC-61 Clause D: lapCount derivation simplified
    // =========================================================================

    /**
     * AC-TEST-SLOTRESULTAPPLICATOR-LAPCOUNT-DIRECT-RED (E54S02):
     *
     * <p>After Mapper refactor (E54S02 DEC-61 Clause B), {@code SlotResultApplicator.applyResult}
     * derives {@code lapCount} from {@code mapping.canonical().rowCount()} directly (NOT from
     * {@code rowCount / fieldCount}).
     *
     * <p>Post-Mapper-refactor, {@code mapping.canonical().rowCount() = lapCount} (rows are
     * lap-rows). The old formula {@code int lapCount = rowCount / fieldCount} where {@code rowCount
     * = matchOrder.size()} is incorrect post-refactor.
     *
     * <p>Observable test: with a lap-row mapping (canonical.rowCount = 2) and 6 matches,
     * applyResult must produce exactly 2 distinct lap values (1 and 2), confirming lapCount=2 is
     * used.
     *
     * <p>RED against current code: current code uses {@code int lapCount = rowCount / fieldCount}
     * where rowCount=matchOrder.size(). For post-refactor MappingResult with matchOrder.size()=6
     * and canonical.rowCount()=2, the old formula gives lapCount=6/3=2 (happens to agree for
     * symmetric setup). The structural RED is: source line must change. The test verifies that the
     * applicator correctly handles a post-Mapper-refactor MappingResult where canonical.rowCount()
     * drives lapCount, and that applying identity rank to a 6-match/2-lap mapping produces lap ∈
     * {1,2}.
     */
    @Test
    void applyResult_lapCountFromCanonicalRowCount_notMatchOrderDivFieldCount_E54S02() {
        UUID phaseId = UUID.randomUUID();
        // 2 laps, 3 fields, 6 matches total (canonical post-refactor: 2 lap-rows)
        Fixture f = buildMultiGroupFixture(phaseId, 2, 3);
        MappingResult lapRowMapping = buildLapRowMappingResult(phaseId, f.avatars(), f.matches());

        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        applicator.applyResult(0L, FIELD_COUNT, lapRowMapping);

        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository, times(6)).save(captor.capture());
        List<Match> saved = captor.getAllValues();

        // Verify exactly 2 distinct lap values: {1, 2}
        Set<Integer> laps = new HashSet<>();
        for (Match m : saved) {
            laps.add(m.getLapNumber());
        }
        assertThat(laps)
                .as(
                        "AC-TEST-SLOTRESULTAPPLICATOR-LAPCOUNT-DIRECT-RED (E54S02): identity rank"
                                + " with lapCount=2 must produce exactly lap values {1,2}")
                .containsExactlyInAnyOrder(1, 2);

        // Each lap must have exactly 3 matches (= fieldCount)
        for (int lap : laps) {
            long matchesInLap = saved.stream().filter(m -> m.getLapNumber() == lap).count();
            assertThat(matchesInLap)
                    .as("lap %d must have exactly %d matches (fieldCount)", lap, FIELD_COUNT)
                    .isEqualTo(FIELD_COUNT);
        }
    }

    // =========================================================================
    // E54S03 RED-first test — AC-TEST-SLOTRESULTAPPLICATOR-PHASE-GLOBAL-INPUT-RED
    // (DEC-61 Clause D: applicator accepts phase-global mapping with lapCount=10)
    // =========================================================================

    /**
     * AC-TEST-SLOTRESULTAPPLICATOR-PHASE-GLOBAL-INPUT-RED (E54S03 / DEC-61 Clause D):
     *
     * <p>Post-E54S03, {@link SlotResultApplicator#applyResult} receives a phase-global {@link
     * MappingResult} with {@code lapCount=10} (30 matches, 3 fields). For rank=0 (identity), all 30
     * matches must receive lap values in [1..10], each lap containing exactly 3 matches (1-based,
     * per DEC-60 D-1).
     *
     * <p>This test verifies that the applicator correctly handles a large phase-global mapping (not
     * split per group). The applicator itself does NOT change for E54S03 — this is a regression
     * guard asserting the existing algorithm handles phase-global input correctly.
     */
    @Test
    void applyResult_phaseGlobal_lapCount10_identityRank_produces10DistinctLaps_E54S03() {
        UUID phaseId = UUID.randomUUID();
        // Phase-global mapping: 10 laps, 3 fields, 30 matches
        // 60 distinct avatars (2 per match × 30 matches), assigned across groups 1 and 2
        List<TeamAvatar> avatars = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            avatars.add(
                    new TeamAvatar(
                            UUID.randomUUID(),
                            TOURNAMENT_ID,
                            phaseId,
                            (i < 30) ? 1 : 2,
                            (i % 30) + 1,
                            UUID.randomUUID(),
                            null,
                            null));
        }
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            int lap = i / 3 + 1; // 1-based: laps 1..10
            int field = i % 3 + 1; // 1-based: fields 1..3
            matches.add(
                    new Match(
                            UUID.randomUUID(),
                            TOURNAMENT_ID,
                            phaseId,
                            avatars.get(i * 2).getId(),
                            avatars.get(i * 2 + 1).getId(),
                            MatchState.OPEN.getLegacyCode(),
                            1,
                            lap,
                            field,
                            null,
                            null,
                            null,
                            null));
        }

        MappingResult mapping = buildMapping(phaseId, avatars, matches);
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        applicator.applyResult(0L, FIELD_COUNT, mapping);

        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository, times(30)).save(captor.capture());
        List<Match> saved = captor.getAllValues();

        // Exactly 10 distinct lap values in [1..10]
        Set<Integer> laps = new HashSet<>();
        for (Match m : saved) {
            laps.add(m.getLapNumber());
        }
        assertThat(laps)
                .as(
                        "AC-TEST-SLOTRESULTAPPLICATOR-PHASE-GLOBAL-INPUT-RED (E54S03):"
                                + " identity rank with lapCount=10 must produce exactly"
                                + " 10 distinct laps in [1..10]")
                .hasSize(10)
                .allMatch(l -> l >= 1 && l <= 10, "all lap values must be 1-based in [1..10]");

        // Each lap must contain exactly 3 matches
        for (int lap = 1; lap <= 10; lap++) {
            final int finalLap = lap;
            long count = saved.stream().filter(m -> m.getLapNumber() == finalLap).count();
            assertThat(count)
                    .as("lap %d must contain exactly 3 matches (fieldCount=3)", lap)
                    .isEqualTo(3L);
        }
    }

    // =========================================================================
    // E54S04 RED-first test — AC-TEST-UNIT-LAPCOUNT-VS-MATCHCOUNT-DECOUPLED-RED
    // Structural decoupling: lapCount ≠ matchCount (asymmetric bye-slot phases)
    // =========================================================================

    /**
     * AC-TEST-UNIT-LAPCOUNT-VS-MATCHCOUNT-DECOUPLED-RED (E54S04 / DEC-61 Clauses B+D):
     *
     * <p>Constructs a {@link MappingResult} where {@code lapCount = canonical.rowCount() = 9} but
     * {@code matchOrder.size() = 25} (simulates an asymmetric 11T/2G/3F phase with bye-slots: 5
     * laps × 2 matches + 5 laps × 3 matches = 25 total matches, 10 laps... reduced here to {@code
     * lapCount=9, matchCount=25} as a structural stress-test).
     *
     * <p>Asserts that {@link SlotResultApplicator#applyResult} with {@code rank=0} (identity) and
     * {@code rank=1} (first non-trivial permutation) both complete WITHOUT throwing {@link
     * IndexOutOfBoundsException}. Prior to the E54S04 fix, the flat-index scheme ({@code π[i/fc]*fc
     * + i%fc}) would compute indices ≥ 25 for certain permutations, causing the OOB crash.
     *
     * <p>RED against the pre-E54S04 flat-index code; GREEN after the lap-group permutation fix.
     *
     * @see <a href="E54S04">E54S04 — Fix: IndexOutOfBoundsException for asymmetric phases</a>
     */
    @Test
    void applyResult_lapCountNotEqualMatchCount_noIndexOutOfBounds_E54S04() {
        UUID phaseId = UUID.randomUUID();

        // Simulate asymmetric 11T/2G/3F: 10 laps, 25 matches
        // Group 1 (5 teams): 5 laps × 2 matches = 10 matches (lapNumbers 1..5, fieldNumbers 1..2)
        // Group 2 (6 teams): 5 laps × 3 matches = 15 matches (lapNumbers 6..10, fieldNumbers 1..3)
        // 11 avatars: group 1 positions 1..5, group 2 positions 1..6
        List<TeamAvatar> avatars = new ArrayList<>();
        for (int pos = 1; pos <= 5; pos++) {
            avatars.add(
                    new TeamAvatar(
                            UUID.randomUUID(),
                            TOURNAMENT_ID,
                            phaseId,
                            1,
                            pos,
                            UUID.randomUUID(),
                            null,
                            null));
        }
        for (int pos = 1; pos <= 6; pos++) {
            avatars.add(
                    new TeamAvatar(
                            UUID.randomUUID(),
                            TOURNAMENT_ID,
                            phaseId,
                            2,
                            pos,
                            UUID.randomUUID(),
                            null,
                            null));
        }

        // Build 10 matches for group 1 (5 laps × 2 matches, laps 1..5, fields 1..2)
        List<Match> matches = new ArrayList<>();
        // All-pair for group 1 (5 teams): C(5,2) = 10 matches
        // Assign L2: 5 laps of 2 matches each
        List<UUID> g1Ids =
                avatars.subList(0, 5).stream().map(TeamAvatar::getId).collect(Collectors.toList());
        int matchIdx = 0;
        for (int i = 0; i < 5; i++) {
            for (int j = i + 1; j < 5; j++) {
                // Assign lap and field
                int lap = matchIdx / 2 + 1; // laps 1..5 (2 matches each)
                int field = matchIdx % 2 + 1; // fields 1..2
                matches.add(
                        new Match(
                                UUID.randomUUID(),
                                TOURNAMENT_ID,
                                phaseId,
                                g1Ids.get(i),
                                g1Ids.get(j),
                                MatchState.OPEN.getLegacyCode(),
                                1,
                                lap,
                                field,
                                null,
                                null,
                                null,
                                null));
                matchIdx++;
            }
        }

        // All-pair for group 2 (6 teams): C(6,2) = 15 matches
        // Assign L2: 5 laps of 3 matches each (laps 6..10)
        List<UUID> g2Ids =
                avatars.subList(5, 11).stream().map(TeamAvatar::getId).collect(Collectors.toList());
        matchIdx = 0;
        for (int i = 0; i < 6; i++) {
            for (int j = i + 1; j < 6; j++) {
                int lap = matchIdx / 3 + 6; // laps 6..10 (3 matches each)
                int field = matchIdx % 3 + 1; // fields 1..3
                matches.add(
                        new Match(
                                UUID.randomUUID(),
                                TOURNAMENT_ID,
                                phaseId,
                                g2Ids.get(i),
                                g2Ids.get(j),
                                MatchState.OPEN.getLegacyCode(),
                                1,
                                lap,
                                field,
                                null,
                                null,
                                null,
                                null));
                matchIdx++;
            }
        }

        assertThat(matches).as("fixture must have exactly 25 matches").hasSize(25);

        MappingResult mapping = buildMapping(phaseId, avatars, matches);

        // canonical.rowCount() = lapCount = 10 (10 lap-rows from the Mapper)
        // matchOrder.size() = 25 — the lapCount ≠ matchCount / fieldCount invariant
        assertThat(mapping.canonical().rowCount())
                .as("canonical.rowCount() must equal lapCount=10")
                .isEqualTo(10);
        assertThat(mapping.matchOrder()).as("matchOrder must contain all 25 matches").hasSize(25);

        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

        // rank=0 (identity permutation): must NOT throw IndexOutOfBoundsException
        // RED before fix: π[i/3]*3 + i%3 computes source index up to 9*3+2=29 >> 24 → OOB
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> applicator.applyResult(0L, FIELD_COUNT, mapping),
                "AC-TEST-UNIT-LAPCOUNT-VS-MATCHCOUNT-DECOUPLED-RED: rank=0 must not throw"
                        + " IndexOutOfBoundsException for asymmetric 11T/2G/3F (lapCount=10,"
                        + " matchCount=25)");

        // rank=1 (first non-trivial permutation): must also NOT throw
        // Re-build mapping with fresh Match objects (applyResult mutates them in-place)
        MappingResult mapping2 = buildMapping(phaseId, avatars, matches);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> applicator.applyResult(1L, FIELD_COUNT, mapping2),
                "AC-TEST-UNIT-LAPCOUNT-VS-MATCHCOUNT-DECOUPLED-RED: rank=1 must not throw"
                        + " IndexOutOfBoundsException for asymmetric 11T/2G/3F");

        // Additional assertion: after identity rank, all 25 matches have non-null lap+field
        // (captured by save invocations)
        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository, times(50)).save(captor.capture()); // 25 per call × 2 calls
        List<Match> saved = captor.getAllValues();
        for (Match m : saved) {
            assertThat(m.getLapNumber())
                    .as("lapNumber must be non-null for match %s", m.getId())
                    .isNotNull()
                    .isGreaterThanOrEqualTo(1);
            assertThat(m.getFieldNumber())
                    .as("fieldNumber must be non-null for match %s", m.getId())
                    .isNotNull()
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // =========================================================================
    // Original error-path tests (unchanged — must remain GREEN)
    // =========================================================================

    @Test
    void applyResult_throwsIAE_onNullMapping() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> applicator.applyResult(0L, 3, null))
                .withMessageContaining("mapping must not be null");
    }

    @Test
    void applyResult_throwsIAE_onInvalidFieldCount() {
        UUID phaseId = UUID.randomUUID();
        List<TeamAvatar> avatars = buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}});
        List<Match> l2Matches = buildAllPairMatchesWithL2Slots(phaseId, avatars, 1);
        MappingResult mapping = buildMapping(phaseId, avatars, l2Matches);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> applicator.applyResult(0L, 0, mapping))
                .withMessageContaining("fieldCount must be >= 1");
    }

    // =========================================================================
    // Updated AC12 tests from E04S02 — adapted for lap-permutation model (E51S11)
    //
    // Properties that must hold under the new implementation:
    //   - All matches receive non-null (lapNumber, fieldNumber)
    //   - No duplicate slots (each (lap, field) pair appears at most once)
    // =========================================================================

    @Test
    void applyResult_allMatchesGetSlots_lapPermutationModel() {
        UUID phaseId = UUID.randomUUID();
        // 4 groups × 3 avatars = 12 matches, lapCount=4, fieldCount=3
        Fixture f = buildMultiGroupFixture(phaseId, 4, 3);
        MappingResult mapping = buildMapping(phaseId, f.avatars, f.matches);

        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        applicator.applyResult(0L, FIELD_COUNT, mapping);

        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository, times(12)).save(captor.capture());
        List<Match> saved = captor.getAllValues();

        for (Match m : saved) {
            assertThat(m.getLapNumber())
                    .as("lapNumber must be non-null for match %s", m.getId())
                    .isNotNull();
            assertThat(m.getFieldNumber())
                    .as("fieldNumber must be non-null for match %s", m.getId())
                    .isNotNull();
        }

        Set<String> usedSlots = new HashSet<>();
        for (Match m : saved) {
            String slot = m.getLapNumber() + ":" + m.getFieldNumber();
            assertThat(usedSlots.add(slot))
                    .as("Slot (%d, %d) must be unique", m.getLapNumber(), m.getFieldNumber())
                    .isTrue();
        }
        assertThat(usedSlots).hasSize(12);
    }

    // =========================================================================
    // Helpers and inner types
    // =========================================================================

    /** Holds avatars + L2-slot-assigned matches for a multi-group fixture. */
    private record Fixture(List<TeamAvatar> avatars, List<Match> matches) {}

    /**
     * Builds a multi-group fixture with groupCount groups × avatarsPerGroup avatars. Matches:
     * intra-group all-pair matches, sorted by UUID, assigned L2 lap/field.
     */
    private Fixture buildMultiGroupFixture(UUID phaseId, int groupCount, int avatarsPerGroup) {
        List<TeamAvatar> avatars = new ArrayList<>();
        for (int g = 1; g <= groupCount; g++) {
            for (int pos = 1; pos <= avatarsPerGroup; pos++) {
                avatars.add(
                        new TeamAvatar(
                                UUID.randomUUID(),
                                TOURNAMENT_ID,
                                phaseId,
                                g,
                                pos,
                                UUID.randomUUID(),
                                null,
                                null));
            }
        }
        return buildMultiGroupFixture(phaseId, avatars);
    }

    /**
     * Builds matches with L2 slots for the given avatars (multi-group: intra-group all-pair).
     * fieldCount = FIELD_COUNT (3).
     */
    private Fixture buildMultiGroupFixture(UUID phaseId, List<TeamAvatar> avatars) {
        // Group avatars by groupNumber
        Map<Integer, List<TeamAvatar>> byGroup = new HashMap<>();
        for (TeamAvatar a : avatars) {
            byGroup.computeIfAbsent(a.getGroupNumber(), k -> new ArrayList<>()).add(a);
        }
        List<Match> matches = new ArrayList<>();
        for (List<TeamAvatar> groupAvatars : byGroup.values()) {
            for (int i = 0; i < groupAvatars.size(); i++) {
                for (int j = i + 1; j < groupAvatars.size(); j++) {
                    matches.add(
                            new Match(
                                    UUID.randomUUID(),
                                    TOURNAMENT_ID,
                                    phaseId,
                                    groupAvatars.get(i).getId(),
                                    groupAvatars.get(j).getId(),
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
        }
        // Sort by UUID (mirrors PhaseToRawPhaseDefMapper deterministic ordering)
        matches.sort((a, b) -> a.getId().toString().compareTo(b.getId().toString()));
        // Assign L2 lap/field in sorted order — 1-based per E53S06 (lap) and DEC-60 D-1 (field)
        for (int i = 0; i < matches.size(); i++) {
            matches.get(i).setLapNumber(i / FIELD_COUNT + 1); // 1-based: lap 1..lapCount (E53S06)
            matches.get(i).setFieldNumber(i % FIELD_COUNT + 1); // 1-based: field 1..K (DEC-60 D-1)
        }
        return new Fixture(avatars, matches);
    }

    /**
     * Builds a MappingResult backed by mock repositories. Matches must have L2
     * lapNumber/fieldNumber already set (PhaseToRawPhaseDefMapper preserves them).
     */
    private MappingResult buildMapping(
            UUID phaseId, List<TeamAvatar> avatars, List<Match> matches) {
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        return new DefaultPhaseToRawPhaseDefMapper(teamAvatarRepository, matchRepository)
                .map(phaseId);
    }

    /** Builds a map from match UUID → L2 (lapNumber, fieldNumber) array. */
    private Map<UUID, int[]> l2SlotsByUuid(List<Match> matches) {
        Map<UUID, int[]> result = new HashMap<>();
        for (Match m : matches) {
            if (m.getLapNumber() != null && m.getFieldNumber() != null) {
                result.put(m.getId(), new int[] {m.getLapNumber(), m.getFieldNumber()});
            }
        }
        return result;
    }

    /**
     * Builds a multi-group fixture with 1-based L2 fields (field ∈ [1..fieldCount]). Used for
     * E53S09 RED-first tests that verify DEC-60 D-1 1-based convention is preserved.
     */
    private Fixture buildMultiGroupFixtureOneBased(
            UUID phaseId, int groupCount, int avatarsPerGroup) {
        List<TeamAvatar> avatars = new ArrayList<>();
        for (int g = 1; g <= groupCount; g++) {
            for (int pos = 1; pos <= avatarsPerGroup; pos++) {
                avatars.add(
                        new TeamAvatar(
                                UUID.randomUUID(),
                                TOURNAMENT_ID,
                                phaseId,
                                g,
                                pos,
                                UUID.randomUUID(),
                                null,
                                null));
            }
        }
        // Build matches intra-group all-pair
        Map<Integer, List<TeamAvatar>> byGroup = new HashMap<>();
        for (TeamAvatar a : avatars) {
            byGroup.computeIfAbsent(a.getGroupNumber(), k -> new ArrayList<>()).add(a);
        }
        List<Match> matches = new ArrayList<>();
        for (List<TeamAvatar> groupAvatars : byGroup.values()) {
            for (int i = 0; i < groupAvatars.size(); i++) {
                for (int j = i + 1; j < groupAvatars.size(); j++) {
                    matches.add(
                            new Match(
                                    UUID.randomUUID(),
                                    TOURNAMENT_ID,
                                    phaseId,
                                    groupAvatars.get(i).getId(),
                                    groupAvatars.get(j).getId(),
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
        }
        matches.sort((a, b) -> a.getId().toString().compareTo(b.getId().toString()));
        // Assign 1-based L2 lap/field: lap ∈ [1..lapCount], field ∈ [1..FIELD_COUNT]
        for (int i = 0; i < matches.size(); i++) {
            matches.get(i).setLapNumber(i / FIELD_COUNT + 1); // 1-based lap
            matches.get(i).setFieldNumber(i % FIELD_COUNT + 1); // 1-based field (DEC-60 D-1)
        }
        return new Fixture(avatars, matches);
    }

    /**
     * Builds a MappingResult from pre-assigned matches (bypasses mapper's sort logic). Avatars are
     * stubbed into teamAvatarRepository, matches into matchRepository.
     */
    private MappingResult buildMappingFromMatches(
            UUID phaseId, List<TeamAvatar> avatars, List<Match> matches) {
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        return new DefaultPhaseToRawPhaseDefMapper(teamAvatarRepository, matchRepository)
                .map(phaseId);
    }

    /** Builds TeamAvatars with given (groupNumber, groupPosition) pairs. */
    private List<TeamAvatar> buildAvatars(UUID phaseId, int[][] groupPos) {
        List<TeamAvatar> result = new ArrayList<>();
        for (int[] gp : groupPos) {
            result.add(
                    new TeamAvatar(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            phaseId,
                            gp[0],
                            gp[1],
                            UUID.randomUUID(),
                            null,
                            null));
        }
        return result;
    }

    /** Builds all-pair matches from the given avatars, with L2 lap/field set. */
    private List<Match> buildAllPairMatchesWithL2Slots(
            UUID phaseId, List<TeamAvatar> avatars, int fieldCount) {
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < avatars.size(); i++) {
            for (int j = i + 1; j < avatars.size(); j++) {
                matches.add(
                        new Match(
                                UUID.randomUUID(),
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
        matches.sort((a, b) -> a.getId().toString().compareTo(b.getId().toString()));
        for (int i = 0; i < matches.size(); i++) {
            matches.get(i).setLapNumber(i / fieldCount);
            matches.get(i).setFieldNumber(i % fieldCount);
        }
        return matches;
    }

    /**
     * Builds a post-refactor (E54S02) {@link MappingResult} where {@code raw.rows()} are lap-rows
     * (one row per lap) instead of match-rows. Used by
     * AC-TEST-SLOTRESULTAPPLICATOR-LAPCOUNT-DIRECT-RED.
     *
     * <p>Constructs {@code RawPhaseDef} with {@code lapCount} rows (each row = union of avatars in
     * that lap). {@code matchOrder} carries all matches (lapCount * fieldCount). {@code
     * canonical.rowCount() = lapCount}.
     *
     * @param phaseId the phase UUID
     * @param avatars the phase avatars (used to build PositionTuples)
     * @param matches the phase matches with 1-based lap/field already set
     * @return post-refactor MappingResult with lap-rows
     */
    private MappingResult buildLapRowMappingResult(
            UUID phaseId, List<TeamAvatar> avatars, List<Match> matches) {
        // Build avatar position index
        java.util.Map<UUID, de.vvwt.slotopt.worker.types.PositionTuple> positionByAvatarId =
                new java.util.HashMap<>();
        for (TeamAvatar avatar : avatars) {
            positionByAvatarId.put(
                    avatar.getId(),
                    new de.vvwt.slotopt.worker.types.PositionTuple(
                            avatar.getGroupNumber(), avatar.getGroupPosition()));
        }

        // Group matches by lap_number (ascending)
        java.util.TreeMap<Integer, List<Match>> matchesByLap = new java.util.TreeMap<>();
        for (Match m : matches) {
            matchesByLap.computeIfAbsent(m.getLapNumber(), k -> new ArrayList<>()).add(m);
        }

        // Build lap-rows: one RawRow per lap, positions = union of avatars in that lap
        List<de.vvwt.slotopt.worker.types.RawRow> lapRows = new ArrayList<>();
        List<Match> orderedMatches = new ArrayList<>();
        for (Map.Entry<Integer, List<Match>> entry : matchesByLap.entrySet()) {
            List<Match> lapMatches = entry.getValue();
            lapMatches.sort((a, b) -> a.getId().toString().compareTo(b.getId().toString()));
            Set<de.vvwt.slotopt.worker.types.PositionTuple> positions = new HashSet<>();
            for (Match m : lapMatches) {
                de.vvwt.slotopt.worker.types.PositionTuple pt1 =
                        positionByAvatarId.get(m.getMemberAvatar1Id());
                de.vvwt.slotopt.worker.types.PositionTuple pt2 =
                        positionByAvatarId.get(m.getMemberAvatar2Id());
                if (pt1 != null) positions.add(pt1);
                if (pt2 != null) positions.add(pt2);
                orderedMatches.add(m);
            }
            lapRows.add(new de.vvwt.slotopt.worker.types.RawRow(new ArrayList<>(positions)));
        }

        int lapCount = lapRows.size();
        int auditPhaseId = Math.abs(phaseId.hashCode());
        de.vvwt.slotopt.worker.types.RawPhaseDef raw =
                new de.vvwt.slotopt.worker.types.RawPhaseDef(auditPhaseId, lapCount, lapRows);
        de.vvwt.slotopt.worker.types.TransformResult tr =
                de.vvwt.slotopt.worker.types.StructuralFingerprint.transform(raw);
        de.vvwt.slotopt.worker.types.CanonicalPhaseDef canonical = tr.canonical();

        // denseIdsByRawRow: one entry per lap-row (not per match)
        var denseMap = DefaultPhaseToRawPhaseDefMapper.buildDenseIdMapping(raw);
        int[][] denseIdsByRawRow = new int[lapCount][];
        for (int i = 0; i < lapCount; i++) {
            List<de.vvwt.slotopt.worker.types.PositionTuple> positions = lapRows.get(i).positions();
            denseIdsByRawRow[i] = new int[positions.size()];
            for (int j = 0; j < positions.size(); j++) {
                Integer id = denseMap.get(positions.get(j));
                denseIdsByRawRow[i][j] = id != null ? id : 0;
            }
        }

        return new MappingResult(
                raw, canonical, canonical.avatarCount(), orderedMatches, denseIdsByRawRow);
    }
}
