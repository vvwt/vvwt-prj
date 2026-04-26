package de.vvwt.slotopt.dispatcher.result;

/**
 * Thrown when the {@code algorithm} field in a {@code submit-result} request does not match the
 * algorithm registered for the worker.
 *
 * <p>Maps to HTTP 400 Bad Request at the controller layer per AC-ALGORITHM-MISMATCH-REJECTED.
 *
 * <p>Defends against algorithm downgrade/upgrade attacks at submission time.
 *
 * <p>Story: E37S09; AC-ALGORITHM-MISMATCH-REJECTED; DEC-43 § D2
 */
public class AlgorithmMismatchException extends RuntimeException {

    public AlgorithmMismatchException(String message) {
        super(message);
    }
}
