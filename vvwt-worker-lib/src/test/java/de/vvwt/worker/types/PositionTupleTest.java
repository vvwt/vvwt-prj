package de.vvwt.worker.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link PositionTuple} validation (AC11). */
class PositionTupleTest {

    @Test
    void validTuple_isCreated() {
        PositionTuple tuple = new PositionTuple(0, 0);
        assertThat(tuple.group()).isZero();
        assertThat(tuple.pos()).isZero();
    }

    @Test
    void negativeGroup_throwsIllegalArgument() {
        assertThatThrownBy(() -> new PositionTuple(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");
    }

    @Test
    void negativePos_throwsIllegalArgument() {
        assertThatThrownBy(() -> new PositionTuple(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pos");
    }
}
