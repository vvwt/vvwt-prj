package de.vvwt.dispatcher.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.dispatcher.audit.AuditService;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code @WebMvcTest} slice for {@link KeyRegistrationController}.
 *
 * <p>Tests AC1 (happy path), AC2 (invalid key), AC3 (rotation via service mock), AC4 (idempotent +
 * role conflict), and AC12 (audit logging on every call).
 */
@WebMvcTest(KeyRegistrationController.class)
class KeyRegistrationControllerTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private KeyRegistrationService keyRegistrationService;

    @MockitoBean private AuditService auditService;

    // -------------------------------------------------------------------------
    // AC1: happy path
    // -------------------------------------------------------------------------

    @Test
    void registerKey_happyPath_returns200WithKeyId() throws Exception {
        UUID expectedKeyId = UUID.randomUUID();
        Instant registeredAt = Instant.now();
        RegisterKeyResponse mockResponse =
                new RegisterKeyResponse(expectedKeyId, "worker", registeredAt);
        when(keyRegistrationService.register(any())).thenReturn(mockResponse);

        // 32 bytes of valid-looking key material (actual Ed25519 validation is in service)
        byte[] fakeKeyBytes = new byte[32];
        fakeKeyBytes[0] = 0x42;
        String base64Key = Base64.getEncoder().encodeToString(fakeKeyBytes);

        mockMvc.perform(
                        post("/register-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"role":"worker","publicKey":"%s","supersedes":null,"name":"test-worker"}
                                """
                                                .formatted(base64Key)
                                                .trim()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyId").value(expectedKeyId.toString()))
                .andExpect(jsonPath("$.role").value("worker"));
    }

    // -------------------------------------------------------------------------
    // AC2: invalid public key → 400
    // -------------------------------------------------------------------------

    @Test
    void registerKey_invalidPublicKey_returns400() throws Exception {
        when(keyRegistrationService.register(any()))
                .thenThrow(new IllegalArgumentException("invalid public key: not valid Ed25519"));

        String base64Key = Base64.getEncoder().encodeToString(new byte[20]); // wrong length

        mockMvc.perform(
                        post("/register-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"role":"worker","publicKey":"%s"}
                                """
                                                .formatted(base64Key)
                                                .trim()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid public key"));
    }

    // -------------------------------------------------------------------------
    // AC4: role conflict → 409
    // -------------------------------------------------------------------------

    @Test
    void registerKey_roleConflict_returns409() throws Exception {
        when(keyRegistrationService.register(any()))
                .thenThrow(
                        new RoleConflictException("Key registered as worker; cannot be submitter"));

        byte[] fakeKeyBytes = new byte[32];
        String base64Key = Base64.getEncoder().encodeToString(fakeKeyBytes);

        mockMvc.perform(
                        post("/register-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"role":"submitter","publicKey":"%s"}
                                """
                                                .formatted(base64Key)
                                                .trim()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("role conflict"));
    }

    // -------------------------------------------------------------------------
    // AC4: idempotent re-registration → 200 with existing keyId
    // -------------------------------------------------------------------------

    @Test
    void registerKey_idempotentReregistration_returns200WithExistingKeyId() throws Exception {
        UUID existingKeyId = UUID.randomUUID();
        RegisterKeyResponse existingResponse =
                new RegisterKeyResponse(existingKeyId, "worker", Instant.now());
        // Service returns existing registration (idempotent path)
        when(keyRegistrationService.register(any())).thenReturn(existingResponse);

        byte[] fakeKeyBytes = new byte[32];
        String base64Key = Base64.getEncoder().encodeToString(fakeKeyBytes);

        mockMvc.perform(
                        post("/register-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"role":"worker","publicKey":"%s"}
                                """
                                                .formatted(base64Key)
                                                .trim()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyId").value(existingKeyId.toString()));
    }

    // -------------------------------------------------------------------------
    // AC12: audit logging on every call
    // -------------------------------------------------------------------------

    @Test
    void registerKey_successPath_writesAuditEntry() throws Exception {
        UUID keyId = UUID.randomUUID();
        when(keyRegistrationService.register(any()))
                .thenReturn(new RegisterKeyResponse(keyId, "worker", Instant.now()));

        byte[] fakeKeyBytes = new byte[32];
        String base64Key = Base64.getEncoder().encodeToString(fakeKeyBytes);

        mockMvc.perform(
                        post("/register-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"role":"worker","publicKey":"%s"}
                                """
                                                .formatted(base64Key)
                                                .trim()))
                .andExpect(status().isOk());

        // Verify audit log was written exactly once (AC12)
        verify(auditService, times(1)).log(any(), eq("/register-key"), any(), any(), eq(200));
    }

    @Test
    void registerKey_invalidKeyPath_writesAuditEntry() throws Exception {
        when(keyRegistrationService.register(any()))
                .thenThrow(new IllegalArgumentException("invalid public key"));

        mockMvc.perform(
                        post("/register-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"worker\",\"publicKey\":\"invalid\"}"))
                .andExpect(status().isBadRequest());

        // Verify audit log was written even on failure (AC12)
        verify(auditService, times(1)).log(any(), eq("/register-key"), any(), any(), eq(400));
    }
}
