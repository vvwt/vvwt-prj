package de.vvwt.slotopt.standalone.http;

/**
 * HTTP response body for {@code POST /api/submit-result} from the standalone worker's perspective.
 *
 * <p>Worker-side DTO mirroring the dispatcher's {@code SubmitResultResponse} wire shape (per Brief
 * D-10; implementer reads {@code vvwt-slotopt-dispatcher/.../result/SubmitResultResponse.java} for
 * exact field shape).
 *
 * <p>{@code accepted=true, reason=null} for the first valid result (first-valid-wins per DEC-6).
 * {@code accepted=false, reason="superseded"} when the packet already has a result.
 *
 * <p>Story: E41S05 AC-SUBMIT-RESULT-WITH-ALGORITHM, AC-OBSERVABILITY-EVENTS-RUNTIME.
 */
public record SubmitResultResponse(boolean accepted, String reason) {}
