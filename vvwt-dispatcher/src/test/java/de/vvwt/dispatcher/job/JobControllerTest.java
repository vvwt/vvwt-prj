package de.vvwt.dispatcher.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.dispatcher.audit.AuditService;
import de.vvwt.dispatcher.cache.CachedResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link JobController}.
 *
 * <p>Tests AC5 (happy path queued), AC6 (signature failure), AC7 (role check),
 * AC8 (N-cap), AC9 (cache hit), AC10 (cache miss), AC11 (error handling),
 * and AC12 (audit logging).
 *
 * <p>The {@link JobService} is mocked; business logic is tested separately.
 */
@WebMvcTest(JobController.class)
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JobService jobService;

    @MockitoBean
    private AuditService auditService;

    private UUID submitterKeyId;
    private String validPhaseDefJson;
    private String validSignatureBase64;

    @BeforeEach
    void setup() {
        submitterKeyId = UUID.randomUUID();
        validPhaseDefJson = """
                {"phaseId":1,"rowCount":2,"rows":[{"positions":[{"group":0,"pos":0},{"group":0,"pos":1}]},{"positions":[{"group":1,"pos":0},{"group":1,"pos":1}]}]}
                """.trim();
        validSignatureBase64 = Base64.getEncoder().encodeToString(new byte[64]); // mock — service is mocked
    }

    private String buildRequest() {
        return """
                {
                  "phaseDef": %s,
                  "signature": "%s",
                  "submitterKeyId": "%s"
                }
                """.formatted(validPhaseDefJson, validSignatureBase64, submitterKeyId).trim();
    }

    // -------------------------------------------------------------------------
    // AC5 + AC10: happy path — cache miss → queued
    // -------------------------------------------------------------------------

    @Test
    void submitJob_cacheMiss_returns202Queued() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobService.submit(any())).thenReturn(new JobService.SubmitResult.Queued(jobId));

        mockMvc.perform(post("/submit-job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRequest()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.cachedResult").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // AC9: cache hit
    // -------------------------------------------------------------------------

    @Test
    void submitJob_cacheHit_returns202Cached() throws Exception {
        UUID jobId = UUID.randomUUID();
        CachedResult cachedResult = new CachedResult(
                new byte[32], 1, 1, 42L, 0.95, 4, Instant.now(), UUID.randomUUID());
        when(jobService.submit(any()))
                .thenReturn(new JobService.SubmitResult.Cached(jobId, cachedResult));

        mockMvc.perform(post("/submit-job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRequest()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("cached"))
                .andExpect(jsonPath("$.cachedResult.bestRank").value(42))
                .andExpect(jsonPath("$.cachedResult.bestScore").value(0.95));
    }

    // -------------------------------------------------------------------------
    // AC6: signature verification failure → 401
    // -------------------------------------------------------------------------

    @Test
    void submitJob_signatureVerificationFails_returns401() throws Exception {
        when(jobService.submit(any()))
                .thenThrow(new JobService.UnauthorizedException("Signature verification failed"));

        mockMvc.perform(post("/submit-job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    // -------------------------------------------------------------------------
    // AC7: worker key calling submit-job → 403
    // -------------------------------------------------------------------------

    @Test
    void submitJob_workerKeyCallingSubmitJob_returns403() throws Exception {
        when(jobService.submit(any()))
                .thenThrow(new JobService.ForbiddenException("Only submitter keys allowed"));

        mockMvc.perform(post("/submit-job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    // -------------------------------------------------------------------------
    // AC8: N-cap exceeded → 422
    // -------------------------------------------------------------------------

    @Test
    void submitJob_nCapExceeded_returns422() throws Exception {
        when(jobService.submit(any()))
                .thenThrow(new JobService.NCapExceededException(16));

        mockMvc.perform(post("/submit-job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRequest()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("N-cap exceeded"))
                .andExpect(jsonPath("$.message").value(
                        "Phase 1 supports N <= 15. N = 16 requires Phase 2 (not yet shipped)."));
    }

    // -------------------------------------------------------------------------
    // AC11: malformed JSON / missing fields → 400
    // -------------------------------------------------------------------------

    @Test
    void submitJob_missingRequiredField_returns400() throws Exception {
        when(jobService.submit(any()))
                .thenThrow(new IllegalArgumentException("submitterKeyId is required"));

        mockMvc.perform(post("/submit-job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phaseDef\":{\"phaseId\":1,\"rowCount\":0,\"rows\":[]},\"signature\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // AC11: transient DB error → 503 with Retry-After
    // -------------------------------------------------------------------------

    @Test
    void submitJob_transientDatabaseError_returns503WithRetryAfter() throws Exception {
        when(jobService.submit(any()))
                .thenThrow(new JobService.DatabaseException("connection timeout",
                        new RuntimeException("jdbc error")));

        mockMvc.perform(post("/submit-job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
    }

    // -------------------------------------------------------------------------
    // AC12: audit logging — success and failure paths
    // -------------------------------------------------------------------------

    @Test
    void submitJob_successPath_writesAuditEntry() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobService.submit(any())).thenReturn(new JobService.SubmitResult.Queued(jobId));

        mockMvc.perform(post("/submit-job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRequest()))
                .andExpect(status().isAccepted());

        verify(auditService, times(1)).log(any(), eq("/submit-job"), any(), any(), eq(202));
    }

    @Test
    void submitJob_unauthorizedPath_writesAuditEntry() throws Exception {
        when(jobService.submit(any()))
                .thenThrow(new JobService.UnauthorizedException("bad sig"));

        mockMvc.perform(post("/submit-job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRequest()))
                .andExpect(status().isUnauthorized());

        verify(auditService, times(1)).log(any(), eq("/submit-job"), any(), any(), eq(401));
    }
}
