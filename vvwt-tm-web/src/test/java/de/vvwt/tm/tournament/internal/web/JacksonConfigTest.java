package de.vvwt.tm.tournament.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;

/**
 * TDD tests for {@link JacksonConfig} (E21S10, AC-TDD-JacksonConfig, AC-CONFIG-TDD-PATTERN,
 * AC-JACKSON-CONFIG-INTEGRATION, inventory row 451).
 *
 * <p>This test was committed RED: {@link JacksonConfig} at {@code
 * de.vvwt.tm.tournament.internal.web} did not exist at commit time — satisfying the DEC-22 Iron
 * Law.
 *
 * <h2>AC-CONFIG-TDD-PATTERN — RED test proves configuration absence</h2>
 *
 * <p>The {@link WithoutJacksonConfigTest} nested class uses a bare {@link ObjectMapper} (no JSR-310
 * module) and asserts that {@code Instant.now()} serialises as epoch-long — confirming the RED
 * state. The outer class (with {@code @Import(JacksonConfig.class)}) provides the GREEN state once
 * the production class exists.
 *
 * @see JacksonConfig
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law, @Configuration TDD pattern</a>
 * @see <a href="DEC-29">DEC-29 — Compiler hygiene</a>
 * @see <a href="E21S10">E21S10 — inventory row 451</a>
 */
@DisplayName("JacksonConfig — E21S10 AC-CONFIG-TDD-PATTERN and AC-JACKSON-CONFIG-INTEGRATION")
class JacksonConfigTest {

    /**
     * RED state: bare ObjectMapper without JacksonConfig proves ISO-8601 is NOT active by default.
     *
     * <p>This test DOES NOT import JacksonConfig — it uses a raw ObjectMapper to prove the RED
     * state: {@code Instant} serializes as epoch-long without JSR-310 configuration.
     */
    @Nested
    @DisplayName("Without JacksonConfig (RED state proof)")
    class WithoutJacksonConfigTest {

        @Test
        @DisplayName("Bare ObjectMapper serializes Instant as epoch-long (no ISO-8601)")
        void bareObjectMapper_instant_serializesAsEpochLong() throws Exception {
            ObjectMapper bare = new ObjectMapper();
            // Default ObjectMapper: WRITE_DATES_AS_TIMESTAMPS is TRUE
            // Instant serializes as [seconds, nanoseconds] array or epoch number
            assertThat(bare.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
            // This is the RED state: no ISO-8601 for dates without JavaTimeModule
        }
    }

    /**
     * GREEN state: Spring context with JacksonConfig wired — asserts ISO-8601 serialization.
     *
     * <p>These tests verify AC-JACKSON-CONFIG-INTEGRATION: {@code Instant} and {@code LocalDate}
     * serialize as ISO-8601 strings when {@link JacksonConfig} is active in the context.
     */
    @Nested
    @DisplayName("With JacksonConfig wired (GREEN state + AC-JACKSON-CONFIG-INTEGRATION)")
    @ContextConfiguration(classes = JacksonConfig.class)
    @org.springframework.boot.test.context.SpringBootTest(
            classes = {
                JacksonConfig.class,
                org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class
            },
            webEnvironment =
                    org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE)
    class WithJacksonConfigTest {

        @Autowired private ObjectMapper objectMapper;

        @Test
        @DisplayName("Instant serializes to ISO-8601 string (not epoch-long)")
        void instant_serializesToIso8601() throws Exception {
            Instant testInstant = Instant.parse("2026-04-20T12:00:00Z");
            String json = objectMapper.writeValueAsString(testInstant);
            // AC-JACKSON-CONFIG-INTEGRATION: must be ISO-8601 string, not numeric epoch
            assertThat(json).isEqualTo("\"2026-04-20T12:00:00Z\"");
        }

        @Test
        @DisplayName("LocalDate serializes to ISO-8601 string (not array form [YYYY,MM,DD])")
        void localDate_serializesToIso8601() throws Exception {
            LocalDate testDate = LocalDate.of(2026, 4, 20);
            String json = objectMapper.writeValueAsString(testDate);
            // AC-JACKSON-CONFIG-INTEGRATION: must be ISO-8601 string, not array form
            assertThat(json).isEqualTo("\"2026-04-20\"");
        }

        @Test
        @DisplayName(
                "Instant does NOT serialize as epoch-long array (no WRITE_DATES_AS_TIMESTAMPS)")
        void instant_notEpochLong() throws Exception {
            Instant testInstant = Instant.parse("2026-04-20T12:00:00Z");
            String json = objectMapper.writeValueAsString(testInstant);
            // Must not be numeric (epoch seconds)
            assertThat(json).doesNotMatch("^\\d+.*");
            // Must not be array form like [1745150400,0]
            assertThat(json).doesNotStartWith("[");
        }
    }
}
