package de.vvwt.slotopt.standalone;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Context-load test for OptimizerWorkerMain.
 *
 * <p>TDD Iron Law (DEC-22): written in RED state before OptimizerWorkerMain class exists. GREEN
 * state is reached when the bare stub class is created in E41S01; the full @Command skeleton is
 * added in E41S02.
 *
 * <p>This test is the canonical first test for the vvwt-slotopt-standalone-worker module per
 * AC-FIRST-CONTEXT-LOAD-TEST (E41S01).
 */
class OptimizerWorkerMainContextLoadTest {

    @Test
    @DisplayName("OptimizerWorkerMain class can be loaded via reflection")
    void optimizerWorkerMain_classIsLoadable() {
        assertThatCode(() -> Class.forName("de.vvwt.slotopt.standalone.OptimizerWorkerMain"))
                .doesNotThrowAnyException();
    }
}
