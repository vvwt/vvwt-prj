package de.vvwt.slotopt.worker.runtime;

import java.util.Optional;

/**
 * Client interface for communicating with the vvwt-slotopt-dispatcher HTTP API.
 *
 * <p>Provides both bootstrap-phase and runtime-phase operations:
 *
 * <ul>
 *   <li>Bootstrap: fetching the announced algorithm list (DEC-43 D2) and registering the worker's
 *       public key.
 *   <li>Runtime: pulling packets ({@link #pullPacketOptional}) and submitting results ({@link
 *       #submitResult}).
 * </ul>
 *
 * <p>DEC-35-by-analogy: public interface in the {@code runtime} package root. The canonical
 * implementation is {@link de.vvwt.slotopt.worker.runtime.internal.DefaultDispatcherClient} in the
 * {@code runtime.internal} package.
 *
 * <p>DEC-11: no compile dependency on {@code vvwt-slotopt-dispatcher} is introduced; only the HTTP
 * wire shape must match.
 *
 * <p>Story: E41S04 AC-DISPATCHER-CLIENT-INTERFACE (moved to E63S01 shared library); E41S05
 * AC-PULL-PACKET-WITH-CAPABILITY-ADVERTISEMENT, AC-SUBMIT-RESULT-WITH-ALGORITHM.
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

    /**
     * Pulls the next available packet via {@code POST /api/pull-packet}.
     *
     * <p>Sends {@code workerId} and {@code supportedAlgorithms} per DEC-43 D2 (Brief O-6 (i)).
     *
     * @param request the pull-packet request carrying workerId and supportedAlgorithms
     * @return an {@link Optional} containing the packet response (HTTP 200), or empty (HTTP 204 —
     *     no packets available)
     * @throws DispatcherException if the request fails (I/O error, HTTP 4xx/5xx including HTTP 410)
     */
    Optional<PullPacketResponse> pullPacketOptional(PullPacketRequest request)
            throws DispatcherException;

    /**
     * Pulls the next available packet, throwing if the response is 204 (no content).
     *
     * <p>Convenience method equivalent to {@link #pullPacketOptional} that throws {@link
     * DispatcherException} for HTTP 204. Prefer {@link #pullPacketOptional} for loop usage where
     * 204 is normal (backoff expected).
     *
     * @param request the pull-packet request
     * @return the packet response; never {@code null}
     * @throws DispatcherException if the request fails, including HTTP 204 (no content)
     */
    default PullPacketResponse pullPacket(PullPacketRequest request) throws DispatcherException {
        Optional<PullPacketResponse> opt = pullPacketOptional(request);
        return opt.orElseThrow(
                () ->
                        new DispatcherException(
                                204, "POST /api/pull-packet returned HTTP 204", null));
    }

    /**
     * Submits a computation result via {@code POST /api/submit-result}.
     *
     * <p>The {@code algorithm} field on the request MUST match the algorithm the worker registered
     * with (DEC-43 D2 binding is per-registration).
     *
     * @param request the submit-result request carrying packetId, workerId, algorithm, signature,
     *     and resultPayloadJson
     * @return the dispatcher's response indicating whether the result was accepted or superseded
     * @throws DispatcherException if the request fails (I/O error, HTTP 410 for deprecated
     *     algorithm at submit time, other non-2xx status)
     */
    SubmitResultResponse submitResult(SubmitResultRequest request) throws DispatcherException;
}
