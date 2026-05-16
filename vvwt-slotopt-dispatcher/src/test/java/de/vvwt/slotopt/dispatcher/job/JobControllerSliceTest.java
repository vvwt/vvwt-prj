// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring MVC test slice for {@link JobController}.
 *
 * <p>DEC-36 cross-package test typing rule: this test class is in the {@code job} package (same
 * public surface), mocking {@link JobService} (the public interface), NOT the implementation class
 * {@code DefaultJobService}.
 *
 * <p>RED-first per DEC-22 / AC-MOCKMVC-CONTROLLER-TEST (E37S07): written before controller class
 * exists.
 *
 * <p>Tests:
 *
 * <ul>
 *   <li>202 Accepted on successful job submission
 *   <li>400 Bad Request on IllegalArgumentException (validation failures including N-cap)
 *   <li>400 Bad Request on malformed/DEC-9 violating JSON (HttpMessageNotReadableException)
 * </ul>
 *
 * <p>Story: E37S07; AC-MOCKMVC-CONTROLLER-TEST; DEC-36
 */
@WebMvcTest(
        value = JobController.class,
        excludeAutoConfiguration = {
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class
        })
class JobControllerSliceTest {

    @Autowired private MockMvc mockMvc;

    // DEC-36: mock the PUBLIC INTERFACE, not the implementation class
    @MockitoBean private JobService jobService;

    private static final String VALID_SUBMIT_JOB_JSON =
            """
            {
              "jobDef": {
                "jobId": "%s",
                "n": 2,
                "canonicalPhaseDef": {
                  "rowCount": 1,
                  "avatarCount": 2,
                  "rows": [[0, 1]]
                }
              },
              "phase": {
                "phaseId": 1,
                "rowCount": 1,
                "rows": [{"positions": [{"group": 0, "pos": 0}, {"group": 0, "pos": 1}]}]
              }
            }
            """;

    // -------------------------------------------------------------------------
    // 202 on successful job submission
    // -------------------------------------------------------------------------

    @Test
    void submitJobReturns202OnSuccess() throws Exception {
        UUID jobId = UUID.randomUUID();
        SubmitJobResponse response = new SubmitJobResponse(jobId, Instant.now());
        when(jobService.submitJob(any())).thenReturn(response);

        mockMvc.perform(
                        post("/api/submit-job")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_SUBMIT_JOB_JSON.formatted(UUID.randomUUID())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    // -------------------------------------------------------------------------
    // 400 on IllegalArgumentException (validation failures)
    // -------------------------------------------------------------------------

    @Test
    void submitJobReturns400OnValidationFailure() throws Exception {
        when(jobService.submitJob(any()))
                .thenThrow(new IllegalArgumentException("N-cap exceeded: rowCount 16 > 15"));

        mockMvc.perform(
                        post("/api/submit-job")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_SUBMIT_JOB_JSON.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // -------------------------------------------------------------------------
    // 400 on malformed JSON / DEC-9 violation (HttpMessageNotReadableException)
    // -------------------------------------------------------------------------

    @Test
    void submitJobReturns400OnMalformedJson() throws Exception {
        mockMvc.perform(
                        post("/api/submit-job")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ this is not valid json }"))
                .andExpect(status().isBadRequest());
    }
}
