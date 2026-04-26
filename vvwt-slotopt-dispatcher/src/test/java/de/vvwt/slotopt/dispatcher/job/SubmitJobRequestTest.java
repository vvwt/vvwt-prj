package de.vvwt.slotopt.dispatcher.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubmitJobRequest}.
 *
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S07). Written before production
 * class.
 *
 * <p>Story: E37S07; AC-SUBMIT-JOB-DTOs
 */
class SubmitJobRequestTest {

    @Test
    void constructorPreservesFields() {
        RawPhaseDef phase =
                new RawPhaseDef(
                        1,
                        1,
                        List.of(
                                new RawRow(
                                        List.of(
                                                new PositionTuple(0, 0),
                                                new PositionTuple(0, 1)))));
        CanonicalPhaseDef canonical =
                new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);

        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        assertThat(request.jobDef()).isSameAs(jobDef);
        assertThat(request.phase()).isSameAs(phase);
    }
}
