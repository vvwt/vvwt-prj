package de.vvwt.tm.infrastructure;

/**
 * Startup diagnostics logger: emits a structured log block after the application is fully
 * initialised.
 *
 * <p>All entries are logged at INFO level with the prefix {@code [tm-bootstrap]} so they can be
 * grepped in Delivery and production logs (AC9).
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E02S02.story.md">Story
 *     E02S02</a>
 * @since E57S01 (DEC-58/DEC-72 interface extraction)
 */
public interface TmStartupLogger {

    /**
     * Emits the startup diagnostics log block. Called by Spring after the application context is
     * fully refreshed and all beans are ready.
     */
    void logStartupDiagnostics();
}
