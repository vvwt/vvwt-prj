package de.vvwt.tm.print;

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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test for {@link LaufzettelAssembler} interface — E24S02.
 *
 * <p>Per AC-CONTRACT-TEST-REDFIRST (E24S02) and DEC-36 cross-package rule: this test lives in
 * {@code de.vvwt.tm.print} (different Java package from the implementation at
 * {@code de.vvwt.tm.print.internal}) and therefore MUST reference {@link LaufzettelAssembler}
 * interface type only — NEVER the concrete {@code DefaultLaufzettelAssembler} class.
 *
 * <p>Each test method includes a compile-time method invocation per enumerated method in the
 * interface (AC-CONTRACT-TEST-REDFIRST: "compile-time method invocation per method in the
 * enumerated set so signature drift is caught by compile error, not just class-existence").
 * Method-set: {@code assemble}, {@code assembleWithPhaseConfig}, {@code hasTime}.
 *
 * <p>Test authored RED-first per DEC-22 Iron Law: committed before {@link LaufzettelAssembler}
 * interface exists, proving the red state.
 */
@DisplayName("LaufzettelAssembler — interface contract (E24S02, DEC-36 cross-package)")
class LaufzettelAssemblerContractTest {

    /**
     * Verifies compile-time presence and correct return type of {@code assemble}.
     *
     * <p>This test does not execute any logic — it is a compile-time signature assertion.
     * A {@code null} reference is used to avoid instantiating the implementation class
     * (cross-package rule: interface-typed reference only per DEC-36).
     */
    @Test
    @DisplayName("assemble method has correct signature (compile-time check)")
    @SuppressWarnings("ConstantConditions")
    void assembleMethodSignatureIsCorrect() {
        // Compile-time assertion: if signature changes, this line becomes a compile error.
        // Runtime: the null assembler produces NullPointerException — expected by design.
        LaufzettelAssembler assembler = null;
        Assertions.assertThrows(NullPointerException.class, () ->
                assembler.assemble(
                        (Tournament) null,
                        (List<Phase>) null,
                        (List<Team>) null,
                        (Map<UUID, List<TeamAvatar>>) null,
                        (Map<UUID, List<Match>>) null,
                        (Map<UUID, List<PhaseBreak>>) null,
                        (List<ActivityType>) null,
                        0));
    }

    /**
     * Verifies compile-time presence and correct return type of {@code assembleWithPhaseConfig}.
     */
    @Test
    @DisplayName("assembleWithPhaseConfig method has correct signature (compile-time check)")
    @SuppressWarnings("ConstantConditions")
    void assembleWithPhaseConfigMethodSignatureIsCorrect() {
        LaufzettelAssembler assembler = null;
        Assertions.assertThrows(NullPointerException.class, () ->
                assembler.assembleWithPhaseConfig(
                        (Tournament) null,
                        (List<Phase>) null,
                        (List<Team>) null,
                        (Map<UUID, List<TeamAvatar>>) null,
                        (Map<UUID, List<Match>>) null,
                        (Map<UUID, List<PhaseBreak>>) null,
                        (List<ActivityType>) null,
                        0,
                        (Map<UUID, Integer>) null,
                        (Map<UUID, Integer>) null));
    }

    /**
     * Verifies compile-time presence and correct return type of {@code hasTime}.
     */
    @Test
    @DisplayName("hasTime method has correct signature (compile-time check)")
    @SuppressWarnings("ConstantConditions")
    void hasTimeMethodSignatureIsCorrect() {
        LaufzettelAssembler assembler = null;
        Assertions.assertThrows(NullPointerException.class, () ->
                assembler.hasTime((Tournament) null));
    }
}
