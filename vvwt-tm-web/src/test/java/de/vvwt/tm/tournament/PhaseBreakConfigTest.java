package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * TDD tests for PhaseBreakConfig (E21S11).
 *
 * <p>Written RED (against non-existent production class) before implementation. Covers
 * AC-TDD-PhaseBreakConfig and AC-VALIDATION-PHASEBREAKCONFIG.
 *
 * @see PhaseBreakConfig
 */
class PhaseBreakConfigTest {

    @Test
    void constructor_validArguments_fieldsAreAccessible() {
        PhaseBreakConfig config = new PhaseBreakConfig(2, 10, "Mittagspause");

        assertThat(config.afterLapNumber()).isEqualTo(2);
        assertThat(config.durationMinutes()).isEqualTo(10);
        assertThat(config.label()).isEqualTo("Mittagspause");
    }

    @Test
    void constructor_nullLabel_isPermitted() {
        PhaseBreakConfig config = new PhaseBreakConfig(1, 5, null);
        assertThat(config.label()).isNull();
    }

    @Test
    void equality_twoIdenticalInstances_areEqual() {
        PhaseBreakConfig a = new PhaseBreakConfig(1, 5, "Break");
        PhaseBreakConfig b = new PhaseBreakConfig(1, 5, "Break");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void hashCode_twoIdenticalInstances_haveEqualHashCode() {
        PhaseBreakConfig a = new PhaseBreakConfig(1, 5, "Break");
        PhaseBreakConfig b = new PhaseBreakConfig(1, 5, "Break");
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equality_differentInstances_areNotEqual() {
        PhaseBreakConfig a = new PhaseBreakConfig(1, 5, "Break");
        PhaseBreakConfig b = new PhaseBreakConfig(2, 5, "Break");
        assertThat(a).isNotEqualTo(b);
    }

    // --- AC-VALIDATION-PHASEBREAKCONFIG ---

    @Test
    void constructor_negativeDurationMinutes_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new PhaseBreakConfig(1, -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationMinutes");
    }

    @Test
    void constructor_zeroDurationMinutes_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new PhaseBreakConfig(1, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationMinutes");
    }

    @Test
    void constructor_zeroAfterLapNumber_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new PhaseBreakConfig(0, 5, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("afterLapNumber");
    }

    @Test
    void constructor_negativeAfterLapNumber_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new PhaseBreakConfig(-1, 5, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("afterLapNumber");
    }
}
