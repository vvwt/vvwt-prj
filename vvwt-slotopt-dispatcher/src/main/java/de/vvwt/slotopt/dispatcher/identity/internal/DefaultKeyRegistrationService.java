package de.vvwt.slotopt.dispatcher.identity.internal;

import de.vvwt.slotopt.dispatcher.audit.AuditService;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifierRegistry;
import de.vvwt.slotopt.dispatcher.identity.DeprecatedAlgorithmException;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistration;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistrationService;
import de.vvwt.slotopt.dispatcher.identity.RegisterKeyRequest;
import de.vvwt.slotopt.dispatcher.identity.RegisterKeyResponse;
import de.vvwt.slotopt.dispatcher.identity.RegistrationOutcome;
import de.vvwt.slotopt.dispatcher.identity.RoleConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * <p>E37S06 amendment: {@link AuditService} dependency added for AC-IDENTITY-INTEGRATION-WIRING.
 * Each registration path records an audit event:
 *
 * <ul>
 *   <li>New registration → {@code KEY_REGISTERED}
 *   <li>Idempotent re-registration → {@code KEY_RE_REGISTRATION_IDEMPOTENT}
 *   <li>Role conflict → {@code KEY_ROLE_CONFLICT} (event recorded BEFORE the exception propagates)
 * </ul>
 *
 * <p>Audit failures do NOT block registration — see AC-AUDIT-FAILURE-MODE.
 *
 * <p>E40S03 amendment: {@link Clock} dependency added for AC-CLOCK-INJECTION. Deprecation-date
 * enforcement per DEC-43 D2/D3 (as amended by DEC-48): new registrations using an algorithm past
 * its DEC-48 deadline are rejected with {@link DeprecatedAlgorithmException} (→ HTTP 410 Gone via
 * the controller's {@code @ExceptionHandler}). Per DEC-43 D3 + DEC-48: {@code null} deprecation
 * date means the algorithm is not deprecated; the enforcement block is never entered.
 *
 * <p>Story: E37S05 (initial); E37S06 (audit wiring + sourceIp parameter); E40S03 (Clock +
 * deprecation enforcement)
 */
@Service
public class DefaultKeyRegistrationService implements KeyRegistrationService {

    private final KeyRegistrationRepository repository;
    private final SignatureVerifierRegistry verifierRegistry;
    private final AuditService auditService;
    private final Clock clock;

    public DefaultKeyRegistrationService(
            KeyRegistrationRepository repository,
            SignatureVerifierRegistry verifierRegistry,
            AuditService auditService,
            Clock clock) {
        this.repository = repository;
        this.verifierRegistry = verifierRegistry;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    public RegistrationOutcome register(RegisterKeyRequest request, String sourceIp) {
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

        // Behavior (1a): deprecation-date enforcement per DEC-43 D2/D3 + DEC-48
        // Inserted BEFORE key-length and persistence logic
        // (AC-DEPRECATION-CHECK-BEFORE-EXISTING-LOGIC).
        // DEC-48 boundary: accepted iff
        // clock.instant().isBefore(depDate.plusDays(1).atStartOfDay(UTC))
        // Null deprecation date → not deprecated → skip check (V1 universal case per Brief C-17).
        SignatureVerifier verifier = verifierOpt.get();
        LocalDate depDate = verifier.deprecationDate();
        if (depDate != null) {
            Instant deadline = depDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (!clock.instant().isBefore(deadline)) {
                throw new DeprecatedAlgorithmException(algorithm, depDate);
            }
        }

        // Behavior (2): validate public key length
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
                // (3a) role conflict → audit KEY_ROLE_CONFLICT then throw 409
                auditService.recordEvent(
                        "KEY_ROLE_CONFLICT",
                        request.workerId(),
                        sourceIp,
                        "{\"existingRole\":\""
                                + existing.getRole()
                                + "\","
                                + "\"requestedRole\":\""
                                + request.role()
                                + "\"}");
                throw new RoleConflictException(
                        request.workerId(), existing.getRole(), request.role());
            }
            // (3b) same role → idempotent re-register → audit KEY_RE_REGISTRATION_IDEMPOTENT
            auditService.recordEvent(
                    "KEY_RE_REGISTRATION_IDEMPOTENT",
                    request.workerId(),
                    sourceIp,
                    "{\"role\":\""
                            + existing.getRole()
                            + "\","
                            + "\"algorithm\":\""
                            + existing.getAlgorithm()
                            + "\"}");
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

        // Audit KEY_REGISTERED after successful persistence
        auditService.recordEvent(
                "KEY_REGISTERED",
                saved.getWorkerId(),
                sourceIp,
                "{\"role\":\""
                        + saved.getRole()
                        + "\","
                        + "\"algorithm\":\""
                        + saved.getAlgorithm()
                        + "\"}");

        return new RegistrationOutcome(
                new RegisterKeyResponse(
                        saved.getWorkerId(),
                        saved.getRole(),
                        saved.getAlgorithm(),
                        saved.getRegisteredAt()),
                true);
    }
}
