package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TDD RED-first test for {@link ApiErrorResponse} (E21S10, AC-TDD-ApiErrorResponse).
 *
 * <p>This test was committed RED: {@link ApiErrorResponse} at {@code de.vvwt.tm.tournament} did not
 * exist at commit time, causing a compile error — satisfying the DEC-22 Iron Law.
 *
 * @see ApiErrorResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S10">E21S10 — inventory row 412</a>
 */
@DisplayName("ApiErrorResponse — E21S10 AC-TDD-ApiErrorResponse")
class ApiErrorResponseTest {

    @Test
    @DisplayName("Builder constructs response with all mandatory fields populated")
    void builder_populatesAllMandatoryFields() {
        Instant now = Instant.now();
        ApiErrorResponse response =
                new ApiErrorResponse.Builder(
                                400, "Bad Request", "Validation failed", "error.validation")
                        .path("/api/test")
                        .timestamp(now)
                        .build();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getError()).isEqualTo("Bad Request");
        assertThat(response.getMessage()).isEqualTo("Validation failed");
        assertThat(response.getMessageKey()).isEqualTo("error.validation");
        assertThat(response.getPath()).isEqualTo("/api/test");
        assertThat(response.getTimestamp()).isEqualTo(now);
        assertThat(response.getFieldErrors()).isNull();
    }

    @Test
    @DisplayName("Builder defaults timestamp to Instant.now() when not explicitly set")
    void builder_defaultsTimestampToNow() {
        Instant before = Instant.now();
        ApiErrorResponse response =
                new ApiErrorResponse.Builder(
                                500, "Internal Server Error", "An error occurred", "error.internal")
                        .build();
        Instant after = Instant.now();

        assertThat(response.getTimestamp()).isNotNull();
        assertThat(response.getTimestamp()).isBetween(before, after);
    }

    @Test
    @DisplayName("Null fieldErrors field is omitted from Jackson serialization (NON_NULL)")
    void jacksonSerialization_nullFieldErrors_omittedFromJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        ApiErrorResponse response =
                new ApiErrorResponse.Builder(403, "Forbidden", "Access denied", "error.forbidden")
                        .build();

        String json = mapper.writeValueAsString(response);

        assertThat(json).doesNotContain("fieldErrors");
        assertThat(json).contains("\"status\":403");
        assertThat(json).contains("\"message\":\"Access denied\"");
        assertThat(json).contains("\"timestamp\"");
    }

    @Test
    @DisplayName("FieldError inner class stores field, rejectedValue, message, messageKey")
    void fieldError_storesAllFields() {
        ApiErrorResponse.FieldError error =
                new ApiErrorResponse.FieldError(
                        "email", "bad@", "Invalid email format", "error.email");

        assertThat(error.getField()).isEqualTo("email");
        assertThat(error.getRejectedValue()).isEqualTo("bad@");
        assertThat(error.getMessage()).isEqualTo("Invalid email format");
        assertThat(error.getMessageKey()).isEqualTo("error.email");
    }

    @Test
    @DisplayName("FieldError with null rejectedValue stores null without NPE")
    void fieldError_nullRejectedValue_storedAsNull() {
        assertThatCode(
                        () ->
                                new ApiErrorResponse.FieldError(
                                        "field", null, "Required", "error.required"))
                .doesNotThrowAnyException();
    }
}
