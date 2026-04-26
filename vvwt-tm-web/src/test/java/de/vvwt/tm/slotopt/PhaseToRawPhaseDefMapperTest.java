package de.vvwt.tm.slotopt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.StructuralFingerprint;
import de.vvwt.slotopt.worker.types.TransformResult;
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

/** Unit tests for {@link PhaseToRawPhaseDefMapper} — covers AC8–AC11 and AC13 of story E04S02. */
@ExtendWith(MockitoExtension.class)
class PhaseToRawPhaseDefMapperTest {

    @Mock private TeamAvatarRepository teamAvatarRepository;

    @Mock private MatchRepository matchRepository;

    private PhaseToRawPhaseDefMapper mapper;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mapper = new PhaseToRawPhaseDefMapper(teamAvatarRepository, matchRepository);
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
        UUID unknownAvatarId = UUID.randomUUID();
        UUID knownAvatarId = avatars.get(0).getId();
        Match badMatch = buildMatch(phaseId, knownAvatarId, unknownAvatarId);

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
        List<Match> matches =
                List.of(buildMatch(phaseId, avatars.get(0).getId(), avatars.get(1).getId()));

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);

        MappingResult result = mapper.map(phaseId);

        // AC1: phaseId field = Math.abs(uuid.hashCode()) — must be non-negative
        assertThat(result.raw().phaseId()).isGreaterThanOrEqualTo(0);
        // And must be deterministic: same UUID → same value
        assertThat(result.raw().phaseId()).isEqualTo(Math.abs(phaseId.hashCode()));
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

    /** Builds all C(n, 2) match pairs for a list of avatars. */
    private List<Match> buildAllPairMatches(UUID phaseId, List<TeamAvatar> avatars) {
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < avatars.size(); i++) {
            for (int j = i + 1; j < avatars.size(); j++) {
                matches.add(buildMatch(phaseId, avatars.get(i).getId(), avatars.get(j).getId()));
            }
        }
        return matches;
    }

    /** Builds a single {@link Match} between two avatar IDs. */
    private Match buildMatch(UUID phaseId, UUID avatar1Id, UUID avatar2Id) {
        return new Match(
                UUID.randomUUID(),
                TENANT_ID,
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
}
