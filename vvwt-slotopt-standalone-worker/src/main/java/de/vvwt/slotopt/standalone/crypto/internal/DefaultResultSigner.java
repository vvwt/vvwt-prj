package de.vvwt.slotopt.standalone.crypto.internal;

import de.vvwt.slotopt.standalone.WorkerConfig;
import de.vvwt.slotopt.standalone.crypto.ResultSigner;
import de.vvwt.slotopt.standalone.crypto.SigningException;
import de.vvwt.slotopt.standalone.crypto.SubmitResultPayload;
import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Default implementation of {@link ResultSigner}.
 *
 * <p>Delegates all signing to the injected {@link WorkerKeyManager} instance (from {@code
 * vvwt-slotopt-worker-lib}). This class does NOT re-implement cryptographic operations — it is a
 * thin adapter per Brief C-22 and DEC-35-by-analogy.
 *
 * <h2>Canonical bytes</h2>
 *
 * <p>Per Brief D-10 + O-6 (ii): the dispatcher's {@code DefaultSubmitResultService}
 * JCS-canonicalizes {@code resultPayloadJson} (RFC 8785 key-sorted, no-whitespace UTF-8) before
 * signature verification. Therefore, the canonical bytes to sign are the UTF-8 encoding of {@link
 * SubmitResultPayload#resultPayloadJson()}. The caller (E41S05 runtime loop) is responsible for
 * providing a JCS-canonical JSON string.
 *
 * <h2>AC-WORKERKEYMANAGER-INSTANTIATION — Path 2 (chosen)</h2>
 *
 * <p>No public factory exists in {@code de.vvwt.slotopt.worker.identity.*} (Path 1 not available).
 * {@link DefaultResultSigner} therefore receives a {@link WorkerKeyManager} instance via
 * constructor injection — the wiring layer (E41S04 bootstrap story) is responsible for
 * instantiating {@code DefaultWorkerKeyManager}. This class contains zero imports from any {@code
 * .internal} package of other modules.
 *
 * <h2>AC-V1-SINGLE-ALGORITHM-CHECK</h2>
 *
 * <p>V1 supports only {@code Ed25519}. If {@link WorkerConfig#signingAlgorithm()} is not {@code
 * "Ed25519"} at construction time, an {@link IllegalStateException} is thrown immediately
 * (defensive fail-fast per Brief Q-5).
 *
 * <h2>AC-D-4-MECHANIC-INSTANTIATED-NOT-AUTHORED</h2>
 *
 * <p>The D-4 hybrid keypair-mismatch mechanic from {@code DefaultWorkerKeyManager} (E37S03
 * delivered) is invoked implicitly when the injected {@link WorkerKeyManager} is initialized. This
 * class contains zero references to file-naming, refuse-to-start, or keypair-rotation logic — those
 * concerns live in {@code vvwt-slotopt-worker-lib}.
 *
 * <p>DEC-35-by-analogy: implementation lives in {@code crypto.internal}; cross-module consumers
 * reference {@link ResultSigner} (the public interface) exclusively.
 *
 * <p>Story: E41S03 AC-DEFAULT-RESULT-SIGNER; AC-WORKERKEYMANAGER-INSTANTIATION;
 * AC-CANONICAL-BYTES-FROM-DELIVERED-DTO; AC-SIGNING-EXCEPTION; AC-V1-SINGLE-ALGORITHM-CHECK;
 * AC-D-4-MECHANIC-INSTANTIATED-NOT-AUTHORED.
 */
public final class DefaultResultSigner implements ResultSigner {

    /** V1 single-algorithm constraint per DEC-43 D4. */
    private static final String SUPPORTED_ALGORITHM = "Ed25519";

    private final WorkerKeyManager keyManager;

    /**
     * Constructs a {@code DefaultResultSigner}.
     *
     * @param keyManager the worker-lib {@link WorkerKeyManager} instance to delegate signing to;
     *     must not be {@code null}
     * @param config the worker configuration; {@link WorkerConfig#signingAlgorithm()} must be
     *     {@code "Ed25519"} (DEC-43 D4 V1 single-algorithm constraint)
     * @throws IllegalStateException if {@code config.signingAlgorithm()} is not {@code "Ed25519"}
     * @throws NullPointerException if {@code keyManager} or {@code config} is {@code null}
     */
    public DefaultResultSigner(WorkerKeyManager keyManager, WorkerConfig config) {
        Objects.requireNonNull(keyManager, "keyManager must not be null");
        Objects.requireNonNull(config, "config must not be null");
        if (!SUPPORTED_ALGORITHM.equals(config.signingAlgorithm())) {
            throw new IllegalStateException(
                    "V1 supports only Ed25519 signing algorithm (DEC-43 D4). "
                            + "Configured algorithm: "
                            + config.signingAlgorithm());
        }
        this.keyManager = keyManager;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Computes canonical bytes as {@code payload.resultPayloadJson().getBytes(UTF-8)} and
     * delegates to {@link WorkerKeyManager#signResult(byte[])}.
     *
     * @throws SigningException if the worker-lib signing operation fails
     * @throws NullPointerException if {@code payload} is {@code null}
     */
    @Override
    public byte[] signResult(SubmitResultPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        byte[] canonicalBytes = payload.resultPayloadJson().getBytes(StandardCharsets.UTF_8);
        try {
            return keyManager.signResult(canonicalBytes);
        } catch (IllegalStateException e) {
            throw new SigningException("Failed to sign result payload: " + e.getMessage(), e);
        }
    }
}
