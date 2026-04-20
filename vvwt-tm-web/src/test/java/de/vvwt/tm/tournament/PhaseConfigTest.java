package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TDD tests for PhaseConfig (E21S11).
 *
 * <p>Written RED (against non-existent production class) before implementation. Covers
 * AC-TDD-PhaseConfig and AC-VALIDATION-PHASECONFIG.
 *
 * @see PhaseConfig
 */
class PhaseConfigTest {

    private static final List<PhaseBreakConfig> NO_BREAKS = Collections.emptyList();

    @Test
    void constructor_validArguments_fieldsAreAccessible() {
        PhaseConfig config = new PhaseConfig(1, 3, 20, 5, NO_BREAKS);

        assertThat(config.phaseNumber()).isEqualTo(1);
        assertThat(config.lapCount()).isEqualTo(3);
        assertThat(config.lapTimeMinutes()).isEqualTo(20);
        assertThat(config.lapBreakMinutes()).isEqualTo(5);
        assertThat(config.phaseBreaks()).isEmpty();
    }

    @Test
    void constructor_zeroLapCount_isPermitted() {
        // A zero-lap phase produces a single marker entry (AC7 from story)
        PhaseConfig config = new PhaseConfig(1, 0, 20, 0, NO_BREAKS);
        assertThat(config.lapCount()).isZero();
    }

    @Test
    void constructor_zeroLapBreakMinutes_isPermitted() {
        PhaseConfig config = new PhaseConfig(1, 3, 20, 0, NO_BREAKS);
        assertThat(config.lapBreakMinutes()).isZero();
    }

    @Test
    void equality_twoIdenticalInstances_areEqual() {
        PhaseConfig a = new PhaseConfig(1, 3, 20, 5, NO_BREAKS);
        PhaseConfig b = new PhaseConfig(1, 3, 20, 5, NO_BREAKS);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void hashCode_twoIdenticalInstances_haveEqualHashCode() {
        PhaseConfig a = new PhaseConfig(1, 3, 20, 5, NO_BREAKS);
        PhaseConfig b = new PhaseConfig(1, 3, 20, 5, NO_BREAKS);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equality_differentInstances_areNotEqual() {
        PhaseConfig a = new PhaseConfig(1, 3, 20, 5, NO_BREAKS);
        PhaseConfig b = new PhaseConfig(1, 4, 20, 5, NO_BREAKS);
        assertThat(a).isNotEqualTo(b);
    }

    // --- AC-VALIDATION-PHASECONFIG ---

    @Test
    void constructor_negativeLapCount_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new PhaseConfig(1, -1, 20, 5, NO_BREAKS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lapCount");
    }

    @Test
    void constructor_negativeLapTimeMinutes_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new PhaseConfig(1, 3, -1, 5, NO_BREAKS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lapTimeMinutes");
    }

    @Test
    void constructor_zeroLapTimeWhenLapCountPositive_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new PhaseConfig(1, 3, 0, 5, NO_BREAKS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lapTimeMinutes");
    }

    @Test
    void constructor_negativeLapBreakMinutes_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new PhaseConfig(1, 3, 20, -1, NO_BREAKS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lapBreakMinutes");
    }

    @Test
    void constructor_nullPhaseBreaks_throwsNullPointerException() {
        assertThatThrownBy(() -> new PhaseConfig(1, 3, 20, 5, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_withPhaseBreaks_fieldsAreAccessible() {
        List<PhaseBreakConfig> breaks = List.of(new PhaseBreakConfig(1, 10, "Pause"));
        PhaseConfig config = new PhaseConfig(1, 3, 20, 5, breaks);
        assertThat(config.phaseBreaks()).hasSize(1);
    }
}
