package de.vvwt.info.dto.registration;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload for tenant or algorithm registration.
 *
 * <p>Per AC8 / DEC-6 / Brief D-X4(b):
 *
 * <ul>
 *   <li>{@code algorithm_id} — required; identifies the asymmetric-key algorithm used (DEC-43)
 *   <li>{@code public_key} — required; base64-encoded public key bytes
 *   <li>{@code signature} — <strong>nullable</strong>; first registration is unsigned (the operator
 *       trusts the first-key-wins binding per DEC-42 D3). Subsequent operations that require
 *       proof-of-possession are signed per DEC-6; the DTO carries the field as nullable to avoid
 *       smuggling proof-of-possession semantics into the DTO layer.
 *   <li>{@code invitation_token} — <strong>nullable</strong>; required when the server is in {@code
 *       INVITATION_ONLY} (primary profile) mode per DEC-42 D3. Absent or invalid → {@code 403
 *       INVITATION_INVALID}. Ignored in self-host ({@code OPEN_FCFS}) mode. Field added in E38S04
 *       (AC10 scope). Nullable here for forward-compat: self-host clients need not include it.
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S02.story.md">E38S02</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC10</a>
 */
public record RegistrationRequest(
        @NotBlank @JsonProperty("algorithm_id") String algorithm_id,
        @NotNull @JsonProperty("public_key") String public_key,
        @JsonProperty("signature") String signature,
        @JsonProperty("invitation_token") String invitation_token) {}
