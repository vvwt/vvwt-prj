package de.vvwt.tm.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson serialization configuration for the REST API (AC2, E05S03).
 *
 * <h2>Configuration choices (AC2)</h2>
 * <ul>
 *   <li><b>Dates as ISO-8601 strings:</b> {@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS}
 *       disabled. {@link JavaTimeModule} registered. {@link java.time.LocalDateTime},
 *       {@link java.time.Instant}, and {@link java.time.LocalDate} serialize as
 *       ISO-8601 strings (e.g., {@code "2026-04-12T15:37:09"}).</li>
 *   <li><b>Enums as strings:</b> {@link SerializationFeature#WRITE_ENUMS_USING_TO_STRING} —
 *       wait, we use {@link MapperFeature#USE_STD_BEAN_NAMING} indirectly; instead we set
 *       {@link SerializationFeature#WRITE_ENUMS_USING_INDEX} to false (default) and let
 *       Jackson use the enum {@code name()} method. Additionally, we disable
 *       {@code WRITE_ENUMS_USING_INDEX} explicitly to ensure name-based serialization.</li>
 *   <li><b>Null fields excluded:</b> {@link JsonInclude#NON_NULL} as the default
 *       serialization inclusion. This is consistent with E03 entity patterns and matches
 *       the {@link ApiErrorResponse} {@code @JsonInclude} annotation.</li>
 * </ul>
 *
 * <h2>Consistency with E03 entity patterns (AC2)</h2>
 * <p>E03 entities use standard Java types ({@link java.time.LocalDateTime} for timestamps,
 * {@link java.util.UUID} for IDs). Jackson's default UUID serialization (as string) and
 * {@link JavaTimeModule} ISO-8601 are already consistent with these patterns.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story E05S03</a>
 */
@Configuration
public class JacksonConfig {

    /**
     * Customizes the global {@link com.fasterxml.jackson.databind.ObjectMapper} used by
     * Spring MVC's Jackson message converter.
     *
     * <p>Using {@link Jackson2ObjectMapperBuilderCustomizer} rather than replacing the
     * {@code ObjectMapper} bean entirely — this preserves Spring Boot's auto-configuration
     * (Actuator modules, Spring Data JDBC, etc.) while adding our serialization rules.
     *
     * @return the customizer bean
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                // AC2: dates as ISO-8601 strings (not timestamps)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

                // AC2: enums as their name() string (not ordinal integer)
                // Jackson default for enum serialization is name() — disable index mode explicitly
                .featuresToDisable(SerializationFeature.WRITE_ENUMS_USING_INDEX)

                // AC2: null fields excluded from all responses
                // Individual DTOs may override with @JsonInclude if needed
                .serializationInclusion(JsonInclude.Include.NON_NULL)

                // Register JavaTimeModule for Java 8+ date/time types (LocalDateTime, Instant, etc.)
                .modules(new JavaTimeModule());
    }
}
