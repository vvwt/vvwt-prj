package de.vvwt.slotopt.worker.runtime;

/**
 * HTTP response body for {@code POST /api/submit-result} from the worker's perspective.
 *
 * <p>Worker-side DTO mirroring the dispatcher's {@code SubmitResultResponse} wire shape. {@code
 * accepted=true, reason=null} for the first valid result (first-valid-wins per DEC-6). {@code
 * accepted=false, reason="superseded"} when the packet already has a result.
 *
 * <p>Story: E41S05 AC-SUBMIT-RESULT-WITH-ALGORITHM (moved to E63S01 shared library).
 */
public record SubmitResultResponse(boolean accepted, String reason) {}
