package de.vvwt.info.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.registration.AlgorithmDescriptor;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC3 (testing) — forward/backward compatibility:
 * - A deserializer reading a payload with an unknown optional field deserializes successfully
 *   (Jackson FAIL_ON_UNKNOWN_PROPERTIES=false).
 * - A missing required field produces a Bean Validation failure with the field name in the message.
 *
 * <p>DEC-22 Iron Law: this test was written BEFORE production classes existed (RED state).
 *
 * <p>Story: E38S02.
 */
class ForwardCompatibilityTest {

    private ObjectMapper lenientMapper;
    private ObjectMapper strictMapper;
    private Validator validator;

    @BeforeEach
    void setUp() {
        lenientMapper = new ObjectMapper();
        lenientMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        lenientMapper.findAndRegisterModules();

        strictMapper = new ObjectMapper();
        strictMapper.findAndRegisterModules();

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void unknownOptionalField_deserializesSuccessfully() throws Exception {
        // AC3 — forward compat: unknown extra field is tolerated with FAIL_ON_UNKNOWN_PROPERTIES=false
        String json =
                "{\"algorithm_id\":\"ed25519\","
                        + "\"display_name\":\"Ed25519\","
                        + "\"deprecation_date\":null,"
                        + "\"parameters\":null,"
                        + "\"future_field_v2\":\"some_value\"}";
        assertThatNoException()
                .isThrownBy(() -> lenientMapper.readValue(json, AlgorithmDescriptor.class));
        AlgorithmDescriptor descriptor = lenientMapper.readValue(json, AlgorithmDescriptor.class);
        assertThat(descriptor.algorithm_id()).isEqualTo("ed25519");
    }

    @Test
    void missingRequiredField_beanValidationFailsWithFieldName() {
        // AC3 — missing required field produces BV failure; field name present in error message
        AlgorithmDescriptor descriptor = new AlgorithmDescriptor(null, "Ed25519", null, null);
        Set<ConstraintViolation<AlgorithmDescriptor>> violations = validator.validate(descriptor);
        assertThat(violations).isNotEmpty();
        // Field name must appear in the violation path
        boolean fieldNamePresent =
                violations.stream()
                        .anyMatch(v -> v.getPropertyPath().toString().equals("algorithm_id"));
        assertThat(fieldNamePresent)
                .as("Bean Validation failure must reference field 'algorithm_id'")
                .isTrue();
    }

    @Test
    void unknownPropertiesFlag_isOrthogonalToDiscriminatorHandling() throws Exception {
        // AC3 — forward compat FAIL_ON_UNKNOWN_PROPERTIES=false does NOT suppress discriminator
        // failures (they are orthogonal Jackson mechanisms — AC2 assertion repeated here
        // in forward-compat context for clarity)
        String json =
                "{\"type\":\"UNKNOWN_TYPE\","
                        + "\"algorithm_id\":\"ed25519\","
                        + "\"display_name\":\"Ed25519\"}";
        // Using a sealed type for this test
        assertThatThrownBy(
                        () -> lenientMapper.readValue(json, de.vvwt.info.dto.event.DomainEvent.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidTypeIdException.class);
    }
}
