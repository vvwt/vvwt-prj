package de.vvwt.slotopt.worker.runtime;

/**
 * The outcome of a single iteration of the worker compute loop.
 *
 * <p>Returned by {@link ComputeStep#execute} to allow the calling loop to decide whether to
 * continue, back off, or emit appropriate observability events.
 *
 * <ul>
 *   <li>{@link #PACKET_PROCESSED} — a packet was pulled, solved, signed, and submitted
 *       (accepted=true by dispatcher).
 *   <li>{@link #PACKET_SUPERSEDED} — a packet was pulled, solved, signed, and submitted but the
 *       dispatcher returned accepted=false (result superseded by another worker).
 *   <li>{@link #NO_PACKET} — the dispatcher returned HTTP 204 (no packets available); the loop
 *       should back off.
 * </ul>
 *
 * <p>Story: E63S01 AC-GOV-NO-TEST-ONLY-MEMBERS-IN-EXTRACTED-CODE, AC-TEST-OUTAGE-SEAM-PLUGGABLE,
 * AC-TEST-COMPUTE-PATH-BEHAVIOUR-PRESERVED.
 */
public enum ComputeStepResult {

    /**
     * A packet was pulled, solved, signed, and submitted; the dispatcher accepted the result
     * (accepted=true).
     */
    PACKET_PROCESSED,

    /**
     * A packet was pulled, solved, signed, and submitted; the dispatcher returned accepted=false
     * (the result was superseded by another worker).
     */
    PACKET_SUPERSEDED,

    /** The dispatcher had no packet available (HTTP 204); the loop should back off. */
    NO_PACKET
}
