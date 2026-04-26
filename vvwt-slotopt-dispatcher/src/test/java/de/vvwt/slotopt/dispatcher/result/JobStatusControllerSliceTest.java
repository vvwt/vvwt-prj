package de.vvwt.slotopt.dispatcher.result;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.slotopt.dispatcher.job.JobRecord;
import de.vvwt.slotopt.dispatcher.job.JobRepository;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring MVC test slice for {@link JobStatusController}.
 *
 * <p>DEC-36: mocks {@link JobRepository} and {@link PacketRepository} via their public Spring Data
 * interfaces (Spring Data IS the port — no separate interface needed per DEC-35).
 *
 * <p>RED-first per DEC-22 / AC-MOCKMVC-CONTROLLER-TESTS (E37S09).
 *
 * <p>Story: E37S09; AC-JOB-STATUS-CONTROLLER; AC-MOCKMVC-CONTROLLER-TESTS; DEC-36
 */
@WebMvcTest(JobStatusController.class)
class JobStatusControllerSliceTest {

    @Autowired private MockMvc mockMvc;

    /** DEC-36: mock the public Spring Data interface (IS the port per DEC-35). */
    @MockitoBean private JobRepository jobRepository;

    @MockitoBean private PacketRepository packetRepository;

    @Test
    void getJobStatus_knownJob_returns200WithAggregatedData() throws Exception {
        UUID jobId = UUID.randomUUID();

        JobRecord job = new JobRecord();
        job.setJobId(jobId);
        job.setStatus("DECOMPOSED");
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(job));

        PacketRecord p1 = new PacketRecord();
        p1.setStatus("RESULT_RECEIVED");
        PacketRecord p2 = new PacketRecord();
        p2.setStatus("CLAIMED");
        PacketRecord p3 = new PacketRecord();
        p3.setStatus("UNCLAIMED");
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of(p1, p2, p3));

        mockMvc.perform(get("/api/job-status/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("DECOMPOSED"))
                .andExpect(jsonPath("$.totalPackets").value(3))
                .andExpect(jsonPath("$.completedPackets").value(1));
    }

    @Test
    void getJobStatus_unknownJob_returns404() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/job-status/" + jobId)).andExpect(status().isNotFound());
    }
}
