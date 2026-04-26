package de.vvwt.slotopt.worker.identity.internal;

import de.vvwt.slotopt.worker.identity.WorkerCapabilityProvider;
import java.util.Set;

/**
 * Default V1 implementation of {@link WorkerCapabilityProvider}.
 *
 * <p>Advertises {@code "Ed25519"} as the sole supported signature algorithm. This matches the V1
 * wire protocol where all workers use Ed25519 (per DEC-43 § D4; per E37S03
 * AC-WORKERCAPABILITYPROVIDER-NEW).
 *
 * <p>Future PQC-capable workers will provide their own {@link WorkerCapabilityProvider}
 * implementations returning multi-element sets (e.g., {@code Set.of("Ed25519", "ML-DSA-65")}).
 *
 * <p>See Story E37S03, DEC-35 (by-analogy: Default* in .internal, interface in root), DEC-43
 * (algorithm-agility).
 */
public final class DefaultWorkerCapabilityProvider implements WorkerCapabilityProvider {

    /** Constructs a {@code DefaultWorkerCapabilityProvider}. No dependencies required for V1. */
    public DefaultWorkerCapabilityProvider() {}

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code Set.of("Ed25519")} for V1. The returned set is unmodifiable.
     */
    @Override
    public Set<String> supportedAlgorithms() {
        return Set.of("Ed25519");
    }
}
