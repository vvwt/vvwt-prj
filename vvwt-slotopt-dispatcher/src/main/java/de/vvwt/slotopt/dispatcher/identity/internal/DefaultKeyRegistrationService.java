package de.vvwt.slotopt.dispatcher.identity.internal;

import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifierRegistry;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistration;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistrationService;
import de.vvwt.slotopt.dispatcher.identity.RegisterKeyRequest;
import de.vvwt.slotopt.dispatcher.identity.RegisterKeyResponse;
import de.vvwt.slotopt.dispatcher.identity.RegistrationOutcome;
import de.vvwt.slotopt.dispatcher.identity.RoleConflictException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link KeyRegistrationService}.
 *
 * <p>Per DEC-35: this implementation lives in {@code identity.internal}. All consumers reference
 * {@link KeyRegistrationService} (the public interface), never this class directly (DEC-36).
 *
 * <p>Named {@code DefaultKeyRegistrationService} per DEC-35 naming canon (no {@code I}-prefix on
 * the interface; {@code Default*} prefix on the implementation).
 *
 * <p>Story: E37S05; AC-KEY-REGISTRATION-SERVICE
 */
@Service
public class DefaultKeyRegistrationService implements KeyRegistrationService {

    private final KeyRegistrationRepository repository;
    private final SignatureVerifierRegistry verifierRegistry;

    public DefaultKeyRegistrationService(
            KeyRegistrationRepository repository, SignatureVerifierRegistry verifierRegistry) {
        this.repository = repository;
        this.verifierRegistry = verifierRegistry;
    }

    @Override
    public RegistrationOutcome register(RegisterKeyRequest request) {
        // Behavior (1): validate algorithm — null/empty/unknown → 400
        String algorithm = request.algorithm();
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException(
                    "algorithm field is required and must not be null or empty");
        }
        Optional<SignatureVerifier> verifierOpt = verifierRegistry.lookup(algorithm);
        if (verifierOpt.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown algorithm: '"
                            + algorithm
                            + "'. Supported algorithms: "
                            + verifierRegistry.supportedAlgorithms());
        }

        // Behavior (2): validate public key length
        SignatureVerifier verifier = verifierOpt.get();
        byte[] keyBytes = request.publicKeyBytes();
        if (keyBytes == null
                || keyBytes.length < verifier.minPublicKeyBytes()
                || keyBytes.length > verifier.maxPublicKeyBytes()) {
            int len = keyBytes == null ? 0 : keyBytes.length;
            throw new IllegalArgumentException(
                    "public key length "
                            + len
                            + " is out of range ["
                            + verifier.minPublicKeyBytes()
                            + ", "
                            + verifier.maxPublicKeyBytes()
                            + "] for algorithm '"
                            + algorithm
                            + "'");
        }

        // Behavior (3): check for existing registration
        Optional<KeyRegistration> existingOpt = repository.findByWorkerId(request.workerId());
        if (existingOpt.isPresent()) {
            KeyRegistration existing = existingOpt.get();
            if (!existing.getRole().equals(request.role())) {
                // (3a) role conflict → 409
                throw new RoleConflictException(
                        request.workerId(), existing.getRole(), request.role());
            }
            // (3b) same role → idempotent re-register
            return new RegistrationOutcome(
                    new RegisterKeyResponse(
                            existing.getWorkerId(),
                            existing.getRole(),
                            existing.getAlgorithm(),
                            existing.getRegisteredAt()),
                    false);
        }

        // Behavior (4): persist new registration
        KeyRegistration entity = new KeyRegistration();
        entity.setWorkerId(request.workerId());
        entity.setAlgorithm(algorithm);
        entity.setPublicKeyBytes(request.publicKeyBytes());
        entity.setRole(request.role());
        entity.setRegisteredAt(Instant.now());

        KeyRegistration saved = repository.save(entity);
        return new RegistrationOutcome(
                new RegisterKeyResponse(
                        saved.getWorkerId(),
                        saved.getRole(),
                        saved.getAlgorithm(),
                        saved.getRegisteredAt()),
                true);
    }
}
