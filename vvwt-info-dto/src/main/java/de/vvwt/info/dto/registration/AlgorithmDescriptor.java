package de.vvwt.info.dto.registration;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.Map;

/**
 * Describes a server-supported signature algorithm per DEC-43 D1.
 *
 * <p>Returned in registration-handshake responses so clients can choose an algorithm and detect
 * approaching deprecations (algorithm-agility extension to DEC-6).
 *
 * <p>Field shapes per DEC-43 D1:
 *
 * <ul>
 *   <li>{@code algorithm_id} — required, {@code @NotBlank}; server-canonical identifier (e.g.,
 *       {@code "ed25519"})
 *   <li>{@code display_name} — required, {@code @NotBlank}; human-readable (e.g., {@code
 *       "Ed25519"})
 *   <li>{@code deprecation_date} — optional ({@code null} = supported indefinitely); if non-null,
 *       the algorithm will not be accepted for new registrations after this date (DEC-43 D3 /
 *       DEC-48 boundary semantics)
 *   <li>{@code parameters} — optional ({@code null} for parameterless algorithms); algorithm-
 *       specific parameter set (e.g., curve, key size)
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S02.story.md">E38S02</a>
 */
public record AlgorithmDescriptor(
        @NotBlank @JsonProperty("algorithm_id") String algorithm_id,
        @NotBlank @JsonProperty("display_name") String display_name,
        @JsonProperty("deprecation_date") LocalDate deprecation_date,
        @JsonProperty("parameters") Map<String, Object> parameters) {}
