// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifierRegistry;
import de.vvwt.slotopt.dispatcher.identity.testsupport.DispatcherDaoTestSupport;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link KeyRegistrationService} → {@link
 * de.vvwt.slotopt.dispatcher.audit.AuditService} wiring (AC-IDENTITY-INTEGRATION-WIRING).
 *
 * <p>Verifies that each registration path (KEY_REGISTERED, KEY_RE_REGISTRATION_IDEMPOTENT,
 * KEY_ROLE_CONFLICT) produces the expected {@code audit_entry} row, verified via direct JDBC per
 * DEC-26 Rule 2.
 *
 * <p>DEC-36: this test is in the {@code identity} package (different from {@code identity.internal}
 * and {@code audit.internal}) — references {@link KeyRegistrationService} and {@link
 * de.vvwt.slotopt.dispatcher.audit.AuditService} via their public interfaces only.
 *
 * <p>Story: E37S06; AC-IDENTITY-INTEGRATION-WIRING
 */
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:audit-integration-${random.uuid};"
                    + "DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.locations=classpath:db/migration/identity,classpath:db/migration/audit",
            "spring.flyway.enabled=true"
        })
@Import({
    de.vvwt.slotopt.dispatcher.identity.internal.DefaultKeyRegistrationService.class,
    de.vvwt.slotopt.dispatcher.audit.internal.DefaultAuditService.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class KeyRegistrationAuditIT {

    @Autowired private KeyRegistrationService keyRegistrationService;

    @Autowired private DataSource dataSource;

    @MockitoBean private SignatureVerifierRegistry verifierRegistry;

    @MockitoBean private SignatureVerifier ed25519Verifier;

    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        assertDb = DispatcherDaoTestSupport.assertDbOf(dataSource);

        // Wire up the SignatureVerifierRegistry mock
        org.mockito.Mockito.when(verifierRegistry.lookup("Ed25519"))
                .thenReturn(Optional.of(ed25519Verifier));
        org.mockito.Mockito.when(verifierRegistry.supportedAlgorithms())
                .thenReturn(Set.of("Ed25519"));
        org.mockito.Mockito.when(ed25519Verifier.minPublicKeyBytes()).thenReturn(32);
        org.mockito.Mockito.when(ed25519Verifier.maxPublicKeyBytes()).thenReturn(32);
    }

    // -------------------------------------------------------------------------
    // AC-IDENTITY-INTEGRATION-WIRING (a): KEY_REGISTERED → AuditEntry row
    // -------------------------------------------------------------------------

    @Test
    void newRegistrationProducesKeyRegisteredAuditEntry() {
        UUID workerId = UUID.randomUUID();
        RegisterKeyRequest request =
                new RegisterKeyRequest(workerId, "worker", "Ed25519", new byte[32]);

        keyRegistrationService.register(request, "10.0.0.1");

        // Verify via direct JDBC (DEC-26 Rule 2)
        Table table = assertDb.table("audit_entry").build();
        assertThat(table).hasNumberOfRows(1);
        assertThat(table).row(0).value("event_type").isEqualTo("KEY_REGISTERED");
        assertThat(table).row(0).value("source_ip").isEqualTo("10.0.0.1");
    }

    // -------------------------------------------------------------------------
    // AC-IDENTITY-INTEGRATION-WIRING (b): KEY_RE_REGISTRATION_IDEMPOTENT → AuditEntry row
    // -------------------------------------------------------------------------

    @Test
    void idempotentReRegistrationProducesAuditEntry() {
        UUID workerId = UUID.randomUUID();
        RegisterKeyRequest request =
                new RegisterKeyRequest(workerId, "worker", "Ed25519", new byte[32]);

        // First registration
        keyRegistrationService.register(request, "10.0.0.1");
        // Second (idempotent) registration
        keyRegistrationService.register(request, "10.0.0.2");

        Table table = assertDb.table("audit_entry").build();
        assertThat(table).hasNumberOfRows(2);
        // Second entry is the idempotent one
        assertThat(table).row(1).value("event_type").isEqualTo("KEY_RE_REGISTRATION_IDEMPOTENT");
    }

    // -------------------------------------------------------------------------
    // AC-IDENTITY-INTEGRATION-WIRING (c): KEY_ROLE_CONFLICT → AuditEntry row
    // -------------------------------------------------------------------------

    @Test
    void roleConflictRegistrationProducesAuditEntry() {
        UUID workerId = UUID.randomUUID();
        // First registration as worker
        keyRegistrationService.register(
                new RegisterKeyRequest(workerId, "worker", "Ed25519", new byte[32]), "10.0.0.1");

        // Attempt re-registration as submitter (different role → conflict)
        assertThatThrownBy(
                        () ->
                                keyRegistrationService.register(
                                        new RegisterKeyRequest(
                                                workerId, "submitter", "Ed25519", new byte[32]),
                                        "10.0.0.3"))
                .isInstanceOf(RoleConflictException.class);

        // Both the original registration AND the conflict attempt must produce audit entries
        Table table = assertDb.table("audit_entry").build();
        assertThat(table).hasNumberOfRows(2);
        assertThat(table).row(1).value("event_type").isEqualTo("KEY_ROLE_CONFLICT");
    }
}
