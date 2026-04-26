package de.vvwt.slotopt.dispatcher.packet;

/**
 * Thrown when a pull-packet request references a {@code workerId} that is not registered in the key
 * registry.
 *
 * <p>Controller maps this to HTTP 404 Not Found per spec section (b) error matrix (unknown resource
 * → 404).
 *
 * <p>Story: E37S08; AC-PULL-PACKET-CONTROLLER; DEC-35
 */
public class WorkerNotFoundException extends RuntimeException {

    public WorkerNotFoundException(String message) {
        super(message);
    }
}
