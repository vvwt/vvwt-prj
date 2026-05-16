// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.DateTimeFeature;

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
 *   <li><b>Dates as ISO-8601 strings:</b> {@link DateTimeFeature#WRITE_DATES_AS_TIMESTAMPS}
 *       disabled. In Jackson 3.x, Java Time support is built into the core (no JavaTimeModule
 *       needed). {@link java.time.Instant} and {@link java.time.LocalDate} serialize as ISO-8601
 *       strings (e.g., {@code "2026-04-20T12:00:00Z"}).
 *   <li><b>Null fields excluded:</b> {@link JsonInclude#NON_NULL} as the default serialization
 *       inclusion, consistent with {@link de.vvwt.tm.tournament.ApiErrorResponse} pattern.
 * </ul>
 *
 * <h2>SB 4.x migration note (E42S01)</h2>
 *
 * <p>Migrated from {@code Jackson2ObjectMapperBuilderCustomizer} (deprecated in SB 4.x
 * spring-boot-jackson2) to {@code JsonMapperBuilderCustomizer} (Jackson 3.x API in
 * spring-boot-jackson). {@code DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS} replaces the SB 3.x
 * {@code SerializationFeature.WRITE_DATES_AS_TIMESTAMPS} for date-as-timestamp control in Jackson
 * 3.x.
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
 * The {@link JsonMapperBuilderCustomizer} bean name is distinct (not the class name), so both
 * customizers coexist without conflict during parallel phase.
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
     * Customizes the global {@link tools.jackson.databind.ObjectMapper} via Jackson 3.x API.
     *
     * <p>Uses {@link JsonMapperBuilderCustomizer} (SB 4.x replacement for the deprecated {@code
     * Jackson2ObjectMapperBuilderCustomizer}) — preserves Spring Boot auto-configuration (Actuator,
     * Spring Data JDBC) while adding tournament serialization rules. In Jackson 3.x, Java Time
     * support is built-in; no {@code JavaTimeModule} is required (E42S01).
     *
     * @return the customizer bean
     */
    @Bean("tmJacksonCustomizer")
    public JsonMapperBuilderCustomizer jacksonCustomizer() {
        return builder ->
                builder
                        // AC-JACKSON-CONFIG-INTEGRATION: Instant/LocalDate as ISO-8601 strings
                        // In Jackson 3.x, WRITE_DATES_AS_TIMESTAMPS moved to DateTimeFeature
                        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                        // Null fields excluded from all responses
                        .changeDefaultPropertyInclusion(
                                inc -> inc.withValueInclusion(JsonInclude.Include.NON_NULL));
    }
}
