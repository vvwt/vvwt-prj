package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftPreviewResult;
import de.vvwt.tm.tournament.draft.DraftSection;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.mockito.Mockito;

/**
 * AC-TEST-PROPERTY-LAP-INVARIANT-RED — jqwik {@code @Property} test for the field-aware lap formula
 * algebraic invariants (DEC-41 Clause B, named-algebraic-invariant form).
 *
 * <h2>Named invariants (two — Sufficiency and Tightness)</h2>
 *
 * <ul>
 *   <li><b>Sufficiency:</b> {@code totalLaps * effectivePerLap >= totalMatches} for all valid
 *       inputs.
 *   <li><b>Tightness:</b> {@code (totalLaps - 1) * effectivePerLap < totalMatches} whenever {@code
 *       totalLaps > 0}.
 * </ul>
 *
 * <h2>Input generators</h2>
 *
 * <ul>
 *   <li>{@code teamsPerGroup ∈ [1, 24]} — captures 1-team (0-match) edge case + large groups
 *   <li>{@code groupCount ∈ [1, 8]} — tournament group count
 *   <li>{@code fieldCount ∈ [1, 20]} — available fields (≥1 per AC-ERROR-HANDLING-FIELDCOUNT-CLAMP
 *       schema guarantee; 0 is clamped separately in unit tests)
 * </ul>
 *
 * <p>RED-first: written before {@code preview(DraftConfig, int, int)} exists (E48S10, DEC-22 Iron
 * Law). The test invokes the 3-arg interface which does not yet compile.
 *
 * @see DefaultDraftService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-41">DEC-41 — Spec-Anchored Test Reuse (observable form criterion d)</a>
 * @see <a href="E48S10">E48S10 — Field-Count-Aware Lap Formula</a>
 */
class DefaultDraftServiceLapInvariantTest {

    /**
     * Creates a minimal {@link DefaultDraftService} with Mockito stubs for all collaborators. Only
     * the {@code preview()} pure-computation path is exercised — no repository calls occur.
     */
    private static DefaultDraftService buildService() {
        return new DefaultDraftService(
                Mockito.mock(PhaseRepository.class),
                Mockito.mock(PhaseBreakRepository.class),
                Mockito.mock(TeamRepository.class),
                Mockito.mock(TeamAvatarRepository.class),
                Mockito.mock(PhasePreparationService.class),
                Mockito.mock(TournamentRepository.class),
                new ObjectMapper(),
                Mockito.mock(de.vvwt.tm.tournament.TimelineCalculationService.class)); // E48S12
    }

    /**
     * Named algebraic invariant: <b>Sufficiency</b> and <b>Tightness</b> of the lap formula.
     *
     * <p>For all valid combinations of {@code teamsPerGroup}, {@code groupCount}, and {@code
     * fieldCount}:
     *
     * <ul>
     *   <li>Sufficiency: {@code totalLaps * effectivePerLap >= totalMatches}
     *   <li>Tightness: {@code (totalLaps - 1) * effectivePerLap < totalMatches} when {@code
     *       totalLaps > 0}
     * </ul>
     *
     * <p>Definition: {@code effectivePerLap = max(1, min(floor(teamsPerGroup/2) * groupCount,
     * fieldCount))}.
     */
    @Property
    void lapInvariant_sufficiencyAndTightness(
            @ForAll @IntRange(min = 1, max = 24) int teamsPerGroup,
            @ForAll @IntRange(min = 1, max = 8) int groupCount,
            @ForAll @IntRange(min = 1, max = 20) int fieldCount) {

        DefaultDraftService service = buildService();

        // Build a config with one section whose groupCount matches the parameter
        DraftSection section =
                new DraftSection(
                        1, "team_number", groupCount, "roundRobin", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        // participating team count = teamsPerGroup * groupCount
        int participatingTeamCount = teamsPerGroup * groupCount;
        DraftPreviewResult result =
                service.preview(config, participatingTeamCount, fieldCount, null);

        int totalLaps = result.sections().get(0).getTotalLaps();
        int totalMatches = result.sections().get(0).getTotalMatches();

        // Compute effectivePerLap for assertion (mirrors formula in DefaultDraftService)
        int teamConflictPerLap = (teamsPerGroup / 2) * groupCount;
        int effectivePerLap = Math.max(1, Math.min(teamConflictPerLap, fieldCount));

        // Named invariant 1: Sufficiency
        assertThat((long) totalLaps * effectivePerLap)
                .as(
                        "Sufficiency: totalLaps(%d) * effectivePerLap(%d) >= totalMatches(%d) "
                                + "[teamsPerGroup=%d, groupCount=%d, fieldCount=%d]",
                        totalLaps,
                        effectivePerLap,
                        totalMatches,
                        teamsPerGroup,
                        groupCount,
                        fieldCount)
                .isGreaterThanOrEqualTo(totalMatches);

        // Named invariant 2: Tightness (only when totalLaps > 0)
        if (totalLaps > 0) {
            assertThat((long) (totalLaps - 1) * effectivePerLap)
                    .as(
                            "Tightness: (totalLaps-1)(%d) * effectivePerLap(%d) < totalMatches(%d) "
                                    + "[teamsPerGroup=%d, groupCount=%d, fieldCount=%d]",
                            totalLaps - 1,
                            effectivePerLap,
                            totalMatches,
                            teamsPerGroup,
                            groupCount,
                            fieldCount)
                    .isLessThan(totalMatches);
        }
    }
}
