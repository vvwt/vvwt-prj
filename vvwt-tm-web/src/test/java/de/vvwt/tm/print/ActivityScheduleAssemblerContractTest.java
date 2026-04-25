package de.vvwt.tm.print;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.Tournament;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Contract test for {@link ActivityScheduleAssembler} — cross-package interface typing (DEC-36,
 * E24S03 AC-CONTRACT-TEST-REDFIRST, AC-DEC-36-CONTRACT-TEST).
 *
 * <p>Types the subject as the PUBLIC INTERFACE {@code ActivityScheduleAssembler}, not the
 * implementation class {@code DefaultActivityScheduleAssembler}. Per DEC-36: cross-package tests
 * must reference the interface, not the concrete type.
 *
 * <p>This test is in {@code de.vvwt.tm.print} (different package from the impl at
 * {@code de.vvwt.tm.print.internal}) — the DEC-36 cross-package rule applies.
 */
@ExtendWith(MockitoExtension.class)
class ActivityScheduleAssemblerContractTest {

    /** Subject typed as the PUBLIC INTERFACE per DEC-36. */
    @Mock
    private ActivityScheduleAssembler assembler;

    @Test
    void assemble_methodSignatureCompiles() {
        // Compile-time verification that the interface exposes the correct method signature
        // with all 8 parameters (AC-CONTRACT-TEST-REDFIRST: compile-time method invocation
        // per enumerated method-set).
        Tournament tournament = null;
        List<Phase> phases = List.of();
        List<Team> teams = List.of();
        Map<UUID, List<TeamAvatar>> avatarsByPhase = Map.of();
        Map<UUID, List<Match>> matchesByPhase = Map.of();
        Map<UUID, List<PhaseBreak>> breaksByPhase = Map.of();
        List<ActivityType> activityTypes = List.of();
        ActivityType targetType = null;

        // Invoke via interface — this line MUST compile for the AC to be satisfied.
        // The mock returns null by default; we only test that the call compiles and the
        // return type is ActivityScheduleModel.
        ActivityScheduleModel result =
                assembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        breaksByPhase,
                        activityTypes,
                        targetType);

        // Result is null (mock default) — this test is a compile-time contract gate.
        // Behavioral tests are in DefaultActivityScheduleAssemblerTest (same-package, white-box).
        assertThat(result).isNull(); // Mockito mock returns null by default
    }

    @Test
    void assemble_returnTypeIsActivityScheduleModel() {
        // Verify the return type is ActivityScheduleModel (interface contract check).
        // The mock returns null by default — we just verify the compile-time return type.
        ActivityScheduleModel result =
                assembler.assemble(null, List.of(), List.of(), Map.of(), Map.of(), Map.of(),
                        List.of(), null);
        // Return type is ActivityScheduleModel — if this compiles, the interface contract is satisfied.
        assertThat(result).isNull();
    }
}
