package de.vvwt.slotopt.standalone.bootstrap;

import de.vvwt.slotopt.standalone.WorkerConfig;

/**
 * Orchestrates the worker bootstrap phase: algorithm announcement query, validation, optional D3
 * warning, and public-key registration.
 *
 * <p>The bootstrap phase runs once at process startup, before the runtime polling loop (E41S05). On
 * any failure, the implementation throws a {@link BootstrapException} with the appropriate exit
 * code (per {@link ExitCode}).
 *
 * <p>DEC-35-by-analogy: public interface in the {@code bootstrap} package root; implementation in
 * {@code bootstrap.internal}.
 *
 * <p>Story: E41S04 AC-ALGORITHM-VALIDATION, AC-OBSERVABILITY-EVENTS-BOOTSTRAP,
 * AC-EXIT-CODE-BOOTSTRAP.
 */
public interface BootstrapService {

    /**
     * Executes the full bootstrap sequence for the given worker configuration.
     *
     * <ol>
     *   <li>Emits {@code worker_started} INFO event.
     *   <li>Calls {@code GET /api/algorithms} via {@link
     *       de.vvwt.slotopt.worker.runtime.DispatcherClient}.
     *   <li>Emits {@code algorithms_announced} INFO event.
     *   <li>Validates that {@link WorkerConfig#signingAlgorithm()} is in the announced list.
     *   <li>Validates the chosen algorithm is not past its deprecation deadline (DEC-48).
     *   <li>Emits optional {@code algorithm_deprecation_warning} event + stderr/WARN if future
     *       deprecation.
     *   <li>Emits {@code algorithm_picked} INFO event.
     *   <li>Generates keypair and registers via {@code POST /api/register-key}.
     *   <li>Emits {@code key_registered} INFO event.
     * </ol>
     *
     * @param config worker configuration; must not be {@code null}
     * @return the registered worker ID (from the dispatcher's registration response)
     * @throws BootstrapException on any bootstrap failure, carrying the appropriate exit code
     */
    java.util.UUID run(WorkerConfig config) throws BootstrapException;
}
