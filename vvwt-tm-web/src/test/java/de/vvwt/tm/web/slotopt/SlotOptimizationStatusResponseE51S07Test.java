package de.vvwt.tm.web.slotopt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit tests for {@link SlotOptimizationStatusResponse} E51S07 extension: {@code
 * lastJobState} field (AC-TEST-STATUS-ENDPOINT-RETURNS-LAST-JOB-STATE-RED,
 * AC-IMPL-STATUS-ENDPOINT-EXTENSION).
 *
 * <p>DEC-22 Iron Law Q-1a: tests written before production code change.
 */
class SlotOptimizationStatusResponseE51S07Test {

    @Test
    @DisplayName("idle() factory produces null lastJobState (no active job)")
    void idle_hasNullLastJobState() {
        SlotOptimizationStatusResponse r = SlotOptimizationStatusResponse.idle();
        assertThat(r.lastJobState()).isNull();
    }

    @Test
    @DisplayName("withLastJobState() factory produces response with lastJobState field")
    void withLastJobState_present() {
        SlotOptimizationStatusResponse r =
                SlotOptimizationStatusResponse.withLastJobState("slot_opt_running");
        assertThat(r.lastJobState()).isEqualTo("slot_opt_running");
    }

    @Test
    @DisplayName("withLastJobState(null) produces null field")
    void withLastJobState_null() {
        SlotOptimizationStatusResponse r = SlotOptimizationStatusResponse.withLastJobState(null);
        assertThat(r.lastJobState()).isNull();
    }

    @Test
    @DisplayName("running() factory carries lastJobState from parameter")
    void running_withLastJobState() {
        // Existing running() factory must be backward-compatible OR a new factory exists.
        // After E51S07: running() must include lastJobState in response.
        SlotOptimizationStatusResponse r =
                SlotOptimizationStatusResponse.runningWithJobState(
                        java.time.Instant.now(), 0.5, "slot_opt_running");
        assertThat(r.lastJobState()).isEqualTo("slot_opt_running");
        assertThat(r.state()).isEqualTo("running");
    }
}
