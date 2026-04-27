package de.vvwt.info.dto.envelope;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC1 (testing) — RED-first JSON round-trip test for Envelope. AC10 (error-handling) — envelope
 * wraps every cross-subsystem payload; missing schemaVersion produces a Bean Validation failure.
 *
 * <p>DEC-22 Iron Law: this test was written BEFORE Envelope.java existed (RED state).
 *
 * <p>Story: E38S02. DEC-22, DEC-42 D1.
 */
class EnvelopeTest {

    private ObjectMapper mapper;
    private Validator validator;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void roundTrip_stringPayload() throws Exception {
        // AC1 — round-trip test: writeValueAsString then readValue produces equals-identical
        // instance
        Envelope<String> envelope = new Envelope<>("1.0", "hello payload");
        String json = mapper.writeValueAsString(envelope);
        @SuppressWarnings("unchecked")
        Envelope<String> deserialized =
                mapper.readValue(
                        json,
                        mapper.getTypeFactory()
                                .constructParametricType(Envelope.class, String.class));
        assertThat(deserialized).isEqualTo(envelope);
    }

    @Test
    void schemaVersionPresentInJson() throws Exception {
        // AC10 — schemaVersion appears in the serialized JSON
        Envelope<String> envelope = new Envelope<>("1.0", "payload");
        String json = mapper.writeValueAsString(envelope);
        assertThat(json).contains("\"schemaVersion\"");
        assertThat(json).contains("\"1.0\"");
    }

    @Test
    void missingSchemaVersion_beanValidationFailure() {
        // AC10 — deserializing payload missing schemaVersion produces a Bean Validation failure
        // (null or blank value on @NotBlank field)
        Envelope<String> envelope = new Envelope<>(null, "payload");
        Set<ConstraintViolation<Envelope<String>>> violations = validator.validate(envelope);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("schemaVersion"));
    }

    @Test
    void schemaVersionInitializedTo_1_0() {
        // AC5 — envelope schemaVersion constant is "1.0"
        assertThat(Envelope.SCHEMA_VERSION).isEqualTo("1.0");
    }
}
