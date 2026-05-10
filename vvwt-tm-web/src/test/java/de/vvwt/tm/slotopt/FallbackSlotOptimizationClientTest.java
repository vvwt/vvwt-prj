package de.vvwt.tm.slotopt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link FallbackSlotOptimizationClient}.
 *
 * <p>E53S09 RED-first tests (per DEC-22 Iron Law + DEC-60 D-1):
 *
 * <ul>
 *   <li>AC-TEST-FALLBACK-WRITES-1-BASED-FIELDNUMBER-RED — asserts fieldNumber ∈ [1..K]; 0 is
 *       forbidden per DEC-60 D-1.
 * </ul>
 *
 * <p>RED before production change: line 95 writes {@code idx % fieldCount} (0-based 0..K-1) →
 * MIN(fieldNumber) = 0 → FAIL.
 *
 * @see FallbackSlotOptimizationClient
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-60">DEC-60 D-1 — L2/Fallback emit 1-based fieldNumber</a>
 * @see <a href="E53S09">E53S09 — 1-based fieldNumber migration story</a>
 */
@ExtendWith(MockitoExtension.class)
class FallbackSlotOptimizationClientTest {

    @Mock private MatchRepository matchRepository;

    private FallbackSlotOptimizationClient fallback;

    private static final int FIELD_COUNT = 3;
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        fallback = new FallbackSlotOptimizationClient(matchRepository, FIELD_COUNT);
    }

    // =========================================================================
    // AC-TEST-FALLBACK-WRITES-1-BASED-FIELDNUMBER-RED (E53S09)
    //
    // Given: 9 matches (3 laps × 3 fields), fieldCount=3.
    // After optimize(phaseId): all fieldNumbers must be in [1..3] (1-based per DEC-60 D-1).
    // MIN(fieldNumber) = 1, MAX(fieldNumber) = 3.
    //
    // RED before production change: line 95 `idx % fieldCount` → MIN=0 → FAIL.
    // =========================================================================

    @Test
    void optimize_writesOneBased_fieldNumbers_notZeroBased() {
        // AC-TEST-FALLBACK-WRITES-1-BASED-FIELDNUMBER-RED (DEC-60 D-1, DEC-22 RED-first)
        UUID phaseId = UUID.randomUUID();
        List<Match> matches = buildMatches(phaseId, 9);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

        fallback.optimize(phaseId);

        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        org.mockito.Mockito.verify(matchRepository, org.mockito.Mockito.times(9)).save(captor.capture());
        List<Match> saved = captor.getAllValues();

        int minField = saved.stream().mapToInt(Match::getFieldNumber).min().orElse(-1);
        assertThat(minField)
                .as(
                        "AC-TEST-FALLBACK-WRITES-1-BASED-FIELDNUMBER-RED: MIN(fieldNumber) must"
                                + " be 1 (1-based per DEC-60 D-1); 0 is forbidden (E53S09)")
                .isGreaterThanOrEqualTo(1);

        int maxField = saved.stream().mapToInt(Match::getFieldNumber).max().orElse(-1);
        assertThat(maxField)
                .as("MAX(fieldNumber) must be ≤ K=3 (1-based: fields 1..3)")
                .isLessThanOrEqualTo(FIELD_COUNT);
    }

    @Test
    void optimize_writesOneBased_lapNumbers_notZeroBased() {
        // Regression-guard: lapNumber is 1-based per E53S06 (not part of E53S09 scope but
        // verifying the pattern is consistent for both coordinates)
        UUID phaseId = UUID.randomUUID();
        List<Match> matches = buildMatches(phaseId, 6);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

        fallback.optimize(phaseId);

        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        org.mockito.Mockito.verify(matchRepository, org.mockito.Mockito.times(6)).save(captor.capture());
        List<Match> saved = captor.getAllValues();

        int minLap = saved.stream().mapToInt(Match::getLapNumber).min().orElse(-1);
        assertThat(minLap)
                .as("MIN(lapNumber) must be ≥ 1 (1-based per E53S06)")
                .isGreaterThanOrEqualTo(1);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static List<Match> buildMatches(UUID phaseId, int count) {
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            matches.add(
                    new Match(
                            UUID.randomUUID(),
                            TOURNAMENT_ID,
                            phaseId,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            MatchState.OPEN.getLegacyCode(),
                            1,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));
        }
        return matches;
    }
}
