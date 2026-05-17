// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring MVC test slice for {@link JobStatusController}.
 *
 * <p>DEC-36: mocks {@link JobRepository}, {@link PacketRepository}, and {@link PacketResultService}
 * via their public interfaces (Spring Data IS the port per DEC-35; PacketResultService is the
 * service interface per DEC-35/DEC-58/DEC-72).
 *
 * <p>RED-first per DEC-22 / AC-MOCKMVC-CONTROLLER-TESTS (E37S09 + E60S04).
 *
 * <p>Story: E37S09 + E60S04; AC-JOB-STATUS-CONTROLLER; AC-MOCKMVC-CONTROLLER-TESTS;
 * AC-TEST-JOB-STATUS-REPORTS-LIFECYCLE; AC-TEST-JOB-STATUS-FINAL-RESULT;
 * AC-TEST-JOB-STATUS-BEST-SO-FAR; AC-ERR-UNKNOWN-JOB-404; AC-ERR-BEST-SO-FAR-NO-COMPLETED-PACKETS;
 * DEC-36
 */
@WebMvcTest(
        value = JobStatusController.class,
        excludeAutoConfiguration = {
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class
        })
class JobStatusControllerSliceTest {

    @Autowired private MockMvc mockMvc;

    /** DEC-36: mock the public Spring Data interface (IS the port per DEC-35). */
    @MockitoBean private JobRepository jobRepository;

    @MockitoBean private PacketRepository packetRepository;

    /** DEC-36: mock via public service interface (DEC-35/DEC-58/DEC-72). */
    @MockitoBean private PacketResultService packetResultService;

    // -------------------------------------------------------------------------
    // AC-TEST-JOB-STATUS-REPORTS-LIFECYCLE (regression for original lifecycle)
    // -------------------------------------------------------------------------

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

        // No retained results yet for this in-progress job
        when(packetResultService.findResultsByJobId(jobId)).thenReturn(List.of());

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

    // -------------------------------------------------------------------------
    // AC-TEST-JOB-STATUS-REPORTS-LIFECYCLE — E60S04 (RED-first)
    // Verifies that status values RECEIVED / DECOMPOSED / COMPLETED are exposed
    // -------------------------------------------------------------------------

    @Test
    void getJobStatus_receivedJob_returnsReceivedStatus() throws Exception {
        UUID jobId = UUID.randomUUID();
        JobRecord job = new JobRecord();
        job.setJobId(jobId);
        job.setStatus("RECEIVED");
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(job));
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of());
        when(packetResultService.findResultsByJobId(jobId)).thenReturn(List.of());

        mockMvc.perform(get("/api/job-status/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.finalResult").doesNotExist())
                .andExpect(jsonPath("$.bestSoFar").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // AC-TEST-JOB-STATUS-FINAL-RESULT — E60S04 (RED-first)
    // -------------------------------------------------------------------------

    @Test
    void getJobStatus_completedJob_exposesFinalResult() throws Exception {
        UUID jobId = UUID.randomUUID();
        JobRecord job = new JobRecord();
        job.setJobId(jobId);
        job.setStatus("COMPLETED");
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(job));

        PacketRecord p1 = new PacketRecord();
        p1.setStatus("RESULT_RECEIVED");
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of(p1));

        PacketResult retained = new PacketResult();
        retained.setPacketId(UUID.randomUUID());
        retained.setJobId(jobId);
        retained.setBestRank(2);
        retained.setBestScore(30.0);
        when(packetResultService.findResultsByJobId(jobId)).thenReturn(List.of(retained));

        mockMvc.perform(get("/api/job-status/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.finalResult.bestRank").value(2))
                .andExpect(jsonPath("$.finalResult.bestScore").value(30.0))
                .andExpect(jsonPath("$.bestSoFar").doesNotExist());
    }

    @Test
    void getJobStatus_inProgressJob_finalResultIsAbsent() throws Exception {
        UUID jobId = UUID.randomUUID();
        JobRecord job = new JobRecord();
        job.setJobId(jobId);
        job.setStatus("DECOMPOSED");
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(job));
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of());
        when(packetResultService.findResultsByJobId(jobId)).thenReturn(List.of());

        mockMvc.perform(get("/api/job-status/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECOMPOSED"))
                .andExpect(jsonPath("$.finalResult").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // AC-TEST-JOB-STATUS-BEST-SO-FAR — E60S04 (RED-first)
    // -------------------------------------------------------------------------

    @Test
    void getJobStatus_inProgressJob_exposesBestSoFar_whenSomePacketsRetained() throws Exception {
        UUID jobId = UUID.randomUUID();
        JobRecord job = new JobRecord();
        job.setJobId(jobId);
        job.setStatus("DECOMPOSED");
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(job));

        PacketRecord p1 = new PacketRecord();
        p1.setStatus("RESULT_RECEIVED");
        PacketRecord p2 = new PacketRecord();
        p2.setStatus("UNCLAIMED");
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of(p1, p2));

        PacketResult retained = new PacketResult();
        retained.setPacketId(UUID.randomUUID());
        retained.setJobId(jobId);
        retained.setBestRank(1);
        retained.setBestScore(15.5);
        when(packetResultService.findResultsByJobId(jobId)).thenReturn(List.of(retained));

        mockMvc.perform(get("/api/job-status/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECOMPOSED"))
                .andExpect(jsonPath("$.bestSoFar.bestRank").value(1))
                .andExpect(jsonPath("$.bestSoFar.bestScore").value(15.5))
                .andExpect(jsonPath("$.finalResult").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // AC-ERR-BEST-SO-FAR-NO-COMPLETED-PACKETS — E60S04 (RED-first)
    // -------------------------------------------------------------------------

    @Test
    void getJobStatus_inProgressJob_bestSoFarIsAbsent_whenNoPacketsRetained() throws Exception {
        UUID jobId = UUID.randomUUID();
        JobRecord job = new JobRecord();
        job.setJobId(jobId);
        job.setStatus("DECOMPOSED");
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(job));
        when(packetRepository.findByJobId(jobId)).thenReturn(List.of());
        when(packetResultService.findResultsByJobId(jobId)).thenReturn(List.of());

        mockMvc.perform(get("/api/job-status/" + jobId))
                .andExpect(status().isOk())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestSoFar").doesNotExist())
                .andExpect(jsonPath("$.finalResult").doesNotExist());
    }
}
