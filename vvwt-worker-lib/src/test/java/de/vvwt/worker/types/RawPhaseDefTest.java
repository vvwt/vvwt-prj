package de.vvwt.worker.types;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RawPhaseDef} validation (AC11). */
class RawPhaseDefTest {

    private static RawRow row(int group, int pos) {
        return new RawRow(List.of(new PositionTuple(group, pos)));
    }

    @Test
    void negativeRowCount_throwsIllegalArgument() {
        assertThatThrownBy(() -> new RawPhaseDef(0, -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rowCount");
    }

    @Test
    void rowCountMismatch_throwsIllegalArgument() {
        assertThatThrownBy(() -> new RawPhaseDef(0, 2, List.of(row(0, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rowCount");
    }

    @Test
    void matchingRowCount_isAccepted() {
        RawPhaseDef def = new RawPhaseDef(1, 1, List.of(row(0, 0)));
        // rowCount == rows.size() — no exception expected
        assert def.rowCount() == 1;
    }
}
