// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
 * <p>E40S03 amendment: Clock injection added. New tests cover deprecation-date enforcement per
 * DEC-43 D2/D3 (as amended by DEC-48). Boundary tests use fixed Clock to assert DEC-48 semantics:
 * entire deprecation_date day accepted; first instant of next day (midnight UTC) rejected.
 *
 * <p>Story: E37S05 (initial); E37S06 (audit assertions added); E40S03 (clock + deprecation tests)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultKeyRegistrationServiceTest {

    private KeyRegistrationService service;

    @Mock private KeyRegistrationRepository repository;
    @Mock private SignatureVerifierRegistry verifierRegistry;
    @Mock private SignatureVerifier ed25519Verifier;
    @Mock private AuditService auditService;

    /** Fixed clock pointing to a clearly non-deprecated instant (2026-01-01T12:00:00Z). */
    private static final Clock NON_DEPRECATED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    private static final String SOURCE_IP = "10.0.0.1";

    @BeforeEach
    void setUp() {
        when(verifierRegistry.supportedAlgorithms()).thenReturn(Set.of("Ed25519"));
        when(verifierRegistry.lookup("Ed25519")).thenReturn(Optional.of(ed25519Verifier));
        when(ed25519Verifier.minPublicKeyBytes()).thenReturn(32);
        when(ed25519Verifier.maxPublicKeyBytes()).thenReturn(32);
        // V1: Ed25519 is not deprecated (null deprecation date)
        when(ed25519Verifier.deprecationDate()).thenReturn(null);

        service =
                new DefaultKeyRegistrationService(
                        repository, verifierRegistry, auditService, NON_DEPRECATED_CLOCK);
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

    // -------------------------------------------------------------------------
    // E40S03 tests — deprecation-date enforcement (DEC-43 D2/D3 + DEC-48)
    // RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE
    // -------------------------------------------------------------------------

    /**
     * AC-DEPRECATION-CHECK-BEFORE-EXISTING-LOGIC: When clock is at the DEC-48 deadline (first
     * instant of next day after deprecation_date), throw DeprecatedAlgorithmException.
     *
     * <p>RED-first: fails before Clock field is added to DefaultKeyRegistrationService constructor.
     */
    @Test
    void deprecatedAlgorithmThrowsWhenClockIsAtDeadline() {
        LocalDate deprecationDate = LocalDate.of(2026, 12, 31);
        // Deadline per DEC-48: 2027-01-01T00:00:00Z (first instant of next day)
        Instant deadline = deprecationDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Clock clockAtDeadline = Clock.fixed(deadline, ZoneOffset.UTC);

        when(ed25519Verifier.deprecationDate()).thenReturn(deprecationDate);
        KeyRegistrationService serviceWithExpiredClock =
                new DefaultKeyRegistrationService(
                        repository, verifierRegistry, auditService, clockAtDeadline);

        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "Ed25519", new byte[32]);

        assertThatThrownBy(() -> serviceWithExpiredClock.register(req, SOURCE_IP))
                .isInstanceOf(DeprecatedAlgorithmException.class)
                .satisfies(
                        ex -> {
                            DeprecatedAlgorithmException dae = (DeprecatedAlgorithmException) ex;
                            assertThat(dae.algorithmId()).isEqualTo("Ed25519");
                            assertThat(dae.deprecationDate()).isEqualTo(deprecationDate);
                        });
    }

    /**
     * AC-DEPRECATION-AT-EXACT-DEADLINE (DEC-48 semantics): Clock at 23:59:59Z on deprecation day →
     * ACCEPTED. The entire deprecation_date day is accepted per DEC-48.
     *
     * <p>DEC-48: deprecation_date is the LAST day the algorithm is accepted. Rejection starts at
     * the first instant of the next day (midnight UTC).
     */
    @Test
    void deprecatedAlgorithmAcceptedWhenClockIsOneSecondBeforeMidnight() {
        LocalDate deprecationDate = LocalDate.of(2026, 12, 31);
        // 23:59:59Z on deprecation day — BEFORE the DEC-48 deadline (next day midnight UTC)
        Instant oneSecondBeforeMidnight = Instant.parse("2026-12-31T23:59:59Z");
        Clock clockBeforeMidnight = Clock.fixed(oneSecondBeforeMidnight, ZoneOffset.UTC);

        when(ed25519Verifier.deprecationDate()).thenReturn(deprecationDate);
        when(repository.findByWorkerId(any())).thenReturn(Optional.empty());
        when(repository.save(any(KeyRegistration.class)))
                .thenAnswer(
                        inv -> {
                            KeyRegistration saved = inv.getArgument(0);
                            saved.setId(1L);
                            return saved;
                        });

        KeyRegistrationService serviceBeforeMidnight =
                new DefaultKeyRegistrationService(
                        repository, verifierRegistry, auditService, clockBeforeMidnight);

        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "Ed25519", new byte[32]);

        // Should NOT throw — 23:59:59Z is ACCEPTED per DEC-48
        RegistrationOutcome outcome = serviceBeforeMidnight.register(req, SOURCE_IP);
        assertThat(outcome.isNew()).isTrue();
    }

    /**
     * AC-DEPRECATION-AT-EXACT-DEADLINE (DEC-48 boundary): Clock at exactly midnight of next day →
     * REJECTED. 2027-01-01T00:00:00Z is the first instant of the day after deprecation_date
     * 2026-12-31 per DEC-48.
     */
    @Test
    void deprecatedAlgorithmRejectedAtMidnightOfNextDay() {
        LocalDate deprecationDate = LocalDate.of(2026, 12, 31);
        // First instant of next day — DEC-48 rejection threshold
        Instant midnightNextDay = Instant.parse("2027-01-01T00:00:00Z");
        Clock clockAtMidnight = Clock.fixed(midnightNextDay, ZoneOffset.UTC);

        when(ed25519Verifier.deprecationDate()).thenReturn(deprecationDate);
        KeyRegistrationService serviceAtMidnight =
                new DefaultKeyRegistrationService(
                        repository, verifierRegistry, auditService, clockAtMidnight);

        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "Ed25519", new byte[32]);

        assertThatThrownBy(() -> serviceAtMidnight.register(req, SOURCE_IP))
                .isInstanceOf(DeprecatedAlgorithmException.class);
    }

    /**
     * AC-V1-NULL-DEPRECATION-NOOP: With Ed25519 deprecationDate() returning null (V1 default per
     * E40S01), the deprecation check never fires. Existing registration flow proceeds normally.
     *
     * <p>RED-first: written against the new constructor signature before the null-check is added.
     */
    @Test
    void nullDeprecationDateNeverFiresDeprecationCheck() {
        // ed25519Verifier.deprecationDate() returns null (set up in @BeforeEach)
        when(repository.findByWorkerId(any())).thenReturn(Optional.empty());
        when(repository.save(any(KeyRegistration.class)))
                .thenAnswer(
                        inv -> {
                            KeyRegistration saved = inv.getArgument(0);
                            saved.setId(1L);
                            return saved;
                        });

        RegisterKeyRequest req =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "Ed25519", new byte[32]);

        // Should complete without throwing DeprecatedAlgorithmException
        RegistrationOutcome outcome = service.register(req, SOURCE_IP);
        assertThat(outcome.isNew()).isTrue();
    }
}
