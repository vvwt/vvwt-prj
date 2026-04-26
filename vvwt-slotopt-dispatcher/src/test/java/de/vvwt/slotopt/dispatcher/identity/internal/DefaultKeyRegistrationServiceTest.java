package de.vvwt.slotopt.dispatcher.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link DefaultKeyRegistrationService}.
 *
 * <p>Tests are authored against the {@link KeyRegistrationService} interface (DEC-36 cross-package
 * test typing rule: this test is in a different package than the subject's {@code internal}
 * package, so it references the interface type, not the implementation class).
 *
 * <p>RED-first per DEC-22 / AC-KEY-REGISTRATION-SERVICE: written before production class exists.
 *
 * <p>Story: E37S05
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultKeyRegistrationServiceTest {

    // DEC-36: test is in identity.internal package but subject's interface is in identity —
    // field declared as the PUBLIC INTERFACE type, not DefaultKeyRegistrationService
    private KeyRegistrationService service;

    @Mock private KeyRegistrationRepository repository;

    @Mock private SignatureVerifierRegistry verifierRegistry;

    @Mock private SignatureVerifier ed25519Verifier;

    @BeforeEach
    void setUp() {
        when(verifierRegistry.supportedAlgorithms()).thenReturn(Set.of("Ed25519"));
        when(verifierRegistry.lookup("Ed25519")).thenReturn(Optional.of(ed25519Verifier));
        when(ed25519Verifier.minPublicKeyBytes()).thenReturn(32);
        when(ed25519Verifier.maxPublicKeyBytes()).thenReturn(32);

        // Construct via implementation class (only allowed here since we are IN the same package)
        service = new DefaultKeyRegistrationService(repository, verifierRegistry);
    }

    // -------------------------------------------------------------------------
    // AC-KEY-REGISTRATION-SERVICE — behavior (1): unknown algorithm → 400
    // -------------------------------------------------------------------------

    @Test
    void unknownAlgorithmThrowsIllegalArgumentException() {
        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "ML-DSA-65", new byte[32]);
        when(verifierRegistry.lookup("ML-DSA-65")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ML-DSA-65");
    }

    @Test
    void nullAlgorithmThrowsIllegalArgumentException() {
        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", null, new byte[32]);

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // AC-KEY-REGISTRATION-SERVICE — behavior (2): key-length out of range → 400
    // -------------------------------------------------------------------------

    @Test
    void keyTooShortThrowsIllegalArgumentException() {
        byte[] shortKey = new byte[16]; // less than 32
        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "Ed25519", shortKey);

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public key");
    }

    @Test
    void keyTooLongThrowsIllegalArgumentException() {
        byte[] longKey = new byte[64]; // more than 32
        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "Ed25519", longKey);

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public key");
    }

    // -------------------------------------------------------------------------
    // AC-KEY-REGISTRATION-SERVICE — behavior (3a): role conflict → RoleConflictException
    // -------------------------------------------------------------------------

    @Test
    void conflictingRoleThrowsRoleConflictException() {
        UUID workerId = UUID.randomUUID();
        KeyRegistration existing = new KeyRegistration();
        existing.setId(1L);
        existing.setWorkerId(workerId);
        existing.setRole("worker");
        existing.setAlgorithm("Ed25519");
        existing.setPublicKeyBytes(new byte[32]);
        existing.setRegisteredAt(Instant.now());
        when(repository.findByWorkerId(workerId)).thenReturn(Optional.of(existing));

        RegisterKeyRequest req =
                new RegisterKeyRequest(workerId, "submitter", "Ed25519", new byte[32]);

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(RoleConflictException.class)
                .satisfies(
                        ex -> {
                            RoleConflictException rce = (RoleConflictException) ex;
                            assertThat(rce.getWorkerId()).isEqualTo(workerId);
                            assertThat(rce.getExistingRole()).isEqualTo("worker");
                            assertThat(rce.getRequestedRole()).isEqualTo("submitter");
                        });
    }

    // -------------------------------------------------------------------------
    // AC-KEY-REGISTRATION-SERVICE — behavior (3b): idempotent re-register
    // -------------------------------------------------------------------------

    @Test
    void idempotentReRegisterReturnExisting() {
        UUID workerId = UUID.randomUUID();
        Instant registeredAt = Instant.now();
        KeyRegistration existing = new KeyRegistration();
        existing.setId(1L);
        existing.setWorkerId(workerId);
        existing.setRole("worker");
        existing.setAlgorithm("Ed25519");
        existing.setPublicKeyBytes(new byte[32]);
        existing.setRegisteredAt(registeredAt);
        when(repository.findByWorkerId(workerId)).thenReturn(Optional.of(existing));

        // Same workerId, same role, same algorithm → idempotent
        RegisterKeyRequest req =
                new RegisterKeyRequest(workerId, "worker", "Ed25519", new byte[32]);

        RegistrationOutcome outcome = service.register(req);

        assertThat(outcome.isNew()).isFalse();
        RegisterKeyResponse response = outcome.response();
        assertThat(response.workerId()).isEqualTo(workerId);
        assertThat(response.role()).isEqualTo("worker");
        assertThat(response.algorithm()).isEqualTo("Ed25519");
        assertThat(response.registeredAt()).isEqualTo(registeredAt);
    }

    // -------------------------------------------------------------------------
    // AC-KEY-REGISTRATION-SERVICE — behavior (4): persist new registration
    // -------------------------------------------------------------------------

    @Test
    void newRegistrationPersistsAndReturnsResponse() {
        UUID workerId = UUID.randomUUID();
        when(repository.findByWorkerId(workerId)).thenReturn(Optional.empty());
        when(repository.save(any(KeyRegistration.class)))
                .thenAnswer(
                        inv -> {
                            KeyRegistration saved = inv.getArgument(0);
                            saved.setId(42L);
                            return saved;
                        });

        RegisterKeyRequest req =
                new RegisterKeyRequest(workerId, "worker", "Ed25519", new byte[32]);

        RegistrationOutcome outcome = service.register(req);

        assertThat(outcome.isNew()).isTrue();
        RegisterKeyResponse response = outcome.response();
        assertThat(response.workerId()).isEqualTo(workerId);
        assertThat(response.role()).isEqualTo("worker");
        assertThat(response.algorithm()).isEqualTo("Ed25519");
        assertThat(response.registeredAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC-ALGORITHM-FIELD-REQUIRED: empty algorithm → 400
    // -------------------------------------------------------------------------

    @Test
    void emptyAlgorithmThrowsIllegalArgumentException() {
        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "", new byte[32]);

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
