package de.vvwt.tm.infrastructure.print;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Thrown by {@link PrintController} when a tournament is not found in the active tenant's scope.
 *
 * <p>Story E08S07 — AC7, AC9:
 * <ul>
 *   <li>AC7: Non-existent tournament results in HTTP 404.</li>
 *   <li>AC9: Tenant-scoped repository returns empty for IDs outside the tenant; this exception
 *       is thrown in that case, producing an unambiguous 404 without leaking whether the ID
 *       belongs to another tenant.</li>
 * </ul>
 *
 * <p>Uses {@link ResponseStatus} to set the HTTP status code directly, bypassing
 * {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}'s catch-all {@code Exception}
 * handler (which would otherwise return 500). Spring MVC processes {@code @ResponseStatus}
 * annotations on exceptions BEFORE delegating to exception handlers, so this exception
 * produces a 404 without triggering the catch-all.
 *
 * <p>This is intentionally a print-package-local exception — it is not a general-purpose
 * "not found" exception because different surfaces have different 404 semantics.
 *
 * @see PrintController
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
class TournamentNotFoundException extends RuntimeException {

    TournamentNotFoundException(UUID tournamentId) {
        super("Tournament not found: " + tournamentId);
    }
}
