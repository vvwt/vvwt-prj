package de.vvwt.slotopt.standalone.http;

/**
 * Client interface for communicating with the vvwt-slotopt-dispatcher HTTP API.
 *
 * <p>Provides the bootstrap-phase operations needed before the worker enters its runtime polling
 * loop: fetching the announced algorithm list (DEC-43 D2) and registering the worker's public key.
 *
 * <p>DEC-35-by-analogy: public interface in the {@code http} package root. The canonical
 * implementation is {@link de.vvwt.slotopt.standalone.http.internal.DefaultDispatcherClient} in the
 * {@code http.internal} package.
 *
 * <p>Story: E41S04 AC-DISPATCHER-CLIENT-INTERFACE.
 */
public interface DispatcherClient {

    /**
     * Fetches the dispatcher's announced signature algorithm list via {@code GET /api/algorithms}.
     *
     * <p>Per DEC-43 D1: the dispatcher announces its supported algorithms with optional deprecation
     * dates. The worker reads this list before keypair generation to choose a compliant algorithm
     * (DEC-43 D2 free-choice rule).
     *
     * @return response wrapping the announced algorithm list; never {@code null}
     * @throws DispatcherException if the request fails (I/O error, non-200 HTTP status)
     */
    AnnouncedAlgorithmsResponse fetchAnnouncedAlgorithms() throws DispatcherException;

    /**
     * Registers the worker's public key with the dispatcher via {@code POST /api/register-key}.
     *
     * <p>Per DEC-6 + DEC-43 D2: the {@code algorithm} field in the request names the chosen
     * algorithm from the announced list.
     *
     * @param request the registration request carrying workerId, role, algorithm, and
     *     publicKeyBytes
     * @return the dispatcher's registration response including the assigned workerId
     * @throws DispatcherException if the request fails (I/O error, HTTP 410 for deprecated
     *     algorithm, other non-200 status)
     */
    RegisterKeyResponse registerKey(RegisterKeyRequest request) throws DispatcherException;
}
