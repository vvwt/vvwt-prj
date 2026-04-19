package de.vvwt.dispatcher.job;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.UUID;

/**
 * HTTP request body for {@code POST /submit-job} (AC5 of E01S06).
 *
 * <p>{@code phaseDef} is kept as a raw JSON string so that:
 *
 * <ol>
 *   <li>JCS canonicalization is applied to the original JSON bytes (AC6).
 *   <li>The {@code RawPhaseDef} record can be deserialized independently after signature
 *       verification passes.
 * </ol>
 *
 * <p>Jackson's {@code @JsonRawValue} / {@link RawPhaseDefDeserializer} pair handles the round-trip:
 * on deserialization the {@code phaseDef} field is captured as the verbatim JSON string; on
 * serialization it is emitted without re-encoding.
 *
 * @param phaseDef the raw {@code phaseDef} JSON object (verbatim, for JCS + deserialization)
 * @param signature Base64-encoded Ed25519 signature of JCS-canonical {@code phaseDef} bytes
 * @param submitterKeyId UUID of the registered submitter key
 */
public record SubmitJobRequest(
        @JsonDeserialize(using = RawPhaseDefDeserializer.class)
                @JsonSerialize(using = RawPhaseDefSerializer.class)
                String phaseDef,
        String signature,
        UUID submitterKeyId) {}
