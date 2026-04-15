package de.vvwt.tm.infrastructure.print;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.io.IOException;

/**
 * Exception handler scoped to the {@link PrintController} (E08S07 AC7).
 *
 * <p>Spring's {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} is a
 * {@code @RestControllerAdvice} that applies to all controllers. Its catch-all
 * {@code @ExceptionHandler(Exception.class)} would intercept {@link TournamentNotFoundException}
 * and return a JSON 500 response, which is wrong for a {@code @Controller} print route.
 *
 * <p>This {@code @ControllerAdvice} is scoped to the {@code de.vvwt.tm.infrastructure.print}
 * package and handles {@link TournamentNotFoundException} before the global handler can.
 * Spring's {@code @ExceptionHandler} resolution prefers the most specific applicable handler,
 * and a handler in the same package as the throwing controller takes precedence over a
 * global catch-all for the same exception type.
 *
 * <p>Returns HTTP 404 directly via {@link HttpServletResponse#sendError} to avoid any
 * response-body serialization issues (the global handler's JSON serializer would conflict
 * with the print controller's text/html content-type expectation).
 *
 * <h2>AC7</h2>
 * <p>Non-existent tournament → HTTP 404. This handler enforces that contract for the
 * print route without returning JSON or raw stack traces.
 *
 * @see PrintController
 * @see TournamentNotFoundException
 */
@ControllerAdvice(basePackageClasses = PrintController.class)
class PrintControllerAdvice {

    /**
     * Handles {@link TournamentNotFoundException} — tournament not found in active tenant scope.
     *
     * <p>Sends HTTP 404 via {@link HttpServletResponse#sendError}. This bypasses Spring MVC's
     * message converter pipeline (which would fail trying to write JSON with Content-Type
     * text/html) and delegates to the container's standard 404 error response.
     *
     * @param response the HTTP response
     * @throws IOException if {@code sendError} fails
     */
    @ExceptionHandler(TournamentNotFoundException.class)
    public void handleTournamentNotFound(HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
}
