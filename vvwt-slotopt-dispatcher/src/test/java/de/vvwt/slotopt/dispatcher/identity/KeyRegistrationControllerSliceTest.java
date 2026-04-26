package de.vvwt.slotopt.dispatcher.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring MVC test slice for {@link KeyRegistrationController}.
 *
 * <p>DEC-36 cross-package test typing rule: this test class is in the {@code identity} package
 * (different from {@code identity.internal}), so it mocks {@link KeyRegistrationService} (the
 * public interface), NOT the implementation class.
 *
 * <p>RED-first per DEC-22 / AC-MOCKMVC-CONTROLLER-TEST: written before controller class exists.
 *
 * <p>Tests:
 *
 * <ul>
 *   <li>201 on new registration
 *   <li>200 on idempotent re-registration (controller uses {@link RegistrationOutcome#isNew()})
 *   <li>400 on unknown algorithm (service throws {@link IllegalArgumentException})
 *   <li>400 on out-of-range key length (service throws {@link IllegalArgumentException})
 *   <li>409 on role conflict (service throws {@link RoleConflictException})
 *   <li>400 on missing/empty algorithm field (AC-ALGORITHM-FIELD-REQUIRED)
 * </ul>
 *
 * <p>Story: E37S05; AC-MOCKMVC-CONTROLLER-TEST
 */
@WebMvcTest(KeyRegistrationController.class)
class KeyRegistrationControllerSliceTest {

    @Autowired private MockMvc mockMvc;

    // DEC-36: mock the PUBLIC INTERFACE, not the implementation class
    @MockitoBean private KeyRegistrationService keyRegistrationService;

    private static final String NEW_REGISTRATION_JSON =
            """
            {
              "workerId": "%s",
              "role": "worker",
              "algorithm": "Ed25519",
              "publicKeyBytes": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
            }
            """;

    // -------------------------------------------------------------------------
    // 201 on new registration
    // -------------------------------------------------------------------------

    @Test
    void newRegistrationReturns201() throws Exception {
        UUID workerId = UUID.randomUUID();
        RegisterKeyResponse response =
                new RegisterKeyResponse(workerId, "worker", "Ed25519", Instant.now());
        when(keyRegistrationService.register(any(), any()))
                .thenReturn(new RegistrationOutcome(response, true));

        mockMvc.perform(
                        post("/api/register-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(NEW_REGISTRATION_JSON.formatted(workerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workerId").value(workerId.toString()))
                .andExpect(jsonPath("$.role").value("worker"))
                .andExpect(jsonPath("$.algorithm").value("Ed25519"));
    }

    // -------------------------------------------------------------------------
    // 200 on idempotent re-registration
    // -------------------------------------------------------------------------

    @Test
    void idempotentReRegistrationReturns200() throws Exception {
        UUID workerId = UUID.randomUUID();
        RegisterKeyResponse response =
                new RegisterKeyResponse(workerId, "worker", "Ed25519", Instant.now());
        when(keyRegistrationService.register(any(), any()))
                .thenReturn(new RegistrationOutcome(response, false));

        mockMvc.perform(
                        post("/api/register-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(NEW_REGISTRATION_JSON.formatted(workerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value(workerId.toString()));
    }

    // -------------------------------------------------------------------------
    // 400 on unknown algorithm
    // -------------------------------------------------------------------------

    @Test
    void unknownAlgorithmReturns400() throws Exception {
        when(keyRegistrationService.register(any(), any()))
                .thenThrow(new IllegalArgumentException("Unknown algorithm: 'ML-DSA-65'"));

        mockMvc.perform(
                        post("/api/register-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(NEW_REGISTRATION_JSON.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // -------------------------------------------------------------------------
    // 400 on out-of-range key length
    // -------------------------------------------------------------------------

    @Test
    void outOfRangeKeyLengthReturns400() throws Exception {
        when(keyRegistrationService.register(any(), any()))
                .thenThrow(new IllegalArgumentException("public key length 16 is out of range"));

        mockMvc.perform(
                        post("/api/register-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(NEW_REGISTRATION_JSON.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // 409 on RoleConflict
    // -------------------------------------------------------------------------

    @Test
    void roleConflictReturns409() throws Exception {
        UUID workerId = UUID.randomUUID();
        when(keyRegistrationService.register(any(), any()))
                .thenThrow(new RoleConflictException(workerId, "worker", "submitter"));

        mockMvc.perform(
                        post("/api/register-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "workerId": "%s",
                                          "role": "submitter",
                                          "algorithm": "Ed25519",
                                          "publicKeyBytes": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                                        }
                                        """
                                                .formatted(workerId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    // -------------------------------------------------------------------------
    // 400 on missing/empty algorithm field (AC-ALGORITHM-FIELD-REQUIRED)
    // -------------------------------------------------------------------------

    @Test
    void missingAlgorithmFieldReturns400() throws Exception {
        when(keyRegistrationService.register(any(), any()))
                .thenThrow(new IllegalArgumentException("algorithm field is required"));

        mockMvc.perform(
                        post("/api/register-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "workerId": "%s",
                                          "role": "worker",
                                          "publicKeyBytes": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                                        }
                                        """
                                                .formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }
}
