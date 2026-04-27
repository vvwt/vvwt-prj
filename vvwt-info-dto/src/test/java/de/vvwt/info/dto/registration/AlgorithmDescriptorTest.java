package de.vvwt.info.dto.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.vvwt.info.dto.registration.AlgorithmDescriptor;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC7 (security) — AlgorithmDescriptor record carries DEC-43 D1 field shape.
 * AC1 (testing) — round-trip test.
 * Missing algorithm_id produces a Bean Validation error.
 *
 * <p>DEC-22 Iron Law: this test was written BEFORE AlgorithmDescriptor.java existed (RED state).
 *
 * <p>Story: E38S02. DEC-43 D1.
 */
class AlgorithmDescriptorTest {

    private ObjectMapper mapper;
    private Validator validator;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void roundTrip_fullFields() throws Exception {
        // AC1, AC7 — round-trip with all fields
        AlgorithmDescriptor descriptor =
                new AlgorithmDescriptor(
                        "ed25519",
                        "Ed25519",
                        LocalDate.of(2030, 12, 31),
                        Map.of("keySize", "256"));
        String json = mapper.writeValueAsString(descriptor);
        AlgorithmDescriptor deserialized = mapper.readValue(json, AlgorithmDescriptor.class);
        assertThat(deserialized).isEqualTo(descriptor);
    }

    @Test
    void roundTrip_nullableFieldsOmitted() throws Exception {
        // AC7 — deprecation_date nullable; parameters nullable
        AlgorithmDescriptor descriptor = new AlgorithmDescriptor("ed25519", "Ed25519", null, null);
        String json = mapper.writeValueAsString(descriptor);
        AlgorithmDescriptor deserialized = mapper.readValue(json, AlgorithmDescriptor.class);
        assertThat(deserialized.deprecation_date()).isNull();
        assertThat(deserialized.parameters()).isNull();
    }

    @Test
    void missingAlgorithmId_beanValidationFailure() {
        // AC7 (security) — missing algorithm_id produces a Bean Validation error
        AlgorithmDescriptor descriptor = new AlgorithmDescriptor(null, "Ed25519", null, null);
        Set<ConstraintViolation<AlgorithmDescriptor>> violations = validator.validate(descriptor);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("algorithm_id"));
    }

    @Test
    void blankAlgorithmId_beanValidationFailure() {
        // AC7 (security) — blank algorithm_id (whitespace) also fails @NotBlank
        AlgorithmDescriptor descriptor = new AlgorithmDescriptor("   ", "Ed25519", null, null);
        Set<ConstraintViolation<AlgorithmDescriptor>> violations = validator.validate(descriptor);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("algorithm_id"));
    }

    @Test
    void missingDisplayName_beanValidationFailure() {
        // AC7 — display_name is also required per DEC-43 D1
        AlgorithmDescriptor descriptor = new AlgorithmDescriptor("ed25519", null, null, null);
        Set<ConstraintViolation<AlgorithmDescriptor>> violations = validator.validate(descriptor);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("display_name"));
    }
}
