package de.vvwt.slotopt.dispatcher.crypto.internal;

import de.vvwt.slotopt.dispatcher.crypto.AlgorithmAnnouncementService;
import de.vvwt.slotopt.dispatcher.crypto.AnnouncedAlgorithm;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link AlgorithmAnnouncementService}.
 *
 * <p>Aggregates all registered {@link SignatureVerifier} Spring beans via constructor injection and
 * projects their DEC-43 D1 metadata methods ({@link SignatureVerifier#algorithmId()}, {@link
 * SignatureVerifier#displayName()}, {@link SignatureVerifier#deprecationDate()}, {@link
 * SignatureVerifier#parameters()}) to {@link AnnouncedAlgorithm} records.
 *
 * <p>Adding a new algorithm in a future story is a code-only change: implement {@link
 * SignatureVerifier}, register it as a {@code @Component} bean — no changes to this class or the
 * interface required (DEC-43 D4 code-only-change contract per Brief D-3 option α).
 *
 * <h2>Package layout (DEC-35)</h2>
 *
 * <p>This implementation lives in {@code crypto.internal}. Consumers MUST type their dependencies
 * as {@link AlgorithmAnnouncementService} (the public interface in {@code crypto} root), never as
 * {@code DefaultAlgorithmAnnouncementService}.
 *
 * <h2>Degenerate case</h2>
 *
 * <p>If no {@link SignatureVerifier} beans are registered, the injected list is empty and {@link
 * #announcedAlgorithms()} returns an empty list (AC-EMPTY-VERIFIER-LIST — return-empty over
 * fail-fast per Brief Q-1 minimalism).
 *
 * <p>Spec: E40S02 AC-DEFAULT-ANNOUNCEMENT-SERVICE; DEC-35; DEC-43 § D1, D4.
 */
@Service
public class DefaultAlgorithmAnnouncementService implements AlgorithmAnnouncementService {

    private final List<SignatureVerifier> verifiers;

    /**
     * Constructs the service with the full list of registered {@link SignatureVerifier} beans.
     *
     * <p>Spring Boot injects all {@code @Component} / {@code @Service} / {@code @Bean} instances
     * implementing {@link SignatureVerifier} into this list. At V1 this is a single-element list
     * (Ed25519 only per DEC-43 D4).
     *
     * @param verifiers all registered {@link SignatureVerifier} beans; may be empty (degenerate
     *     case)
     */
    public DefaultAlgorithmAnnouncementService(List<SignatureVerifier> verifiers) {
        this.verifiers = verifiers;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Maps each registered {@link SignatureVerifier} to an {@link AnnouncedAlgorithm} using the
     * four DEC-43 D1 metadata methods from the E40S01 interface extension.
     */
    @Override
    public List<AnnouncedAlgorithm> announcedAlgorithms() {
        return verifiers.stream()
                .map(
                        v ->
                                new AnnouncedAlgorithm(
                                        v.algorithmId(),
                                        v.displayName(),
                                        v.deprecationDate(),
                                        v.parameters()))
                .toList();
    }
}
