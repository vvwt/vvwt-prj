package de.vvwt.slotopt.worker.identity;

import java.util.Set;

/**
 * Advertises the set of signature algorithms supported by this worker.
 *
 * <p>The dispatcher uses the advertised algorithm set (via the {@code supportedAlgorithms[]} field
 * in {@code pull-packet} responses) to route packets only to workers that can process them. In V1,
 * all workers advertise only {@code "Ed25519"}; future PQC-capable workers will declare additional
 * algorithms (e.g., {@code "ML-DSA-65"}).
 *
 * <p>This interface is authored against the E37S02 spec section (d) and DEC-43.
 *
 * <p>The canonical V1 implementation is {@link
 * de.vvwt.slotopt.worker.identity.internal.DefaultWorkerCapabilityProvider}.
 *
 * <p>See Story E37S03, DEC-35 (by-analogy), DEC-43 (algorithm-agility).
 */
public interface WorkerCapabilityProvider {

    /**
     * Returns the set of signature algorithm identifiers supported by this worker.
     *
     * <p>Algorithm identifiers use the JCE/JDK canonical name form (e.g., {@code "Ed25519"}). V1
     * workers return {@code Set.of("Ed25519")} only. The returned set must be non-null and
     * non-empty.
     *
     * @return an unmodifiable, non-null, non-empty set of algorithm identifier strings
     */
    Set<String> supportedAlgorithms();
}
