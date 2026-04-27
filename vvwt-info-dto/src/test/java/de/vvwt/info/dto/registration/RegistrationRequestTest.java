package de.vvwt.info.dto.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.registration.AlgorithmWarning;
import de.vvwt.info.dto.registration.RegistrationRequest;
import de.vvwt.info.dto.registration.RegistrationResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC8 (security) — RegistrationRequest: algorithm_id required, public_key required, signature NULLABLE.
 * AC1 (testing) — round-trip tests.
 * Also covers RegistrationResponse: nullable tournament_token, optional algorithm_warning.
 *
 * <p>DEC-22 Iron Law: this test was written BEFORE RegistrationRequest.java existed (RED state).
 *
 * <p>Story: E38S02. DEC-6 (signature nullable per first-registration-unsigned, Brief D-X4 b).
 */
class RegistrationRequestTest {

    private ObjectMapper mapper;
    private Validator validator;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void registrationRequest_roundTrip_firstRegistration_nullSignature() throws Exception {
        // AC8 — first registration is unsigned (signature is null) per Brief D-X4(b) / DEC-6 carve-out
        String publicKeyB64 = Base64.getEncoder().encodeToString(new byte[32]);
        RegistrationRequest request = new RegistrationRequest("ed25519", publicKeyB64, null);
        String json = mapper.writeValueAsString(request);
        RegistrationRequest deserialized = mapper.readValue(json, RegistrationRequest.class);
        assertThat(deserialized).isEqualTo(request);
        assertThat(deserialized.signature()).isNull();
    }

    @Test
    void registrationRequest_roundTrip_withSignature() throws Exception {
        // AC8 — subsequent registrations can carry a signature
        String publicKeyB64 = Base64.getEncoder().encodeToString(new byte[32]);
        String signatureB64 = Base64.getEncoder().encodeToString(new byte[64]);
        RegistrationRequest request = new RegistrationRequest("ed25519", publicKeyB64, signatureB64);
        String json = mapper.writeValueAsString(request);
        RegistrationRequest deserialized = mapper.readValue(json, RegistrationRequest.class);
        assertThat(deserialized).isEqualTo(request);
        assertThat(deserialized.signature()).isNotNull();
    }

    @Test
    void missingAlgorithmId_beanValidationFailure() {
        // AC8 — algorithm_id is required
        RegistrationRequest request =
                new RegistrationRequest(null, Base64.getEncoder().encodeToString(new byte[32]), null);
        Set<ConstraintViolation<RegistrationRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("algorithm_id"));
    }

    @Test
    void missingPublicKey_beanValidationFailure() {
        // AC8 — public_key is required
        RegistrationRequest request = new RegistrationRequest("ed25519", null, null);
        Set<ConstraintViolation<RegistrationRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("public_key"));
    }

    @Test
    void registrationResponse_roundTrip_withNullTournamentToken() throws Exception {
        // AC8 — tournament_token is nullable (populated by tournament-registration endpoint in E38S05)
        RegistrationResponse response = new RegistrationResponse(null, null);
        String json = mapper.writeValueAsString(response);
        RegistrationResponse deserialized = mapper.readValue(json, RegistrationResponse.class);
        assertThat(deserialized.tournament_token()).isNull();
        assertThat(deserialized.algorithm_warning()).isNull();
    }

    @Test
    void registrationResponse_roundTrip_withAlgorithmWarning() throws Exception {
        // AC8 — algorithm_warning field shape: {algorithm_id, deprecation_date, days_remaining}
        AlgorithmWarning warning = new AlgorithmWarning("ed25519", LocalDate.of(2028, 12, 31), 1000);
        RegistrationResponse response = new RegistrationResponse("token-abc", warning);
        String json = mapper.writeValueAsString(response);
        RegistrationResponse deserialized = mapper.readValue(json, RegistrationResponse.class);
        assertThat(deserialized.tournament_token()).isEqualTo("token-abc");
        assertThat(deserialized.algorithm_warning()).isEqualTo(warning);
    }
}
