package de.vvwt.slotopt.dispatcher.result;

/**
 * Thrown when the worker ID in a {@code submit-result} request is not registered.
 *
 * <p>Maps to HTTP 401 Unauthorized at the controller layer.
 *
 * <p>Story: E37S09; AC-SUBMIT-RESULT-SERVICE; DEC-6
 */
public class UnknownWorkerException extends RuntimeException {

    public UnknownWorkerException(String message) {
        super(message);
    }
}
