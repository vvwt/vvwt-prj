// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawRow;
import de.vvwt.slotopt.worker.types.StructuralFingerprint;
import de.vvwt.slotopt.worker.types.TransformResult;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultPhaseToRawPhaseDefMapper} (via {@link PhaseToRawPhaseDefMapper}
 * interface) — covers AC8–AC11 and AC13 of story E04S02, and E54S02 RED-first tests for RawRow=Lap
 * refactor per DEC-61 Clause B.
 *
 * @see DefaultPhaseToRawPhaseDefMapper
 * @see PhaseToRawPhaseDefMapper
 * @since E57S01 (moved from slotopt.PhaseToRawPhaseDefMapperTest to slotopt.internal per DEC-58
 *     interface extraction)
 */
@ExtendWith(MockitoExtension.class)
class DefaultPhaseToRawPhaseDefMapperTest {

    @Mock private TeamAvatarRepository teamAvatarRepository;

    @Mock private MatchRepository matchRepository;

    private PhaseToRawPhaseDefMapper mapper;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mapper = new DefaultPhaseToRawPhaseDefMapper(teamAvatarRepository, matchRepository);
    }

    // -------------------------------------------------------------------------
    // AC11 — forward mapping: 6 avatars, 15 matches (C(6,2) all-play-all)
    // -------------------------------------------------------------------------

    @Test
    void map_forwardMapping_sixAvatarsFifteenMatches() {
        UUID phaseId = UUID.randomUUID();

        // Build 6 TeamAvatars with distinct (groupNumber, groupPosition) tuples
        // groupNumber in 1..2, groupPosition in 1..3 (6 = 2 groups × 3 positions)
        List<TeamAvatar> avatars =
                buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}, {1, 3}, {2, 1}, {2, 2}, {2, 3}});

        // Build 15 matches (C(6,2) = 15) — all pairs
        List<Match> matches = buildAllPairMatches(phaseId, avatars);

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);

        MappingResult result = mapper.map(phaseId);

        // AC11: rowCount = 15
        assertThat(result.raw().rowCount()).isEqualTo(15);

        // AC11: each row has exactly 2 PositionTuples
        for (int i = 0; i < result.raw().rows().size(); i++) {
            assertThat(result.raw().rows().get(i).positions()).hasSize(2);
        }

        // AC11: N = 6
        assertThat(result.avatarCount()).isEqualTo(6);

        // AC11: PositionTuples use (groupNumber, groupPosition) directly per DEC-9
        // Verify all 6 distinct tuples appear across the rows
        java.util.Set<PositionTuple> allTuples = new java.util.HashSet<>();
        for (int i = 0; i < result.raw().rows().size(); i++) {
            allTuples.addAll(result.raw().rows().get(i).positions());
        }
        assertThat(allTuples).hasSize(6);

        // AC3: matchOrder aligned with rows — same size as matches
        assertThat(result.matchOrder()).hasSize(15);

        // denseIdsByRawRow: each entry is a 2-element array
        assertThat(result.denseIdsByRawRow().length).isEqualTo(15);
        for (int[] ids : result.denseIdsByRawRow()) {
            assertThat(ids.length).isEqualTo(2);
            // Dense IDs in [0, N-1]
            assertThat(ids[0]).isBetween(0, 5);
            assertThat(ids[1]).isBetween(0, 5);
            // Two different avatars per match
            assertThat(ids[0]).isNotEqualTo(ids[1]);
        }
    }

    // -------------------------------------------------------------------------
    // AC13 — determinism: same phase data → same RawPhaseDef (identical fingerprint)
    // -------------------------------------------------------------------------

    @Test
    void map_determinism_sameInputProducesSameFingerprint() {
        UUID phaseId = UUID.randomUUID();
        List<TeamAvatar> avatars =
                buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}, {1, 3}, {2, 1}, {2, 2}, {2, 3}});
        List<Match> matches = buildAllPairMatches(phaseId, avatars);

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);

        MappingResult first = mapper.map(phaseId);
        MappingResult second = mapper.map(phaseId);

        // AC13: identical RawPhaseDef → same StructuralFingerprint
        TransformResult fp1 = StructuralFingerprint.transform(first.raw());
        TransformResult fp2 = StructuralFingerprint.transform(second.raw());

        assertThat(fp1.fingerprint()).isEqualTo(fp2.fingerprint());

        // Verify row order is identical (deterministic UUID sort)
        for (int i = 0; i < first.raw().rows().size(); i++) {
            assertThat(first.raw().rows().get(i).positions())
                    .isEqualTo(second.raw().rows().get(i).positions());
        }
    }

    // -------------------------------------------------------------------------
    // AC8 — error: no matches → IllegalStateException
    // -------------------------------------------------------------------------

    @Test
    void map_throwsISE_whenNoMatchesExist() {
        UUID phaseId = UUID.randomUUID();
        List<TeamAvatar> avatars = buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}});

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> mapper.map(phaseId))
                .withMessageContaining("No matches found")
                .withMessageContaining(phaseId.toString());
    }

    // -------------------------------------------------------------------------
    // AC9 — error: no avatars → IllegalStateException
    // -------------------------------------------------------------------------

    @Test
    void map_throwsISE_whenNoAvatarsExist() {
        UUID phaseId = UUID.randomUUID();

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> mapper.map(phaseId))
                .withMessageContaining("No TeamAvatars found")
                .withMessageContaining(phaseId.toString());
    }

    // -------------------------------------------------------------------------
    // AC10 — error: match references unknown avatar → IllegalStateException
    // -------------------------------------------------------------------------

    @Test
    void map_throwsISE_whenMatchReferencesUnknownAvatar() {
        UUID phaseId = UUID.randomUUID();
        List<TeamAvatar> avatars = buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}});

        // Build a match referencing an avatar NOT in the loaded set
        // Post-E54S02: must have lapNumber set so the validator reaches the avatar check (DEC-61
        // Clause B)
        UUID unknownAvatarId = UUID.randomUUID();
        UUID knownAvatarId = avatars.get(0).getId();
        Match badMatch = buildMatchWithLap(phaseId, knownAvatarId, unknownAvatarId, 1);

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(badMatch));

        assertThatIllegalStateException()
                .isThrownBy(() -> mapper.map(phaseId))
                .withMessageContaining(unknownAvatarId.toString())
                .withMessageContaining(badMatch.getId().toString());
    }

    // -------------------------------------------------------------------------
    // AC1 — phaseId derivation: audit-only, deterministic from UUID
    // -------------------------------------------------------------------------

    @Test
    void map_phaseIdField_isDeterministicFromUUID() {
        UUID phaseId = UUID.randomUUID();
        List<TeamAvatar> avatars = buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}});
        // Post-E54S02: matches must have lapNumber set (DEC-61 Clause B)
        List<Match> matches =
                List.of(
                        buildMatchWithLap(
                                phaseId, avatars.get(0).getId(), avatars.get(1).getId(), 1));

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);

        MappingResult result = mapper.map(phaseId);

        // AC1: phaseId field = Math.abs(uuid.hashCode()) — must be non-negative
        assertThat(result.raw().phaseId()).isGreaterThanOrEqualTo(0);
        // And must be deterministic: same UUID → same value
        assertThat(result.raw().phaseId()).isEqualTo(Math.abs(phaseId.hashCode()));
    }

    // -------------------------------------------------------------------------
    // E54S02 RED-first tests — DEC-61 Clause B: RawRow = Lap (per DEC-22 Iron Law)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-MAPPER-ROWS-EQUALS-LAPCOUNT-RED (E54S02):
     *
     * <p>A phase with 30 matches across 10 distinct lap_number values must produce
     * raw.rows().size() == 10 (one RawRow per lap), NOT 30 (one per match).
     *
     * <p>RED against current code: current map() builds one RawRow per match → rows.size() = 30.
     */
    @Test
    void map_rowsSizeEqualsLapCount_notMatchCount_E54S02() {
        UUID phaseId = UUID.randomUUID();
        // 6 avatars in 1 group, 3 fields, 10 laps → 30 matches total
        List<TeamAvatar> avatars =
                buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}, {1, 3}, {2, 1}, {2, 2}, {2, 3}});
        // Build 30 matches: 3 per lap, laps 1..10 (1-based per DEC-60)
        List<Match> matches = buildMatchesWithLaps(phaseId, avatars, 10, 3);

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);

        MappingResult result = mapper.map(phaseId);

        // AC-TEST-MAPPER-ROWS-EQUALS-LAPCOUNT-RED: rows.size() == lapCount (10), NOT matchCount
        // (30)
        assertThat(result.raw().rows())
                .as(
                        "AC-TEST-MAPPER-ROWS-EQUALS-LAPCOUNT-RED (E54S02 DEC-61 Clause B):"
                                + " rows.size() must equal lapCount=10, not matchCount=30")
                .hasSize(10);

        // matchOrder still has all 30 matches
        assertThat(result.matchOrder()).hasSize(30);
    }

    /**
     * AC-TEST-MAPPER-ROW-POSITIONS-UNION-RED (E54S02):
     *
     * <p>For a lap with 3 matches and 6 distinct avatars (non-overlapping), the lap-row's positions
     * must be the union of all 6 PositionTuples (not just 2 from one match).
     *
     * <p>RED against current code: current code builds one RawRow per match with exactly 2
     * PositionTuples → positions.size() == 2 per row, not 6.
     */
    @Test
    void map_rowPositionsIsUnionOfAvatarsInLap_E54S02() {
        UUID phaseId = UUID.randomUUID();
        // 6 avatars, all in group 1, 3 fields → 3 matches per lap
        List<TeamAvatar> avatars =
                buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}, {1, 3}, {1, 4}, {1, 5}, {1, 6}});
        // 3 matches in lap 1: (a0,a1), (a2,a3), (a4,a5) — 6 distinct avatars in the lap
        UUID a0 = avatars.get(0).getId();
        UUID a1 = avatars.get(1).getId();
        UUID a2 = avatars.get(2).getId();
        UUID a3 = avatars.get(3).getId();
        UUID a4 = avatars.get(4).getId();
        UUID a5 = avatars.get(5).getId();
        List<Match> matches = new ArrayList<>();
        matches.add(buildMatchWithLap(phaseId, a0, a1, 1));
        matches.add(buildMatchWithLap(phaseId, a2, a3, 1));
        matches.add(buildMatchWithLap(phaseId, a4, a5, 1));

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);

        MappingResult result = mapper.map(phaseId);

        // After refactor: 1 row for lap 1 with 6 PositionTuples (union of all 3 matches' avatars)
        assertThat(result.raw().rows())
                .as("AC-TEST-MAPPER-ROW-POSITIONS-UNION-RED: 1 lap → 1 row")
                .hasSize(1);
        RawRow lapRow = result.raw().rows().get(0);
        assertThat(lapRow.positions())
                .as(
                        "AC-TEST-MAPPER-ROW-POSITIONS-UNION-RED: positions must be union of all 6"
                            + " avatars in the lap (3 matches × 2 avatars each, non-overlapping)")
                .hasSize(6);
    }

    /**
     * AC-TEST-MAPPER-ROW-ORDER-ASCENDING-LAP-RED (E54S02):
     *
     * <p>Input matches in shuffled lap_number order → output rows ordered ascending by lap_number.
     *
     * <p>RED against current code: current code sorts by UUID (not by lap_number) → row order is
     * not guaranteed to be ascending by lap_number.
     */
    @Test
    void map_rowsOrderedAscendingByLapNumber_E54S02() {
        UUID phaseId = UUID.randomUUID();
        // 2 avatars, 3 laps (each lap has 1 match), supplied in lap order 3, 1, 2
        List<TeamAvatar> avatars = buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}});
        UUID a0 = avatars.get(0).getId();
        UUID a1 = avatars.get(1).getId();
        // Create matches with laps in shuffled order: 3, 1, 2
        List<Match> matches = new ArrayList<>();
        matches.add(buildMatchWithLap(phaseId, a0, a1, 3));
        matches.add(buildMatchWithLap(phaseId, a0, a1, 1));
        matches.add(buildMatchWithLap(phaseId, a0, a1, 2));

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);

        MappingResult result = mapper.map(phaseId);

        // After refactor: 3 rows, ordered by ascending lap_number
        // To verify order: row 0 = lap 1, row 1 = lap 2, row 2 = lap 3
        // We verify this by checking the positions in each row correspond to the avatars from the
        // correctly-ordered lap.
        assertThat(result.raw().rows())
                .as("AC-TEST-MAPPER-ROW-ORDER-ASCENDING-LAP-RED: must have 3 rows (one per lap)")
                .hasSize(3);

        // Row order: lap 1 first, lap 2 second, lap 3 third
        // We can verify positional ordering by confirming rows are distinct and there are exactly 3
        // Detailed ordering is validated by checking matchOrder alignment with rows
        // (matchOrder must be in same ascending-lap-number order as rows)
        // Verify matchOrder is sorted by lap number ascending
        List<Match> order = result.matchOrder();
        for (int i = 0; i < order.size() - 1; i++) {
            assertThat(order.get(i).getLapNumber())
                    .as(
                            "AC-TEST-MAPPER-ROW-ORDER-ASCENDING-LAP-RED: matchOrder[%d].lapNumber"
                                    + " must be <= matchOrder[%d].lapNumber",
                            i, i + 1)
                    .isLessThanOrEqualTo(order.get(i + 1).getLapNumber());
        }
    }

    /**
     * AC-TEST-MAPPER-NULL-LAP-NUMBER-THROWS-RED (E54S02):
     *
     * <p>If any input Match has lap_number = null at Mapper invocation time, the Mapper throws
     * {@link IllegalStateException} with a message naming the offending match ID.
     *
     * <p>RED against current code: current code does not check for null lap_number at all (it only
     * checks for null avatar references) → no exception thrown.
     */
    @Test
    void map_throwsISE_whenMatchHasNullLapNumber_E54S02() {
        UUID phaseId = UUID.randomUUID();
        List<TeamAvatar> avatars = buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}});
        UUID a0 = avatars.get(0).getId();
        UUID a1 = avatars.get(1).getId();
        // Match with null lap_number (pre-L2 state — Mapper must reject it)
        Match badMatch = buildMatch(phaseId, a0, a1); // lapNumber = null (default)

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(badMatch));

        assertThatIllegalStateException()
                .as("AC-TEST-MAPPER-NULL-LAP-NUMBER-THROWS-RED (E54S02)")
                .isThrownBy(() -> mapper.map(phaseId))
                .withMessageContaining(badMatch.getId().toString());
    }

    /**
     * AC-TEST-EMPTY-LAP-LIST-EDGE-RED (E54S02):
     *
     * <p>If the Mapper is invoked on a phase with zero matches, the existing ISE throw (lines
     * 110-115) is preserved. This is a GREEN test after refactor (existing behavior preserved).
     *
     * <p>Included here to confirm the throw message is unchanged post-refactor.
     */
    @Test
    void map_throwsISE_whenNoMatchesExist_E54S02_edgePreserved() {
        UUID phaseId = UUID.randomUUID();
        List<TeamAvatar> avatars = buildAvatars(phaseId, new int[][] {{1, 1}, {1, 2}});

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of());

        assertThatIllegalStateException()
                .as("AC-TEST-EMPTY-LAP-LIST-EDGE-RED: existing no-match ISE must be preserved")
                .isThrownBy(() -> mapper.map(phaseId))
                .withMessageContaining("No matches found")
                .withMessageContaining(phaseId.toString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds a list of {@link TeamAvatar}s with the given (groupNumber, groupPosition) pairs. */
    private List<TeamAvatar> buildAvatars(UUID phaseId, int[][] groupPos) {
        List<TeamAvatar> result = new ArrayList<>();
        UUID tournamentId = UUID.randomUUID();
        for (int[] gp : groupPos) {
            TeamAvatar avatar =
                    new TeamAvatar(
                            UUID.randomUUID(),
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

    /**
     * Builds all C(n, 2) match pairs for a list of avatars, each in a distinct 1-based lap
     * (post-E54S02: matches must have lapNumber set before Mapper invocation).
     */
    private List<Match> buildAllPairMatches(UUID phaseId, List<TeamAvatar> avatars) {
        List<Match> matches = new ArrayList<>();
        int lap = 1;
        for (int i = 0; i < avatars.size(); i++) {
            for (int j = i + 1; j < avatars.size(); j++) {
                matches.add(
                        buildMatchWithLap(
                                phaseId, avatars.get(i).getId(), avatars.get(j).getId(), lap++));
            }
        }
        return matches;
    }

    /**
     * Builds a single {@link Match} between two avatar IDs with {@code lapNumber = null}.
     *
     * <p>Only used in tests that specifically test null-lapNumber rejection
     * (AC-TEST-MAPPER-NULL-LAP-NUMBER). Do NOT use this for tests that call {@link
     * PhaseToRawPhaseDefMapper#map(UUID)} — those must use {@link #buildMatchWithLap} post-E54S02
     * (DEC-61 Clause B).
     */
    private Match buildMatch(UUID phaseId, UUID avatar1Id, UUID avatar2Id) {
        return new Match(
                UUID.randomUUID(),
                TOURNAMENT_ID,
                phaseId,
                avatar1Id,
                avatar2Id,
                MatchState.OPEN.getLegacyCode(),
                1,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Builds a single {@link Match} with a specific 1-based lap_number (E54S02 RED-first helpers).
     *
     * @param phaseId the phase UUID
     * @param avatar1Id first player avatar UUID
     * @param avatar2Id second player avatar UUID
     * @param lapNumber the 1-based lap number to set (per DEC-60)
     */
    private Match buildMatchWithLap(UUID phaseId, UUID avatar1Id, UUID avatar2Id, int lapNumber) {
        Match m =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        phaseId,
                        avatar1Id,
                        avatar2Id,
                        MatchState.OPEN.getLegacyCode(),
                        1,
                        lapNumber,
                        null,
                        null,
                        null,
                        null,
                        null);
        return m;
    }

    /**
     * Builds {@code lapCount * matchesPerLap} matches with 1-based lap_numbers in [1..lapCount].
     *
     * <p>Pairs avatars round-robin across the given lapCount laps. Uses distinct avatar-pair
     * rotations per lap so positions union can be verified. If avatars.size() *
     * (avatars.size()-1)/2 is less than lapCount * matchesPerLap, avatars are reused (positional
     * tuples remain distinct because PositionTuple is structural, not identity-based).
     *
     * @param phaseId the phase UUID
     * @param avatars the avatar list to draw pairs from
     * @param lapCount number of distinct laps (1-based: laps 1..lapCount)
     * @param matchesPerLap number of matches per lap
     * @return the flat list of all matches with lap_number set
     */
    private List<Match> buildMatchesWithLaps(
            UUID phaseId, List<TeamAvatar> avatars, int lapCount, int matchesPerLap) {
        List<Match> result = new ArrayList<>();
        int avatarCount = avatars.size();
        int pairIdx = 0;
        // Pre-compute all avatar pairs
        List<UUID[]> pairs = new ArrayList<>();
        for (int i = 0; i < avatarCount; i++) {
            for (int j = i + 1; j < avatarCount; j++) {
                pairs.add(new UUID[] {avatars.get(i).getId(), avatars.get(j).getId()});
            }
        }
        // Distribute matchesPerLap pairs per lap, cycling pairs if needed
        for (int lap = 1; lap <= lapCount; lap++) {
            for (int m = 0; m < matchesPerLap; m++) {
                UUID[] pair = pairs.get(pairIdx % pairs.size());
                pairIdx++;
                result.add(buildMatchWithLap(phaseId, pair[0], pair[1], lap));
            }
        }
        return result;
    }
}
