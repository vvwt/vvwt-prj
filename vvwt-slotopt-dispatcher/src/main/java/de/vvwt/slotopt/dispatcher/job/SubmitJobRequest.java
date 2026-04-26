package de.vvwt.slotopt.dispatcher.job;

import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.RawPhaseDef;

/**
 * Request DTO for the {@code POST /api/submit-job} endpoint.
 *
 * <p>Field names match the spec verbatim (per AC-SUBMIT-JOB-DTOs):
 *
 * <ul>
 *   <li>{@code jobDef} — the job descriptor from the worker-lib type model (DEC-11)
 *   <li>{@code phase} — the raw phase definition; UUIDs are rejected by {@link
 *       RawPhaseDefDeserializer} at deserialization time (DEC-9 boundary)
 * </ul>
 *
 * <p>This is a Java record for immutability and conciseness. Spring Boot 3.x / Jackson 2.x
 * deserializes records via constructor introspection. Requires {@code
 * spring.jackson.constructor-detector=use-properties-based} or the record constructor is
 * auto-detected.
 *
 * <p>Story: E37S07; AC-SUBMIT-JOB-DTOs; DEC-9
 */
public record SubmitJobRequest(JobDef jobDef, RawPhaseDef phase) {}
