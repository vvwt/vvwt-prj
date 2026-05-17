// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link AdminCredentialsBootstrap}.
 *
 * <p>Mocking is JUSTIFIED for this SUT: the orchestrator's only job is to wire the collaborators
 * ({@link PasswordGenerator}, {@link AdminCredentialsDao}, {@link PasswordEncoder}) correctly. The
 * collaborators' own behaviour is fully tested in {@code PasswordGeneratorTest} (E15S01) and {@code
 * AdminCredentialsDaoTest} (E15S02). Testing only the orchestration logic here avoids re-testing
 * the collaborators. Per story E15S03 AC2 and DEC-22 notes: "the one story where mocking IS
 * appropriate".
 *
 * <h2>TDD cycle (DEC-22)</h2>
 *
 * <p>This file was committed BEFORE {@link AdminCredentialsBootstrap} existed (RED state:
 * compilation error). See git history for the RED commit preceding the GREEN implementation.
 *
 * @see AdminCredentialsBootstrap
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E15S03.story.md">Story
 *     E15S03</a>
 */
@ExtendWith(MockitoExtension.class)
class AdminCredentialsBootstrapTest {

    @Mock private AdminCredentialsDao dao;

    @Mock private PasswordGenerator generator;

    @Mock private PasswordEncoder encoder;

    @Mock private ApplicationArguments args;

    @InjectMocks private DefaultAdminCredentialsBootstrap bootstrap;

    // -------------------------------------------------------------------------
    // T1 / AC3 — First-start: four interactions in order (generate → encode → insertNew → log)
    // -------------------------------------------------------------------------

    /**
     * T1 (AC3 — first-start four-interaction order): When the Dao returns empty, the orchestrator
     * invokes generator → encoder → Dao.insertNew in that order. All four interactions are asserted
     * via InOrder (AC3: "all four interactions are asserted in order").
     */
    @Test
    void firstStart_emptyDao_generatesHashesInsertsInOrder() throws Exception {
        when(dao.findExisting()).thenReturn(Optional.empty());
        when(generator.generate()).thenReturn("TestPassword01");
        when(encoder.encode("TestPassword01")).thenReturn("$2a$10$testhash");

        bootstrap.run(args);

        InOrder order = inOrder(dao, generator, encoder);
        order.verify(dao).findExisting();
        order.verify(generator).generate();
        order.verify(encoder).encode("TestPassword01");
        order.verify(dao).insertNew(any(UUID.class), eq("$2a$10$testhash"));
    }

    // -------------------------------------------------------------------------
    // T2 / AC4 — Subsequent-start: Generator NEVER called; "set at first start" log emitted
    // -------------------------------------------------------------------------

    /**
     * T2 (AC4 — subsequent-start no-op): When the Dao returns an existing record, the Generator is
     * NEVER called. The "set at first start — check startup log" log line MUST be emitted
     * (preserved behaviour). We verify the observable side effect: generator is never invoked.
     */
    @Test
    void subsequentStart_existingCredentials_generatorNeverCalled() throws Exception {
        AdminCredentialsDao.CredentialRecord existing =
                new AdminCredentialsDao.CredentialRecord(UUID.randomUUID(), "$2a$10$existinghash");
        when(dao.findExisting()).thenReturn(Optional.of(existing));

        bootstrap.run(args);

        verify(generator, never()).generate();
        verify(encoder, never()).encode(anyString());
        verify(dao, never()).insertNew(any(), anyString());
    }

    // -------------------------------------------------------------------------
    // T3 / AC5 — Race path: DIVE on insert → re-query → no exception leaked
    // -------------------------------------------------------------------------

    /**
     * T3 (AC5 — concurrent-start race fallback): When {@code dao.insertNew()} throws {@link
     * DataIntegrityViolationException}, the orchestrator re-queries via {@code dao.findExisting()}
     * and uses the existing hash. No exception is leaked to the caller.
     */
    @Test
    void racePath_diveOnInsert_requeriesAndCompletes() throws Exception {
        AdminCredentialsDao.CredentialRecord winnerRecord =
                new AdminCredentialsDao.CredentialRecord(UUID.randomUUID(), "$2a$10$winnerhash");

        when(dao.findExisting())
                .thenReturn(Optional.empty()) // first call: empty (trigger first-start path)
                .thenReturn(Optional.of(winnerRecord)); // second call: winner's record
        when(generator.generate()).thenReturn("GeneratedPwd1");
        when(encoder.encode("GeneratedPwd1")).thenReturn("$2a$10$encodedhash");
        DataIntegrityViolationException race =
                new DataIntegrityViolationException("singleton_guard constraint violated");
        org.mockito.Mockito.doThrow(race).when(dao).insertNew(any(UUID.class), anyString());

        // Must NOT throw
        bootstrap.run(args);

        // Verify re-query was performed after the DIVE
        InOrder order = inOrder(dao);
        order.verify(dao).findExisting(); // first call
        order.verify(dao).insertNew(any(UUID.class), anyString());
        order.verify(dao).findExisting(); // re-query after DIVE
    }

    // -------------------------------------------------------------------------
    // T4 / AC6 — Encoder failure: no insertNew called, exception propagates
    // -------------------------------------------------------------------------

    /**
     * T4 (AC6 — encoder failure): When the {@link PasswordEncoder} throws during hashing, the
     * orchestrator must NOT invoke {@link AdminCredentialsDao#insertNew}. The exception propagates
     * to the caller.
     */
    @Test
    void encoderFailure_noInsertAttempted_exceptionPropagates() throws Exception {
        when(dao.findExisting()).thenReturn(Optional.empty());
        when(generator.generate()).thenReturn("GeneratedPwd1");
        when(encoder.encode(anyString())).thenThrow(new RuntimeException("encoder failed"));

        assertThatThrownBy(() -> bootstrap.run(args))
                .as("AC6: encoder failure must propagate — no half-written state")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("encoder failed");

        verify(dao, never()).insertNew(any(), anyString());
    }

    // -------------------------------------------------------------------------
    // T5 / AC7 — @Order(2) annotation declared
    // -------------------------------------------------------------------------

    /**
     * T5 (AC7 — @Order(2) annotation): {@link AdminCredentialsBootstrap} must declare
     * {@code @Order(2)} to ensure it runs after {@code DefaultTenantBootstrapRunner}
     * ({@code @Order(1)}).
     */
    @Test
    void orderAnnotation_isTwo() {
        Order order = DefaultAdminCredentialsBootstrap.class.getAnnotation(Order.class);

        assertThat(order)
                .as("AC7: AdminCredentialsBootstrap must declare @Order annotation")
                .isNotNull();

        assertThat(order.value())
                .as(
                        "AC7: @Order value must be 2 (runs after DefaultTenantBootstrapRunner"
                                + " @Order(1))")
                .isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // T6 / AC8 — ApplicationModulesTest — Modulith verify remains green
    // -------------------------------------------------------------------------

    /**
     * T6 (AC8 — Modulith verify): {@code ApplicationModules.verify()} must remain green after
     * adding {@code AdminCredentialsBootstrap} to {@code de.vvwt.tm.auth.internal}. The class must
     * NOT import from {@code de.vvwt.tm.tenant.internal.*}.
     */
    @Test
    void applicationModulesVerify_remainsGreen() {
        org.springframework.modulith.core.ApplicationModules.of(
                        de.vvwt.tm.TournamentManagerApplication.class)
                .verify();
    }

    // -------------------------------------------------------------------------
    // T7 / AC9 — Package placement: new class is in auth.internal
    // -------------------------------------------------------------------------

    /**
     * T7 (AC9 — package placement): {@link AdminCredentialsBootstrap} (new reconstruction-in-place
     * class) must be in {@code de.vvwt.tm.auth.internal}, not in {@code de.vvwt.tm.auth}.
     */
    @Test
    void packagePlacement_newBootstrap_isInAuthInternal() {
        String packageName = DefaultAdminCredentialsBootstrap.class.getPackageName();

        assertThat(packageName)
                .as("AC9: AdminCredentialsBootstrap (new) must be in de.vvwt.tm.auth.internal")
                .isEqualTo("de.vvwt.tm.auth.internal");
    }
}
