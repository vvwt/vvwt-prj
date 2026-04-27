package de.vvwt.info.dto.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import de.vvwt.info.dto.error.ErrorResponse;
import de.vvwt.info.dto.error.ErrorResponse.FullResyncRequired;
import de.vvwt.info.dto.error.ErrorResponse.RateLimited;
import de.vvwt.info.dto.error.ErrorResponse.RegistrationRejected;
import de.vvwt.info.dto.error.ErrorResponse.SequenceConflict;
import de.vvwt.info.dto.error.ErrorResponse.SignatureInvalid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC2 (testing) — polymorphic discriminator dispatch for sealed ErrorResponse.
 * AC9 (error-handling) — ErrorResponse sealed type; Phase 1 subtypes; RegistrationRejected.reason
 * as open String.
 *
 * <p>DEC-22 Iron Law: this test was written BEFORE ErrorResponse.java existed (RED state).
 *
 * <p>Story: E38S02.
 */
class ErrorResponseTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    void fullResyncRequired_roundTrip() throws Exception {
        // AC1, AC9 — FullResyncRequired round-trip
        FullResyncRequired error = new FullResyncRequired();
        String json = mapper.writeValueAsString(error);
        ErrorResponse deserialized = mapper.readValue(json, ErrorResponse.class);
        assertThat(deserialized).isInstanceOf(FullResyncRequired.class);
        assertThat(deserialized).isEqualTo(error);
    }

    @Test
    void registrationRejected_roundTrip_withOpenStringReason() throws Exception {
        // AC9 — reason field is open-String (not closed enum) per cycle-1 F-2 fix
        RegistrationRejected error = new RegistrationRejected("KEY_MISMATCH");
        String json = mapper.writeValueAsString(error);
        ErrorResponse deserialized = mapper.readValue(json, ErrorResponse.class);
        assertThat(deserialized).isInstanceOf(RegistrationRejected.class);
        assertThat(((RegistrationRejected) deserialized).reason()).isEqualTo("KEY_MISMATCH");
    }

    @Test
    void registrationRejected_roundTrip_withArbitraryReasonString() throws Exception {
        // AC9 — reason is open-String: arbitrary future reason codes deserialize without change
        RegistrationRejected error = new RegistrationRejected("FUTURE_REASON_CODE_V2");
        String json = mapper.writeValueAsString(error);
        ErrorResponse deserialized = mapper.readValue(json, ErrorResponse.class);
        assertThat(deserialized).isInstanceOf(RegistrationRejected.class);
        assertThat(((RegistrationRejected) deserialized).reason()).isEqualTo("FUTURE_REASON_CODE_V2");
    }

    @Test
    void signatureInvalid_roundTrip() throws Exception {
        SignatureInvalid error = new SignatureInvalid();
        String json = mapper.writeValueAsString(error);
        ErrorResponse deserialized = mapper.readValue(json, ErrorResponse.class);
        assertThat(deserialized).isInstanceOf(SignatureInvalid.class);
    }

    @Test
    void sequenceConflict_roundTrip() throws Exception {
        SequenceConflict error = new SequenceConflict();
        String json = mapper.writeValueAsString(error);
        ErrorResponse deserialized = mapper.readValue(json, ErrorResponse.class);
        assertThat(deserialized).isInstanceOf(SequenceConflict.class);
    }

    @Test
    void rateLimited_roundTrip() throws Exception {
        RateLimited error = new RateLimited(30, "TENANT");
        String json = mapper.writeValueAsString(error);
        ErrorResponse deserialized = mapper.readValue(json, ErrorResponse.class);
        assertThat(deserialized).isInstanceOf(RateLimited.class);
        assertThat(((RateLimited) deserialized).retry_after_seconds()).isEqualTo(30);
        assertThat(((RateLimited) deserialized).scope()).isEqualTo("TENANT");
    }

    @Test
    void unknownDiscriminator_throwsInvalidTypeIdException() throws Exception {
        // AC2 — unknown discriminator value produces InvalidTypeIdException (NOT silent fallback)
        // Note: FAIL_ON_UNKNOWN_PROPERTIES=false is orthogonal (unknown properties, not unknown type)
        String json = "{\"type\":\"COMPLETELY_UNKNOWN_TYPE\",\"someField\":\"value\"}";
        assertThatThrownBy(() -> mapper.readValue(json, ErrorResponse.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void unknownDiscriminator_failOnUnknownPropertiesFalse_stillThrowsInvalidTypeIdException()
            throws Exception {
        // AC2 — FAIL_ON_UNKNOWN_PROPERTIES=false does NOT suppress discriminator failures
        // These are distinct: FAIL_ON_UNKNOWN_PROPERTIES controls unknown JSON fields (not type ids)
        ObjectMapper lenientMapper =
                mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String json = "{\"type\":\"UNKNOWN_DISCRIMINATOR\",\"extra\":\"field\"}";
        assertThatThrownBy(() -> lenientMapper.readValue(json, ErrorResponse.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void discriminatorTagAppearsInSerializedJson() throws Exception {
        // AC2 — verify the discriminator field is present in JSON
        FullResyncRequired error = new FullResyncRequired();
        String json = mapper.writeValueAsString(error);
        assertThat(json).contains("\"type\"");
        assertThat(json).contains("\"FULL_RESYNC_REQUIRED\"");
    }
}
