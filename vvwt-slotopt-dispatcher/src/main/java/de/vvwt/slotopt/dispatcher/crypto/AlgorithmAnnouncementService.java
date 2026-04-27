package de.vvwt.slotopt.dispatcher.crypto;

import java.util.List;

/**
 * Service interface for announcing the dispatcher's supported signature algorithms per DEC-43 D1.
 *
 * <p>Lives in the {@code crypto} package root (public surface) per DEC-35 naming convention
 * (service interfaces in the public package, implementations in {@code .internal}). The canonical
 * implementation is {@link
 * de.vvwt.slotopt.dispatcher.crypto.internal.DefaultAlgorithmAnnouncementService}.
 *
 * <p>Cross-package consumers and test classes MUST type-reference this interface, NOT the
 * implementation class (DEC-36 cross-package test typing rule).
 *
 * <p>Spec: E40S02 AC-ANNOUNCEMENT-SERVICE-INTERFACE; DEC-35; DEC-36; DEC-43 § D1.
 */
public interface AlgorithmAnnouncementService {

    /**
     * Returns the immutable list of signature algorithms currently announced by this dispatcher.
     *
     * <p>At V1, returns a single-entry list containing {@code Ed25519} (per DEC-43 D4). Future
     * algorithm entries are added by registering additional {@link SignatureVerifier} Spring beans
     * — no code change to this service or its implementation is required (DEC-43 D4
     * code-only-change contract).
     *
     * <p>Returns an empty list if no {@link SignatureVerifier} beans are registered (degenerate
     * case per AC-EMPTY-VERIFIER-LIST — return-empty over fail-fast).
     *
     * @return the list of announced algorithms; never {@code null}; may be empty
     */
    List<AnnouncedAlgorithm> announcedAlgorithms();
}
