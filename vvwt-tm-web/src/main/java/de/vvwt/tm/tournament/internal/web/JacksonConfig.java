package de.vvwt.tm.tournament.internal.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson serialization configuration for the {@code tournament} bounded context (E21S10,
 * AC-TDD-JacksonConfig, AC-PKG-JacksonConfig, AC-JACKSON-CONFIG-INTEGRATION, inventory row 451).
 *
 * <h2>DEC-21 package discipline</h2>
 *
 * <p>Placed at {@code de.vvwt.tm.tournament.internal.web.*}: its effects (ObjectMapper
 * configuration) are global, but the class itself is tournament-internal implementation — not a
 * public API surface. Other modules consume the configured ObjectMapper via Spring's
 * auto-configuration, not via a Java import of this class.
 *
 * <h2>Configuration choices (AC-JACKSON-CONFIG-INTEGRATION)</h2>
 *
 * <ul>
 *   <li><b>Dates as ISO-8601 strings:</b> {@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS}
 *       disabled. {@link JavaTimeModule} registered. {@link java.time.Instant} and {@link
 *       java.time.LocalDate} serialize as ISO-8601 strings (e.g., {@code "2026-04-20T12:00:00Z"}).
 *   <li><b>Null fields excluded:</b> {@link JsonInclude#NON_NULL} as the default serialization
 *       inclusion, consistent with {@link de.vvwt.tm.tournament.ApiErrorResponse} pattern.
 * </ul>
 *
 * <h2>AC-CONFIG-TDD-PATTERN</h2>
 *
 * <p>The RED test in {@code JacksonConfigTest.WithoutJacksonConfigTest} confirmed that a bare
 * {@code ObjectMapper} serializes {@code Instant} as epoch-long. This class makes those tests GREEN
 * by configuring ISO-8601 serialization.
 *
 * <h2>Bean identity</h2>
 *
 * <p>Named {@code "tmJacksonConfig"} to avoid colliding with the legacy {@code
 * de.vvwt.tm.infrastructure.web.JacksonConfig} during the reconstruction-in-place parallel phase.
 * The {@link Jackson2ObjectMapperBuilderCustomizer} bean name is distinct (not the class name), so
 * both customizers coexist without conflict during parallel phase.
 *
 * @see de.vvwt.tm.tournament.ApiErrorResponse
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, internal vs public package</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law, @Configuration TDD pattern</a>
 * @see <a href="DEC-29">DEC-29 — Compiler hygiene</a>
 * @see <a href="DEC-30">DEC-30 — Spotless formatting (AOSP)</a>
 * @see <a href="E21S10">E21S10 — inventory row 451</a>
 */
@Configuration("tmJacksonConfig")
public class JacksonConfig {

    /**
     * Customizes the global {@link com.fasterxml.jackson.databind.ObjectMapper}.
     *
     * <p>Uses {@link Jackson2ObjectMapperBuilderCustomizer} rather than replacing the {@code
     * ObjectMapper} bean — preserves Spring Boot auto-configuration (Actuator, Spring Data JDBC)
     * while adding tournament serialization rules.
     *
     * @return the customizer bean
     */
    @Bean("tmJacksonCustomizer")
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder ->
                builder
                        // AC-JACKSON-CONFIG-INTEGRATION: Instant/LocalDate as ISO-8601 strings
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        // AC-JACKSON-CONFIG-INTEGRATION: no WRITE_ENUMS_USING_INDEX (name-based)
                        .featuresToDisable(SerializationFeature.WRITE_ENUMS_USING_INDEX)
                        // Null fields excluded from all responses
                        .serializationInclusion(JsonInclude.Include.NON_NULL)
                        // Register JavaTimeModule for Java 8+ date/time types
                        .modules(new JavaTimeModule());
    }
}
