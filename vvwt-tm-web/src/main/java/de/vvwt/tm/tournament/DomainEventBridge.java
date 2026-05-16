package de.vvwt.tm.tournament;

/**
 * Marker interface for the domain event bridge component.
 *
 * <p>The bridge listens to {@code de.vvwt.tm.tournament.events.*} events and forwards them to
 * WebSocket topics. No methods are directly consumed by other production code — the interface
 * satisfies DEC-58 Clause A + DEC-72 (every {@code @Component} must have a public interface in the
 * bounded-context root package) for the AOP-proxy mandate.
 *
 * @since E57S01
 */
public interface DomainEventBridge {}
