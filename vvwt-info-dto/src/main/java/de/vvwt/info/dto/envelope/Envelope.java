package de.vvwt.info.dto.envelope;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Envelope wraps every cross-subsystem payload: {@code {schemaVersion: String, payload: T}}.
 *
 * <p>All publish, snapshot, and reader stream messages are envelope-wrapped (AC10). Schema
 * versioning strategy: {@code schemaVersion} uses a 2-segment form {@code "MAJOR.MINOR"}; PATCH
 * releases (e.g., {@code 1.0.1}) are wire-invisible — {@code schemaVersion} stays {@code "1.0"} for
 * any 1.0.x release (AC5).
 *
 * <p>Convenience constant {@link #SCHEMA_VERSION} = {@code "1.0"} for this Phase-1 release.
 *
 * <p>Forward compatibility: consumers SHOULD configure {@code FAIL_ON_UNKNOWN_PROPERTIES=false} so
 * that additive extensions in minor schema versions deserialize successfully (AC3).
 *
 * @param <T> the payload type
 * @see <a href="../../../../../../../../docs/governance/stories/E38S02.story.md">E38S02</a>
 */
public record Envelope<T>(
        @NotBlank @JsonProperty("schemaVersion") String schemaVersion,
        @JsonProperty("payload") T payload) {

    /** Current Phase-1 schema version. Wire-visible form is {@code "MAJOR.MINOR"} only (AC5). */
    public static final String SCHEMA_VERSION = "1.0";
}
