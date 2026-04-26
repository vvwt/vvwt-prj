package de.vvwt.slotopt.dispatcher.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.dispatcher.audit.AuditService;
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
 * <p>DEC-36: test is in identity.internal package (same as subject) — white-box access permitted.
 * Field declared as KeyRegistrationService (public interface) per DEC-36 cross-package spirit.
 *
 * <p>E37S06 amendment: AuditService mock added. AuditService is a COMMAND collaborator — the audit
 * invocation IS the observable behaviour contract. verify(auditService) is appropriate per
 * testing-anti-patterns-java.md (command vs. query distinction).
 *
 * <p>Story: E37S05 (initial); E37S06 (audit assertions added)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultKeyRegistrationServiceTest {

    private KeyRegistrationService service;

    @Mock private KeyRegistrationRepository repository;
    @Mock private SignatureVerifierRegistry verifierRegistry;
    @Mock private SignatureVerifier ed25519Verifier;
    @Mock private AuditService auditService;

    private static final String SOURCE_IP = "10.0.0.1";

    @BeforeEach
    void setUp() {
        when(verifierRegistry.supportedAlgorithms()).thenReturn(Set.of("Ed25519"));
        when(verifierRegistry.lookup("Ed25519")).thenReturn(Optional.of(ed25519Verifier));
        when(ed25519Verifier.minPublicKeyBytes()).thenReturn(32);
        when(ed25519Verifier.maxPublicKeyBytes()).thenReturn(32);

        service = new DefaultKeyRegistrationService(repository, verifierRegistry, auditService);
    }

    @Test
    void unknownAlgorithmThrowsIllegalArgumentException() {
        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "ML-DSA-65", new byte[32]);
        when(verifierRegistry.lookup("ML-DSA-65")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(req, SOURCE_IP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ML-DSA-65");
    }

    @Test
    void nullAlgorithmThrowsIllegalArgumentException() {
        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", null, new byte[32]);

        assertThatThrownBy(() -> service.register(req, SOURCE_IP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyTooShortThrowsIllegalArgumentException() {
        byte[] shortKey = new byte[16];
        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "Ed25519", shortKey);

        assertThatThrownBy(() -> service.register(req, SOURCE_IP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public key");
    }

    @Test
    void keyTooLongThrowsIllegalArgumentException() {
        byte[] longKey = new byte[64];
        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "Ed25519", longKey);

        assertThatThrownBy(() -> service.register(req, SOURCE_IP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public key");
    }

    @Test
    void conflictingRoleThrowsRoleConflictExceptionAndAuditsConflict() {
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

        assertThatThrownBy(() -> service.register(req, SOURCE_IP))
                .isInstanceOf(RoleConflictException.class)
                .satisfies(
                        ex -> {
                            RoleConflictException rce = (RoleConflictException) ex;
                            assertThat(rce.getWorkerId()).isEqualTo(workerId);
                            assertThat(rce.getExistingRole()).isEqualTo("worker");
                            assertThat(rce.getRequestedRole()).isEqualTo("submitter");
                        });

        // AuditService is a COMMAND collaborator — verify() is appropriate
        verify(auditService)
                .recordEvent(eq("KEY_ROLE_CONFLICT"), eq(workerId), eq(SOURCE_IP), anyString());
    }

    @Test
    void idempotentReRegisterReturnExistingAndAuditsIdempotent() {
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

        RegisterKeyRequest req =
                new RegisterKeyRequest(workerId, "worker", "Ed25519", new byte[32]);

        RegistrationOutcome outcome = service.register(req, SOURCE_IP);

        assertThat(outcome.isNew()).isFalse();
        RegisterKeyResponse response = outcome.response();
        assertThat(response.workerId()).isEqualTo(workerId);
        assertThat(response.role()).isEqualTo("worker");
        assertThat(response.algorithm()).isEqualTo("Ed25519");
        assertThat(response.registeredAt()).isEqualTo(registeredAt);

        verify(auditService)
                .recordEvent(
                        eq("KEY_RE_REGISTRATION_IDEMPOTENT"),
                        eq(workerId),
                        eq(SOURCE_IP),
                        anyString());
    }

    @Test
    void newRegistrationPersistsAndAuditsKeyRegistered() {
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

        RegistrationOutcome outcome = service.register(req, SOURCE_IP);

        assertThat(outcome.isNew()).isTrue();
        RegisterKeyResponse response = outcome.response();
        assertThat(response.workerId()).isEqualTo(workerId);
        assertThat(response.role()).isEqualTo("worker");
        assertThat(response.algorithm()).isEqualTo("Ed25519");
        assertThat(response.registeredAt()).isNotNull();

        verify(auditService)
                .recordEvent(eq("KEY_REGISTERED"), eq(workerId), eq(SOURCE_IP), anyString());
    }

    @Test
    void emptyAlgorithmThrowsIllegalArgumentException() {
        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "", new byte[32]);

        assertThatThrownBy(() -> service.register(req, SOURCE_IP))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
