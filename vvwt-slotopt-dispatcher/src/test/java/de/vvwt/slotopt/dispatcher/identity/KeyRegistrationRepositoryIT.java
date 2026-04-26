package de.vvwt.slotopt.dispatcher.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.identity.testsupport.DispatcherDaoTestSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link KeyRegistrationRepository}.
 *
 * <p>Applies DEC-26 three-rule DAO governance analogously to the dispatcher module:
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> Flyway runs the production migration
 *       automatically via {@code spring.flyway.locations} test property. Schema source = {@code
 *       db/migration/identity/V1__key_registration.sql} — NOT inline test DDL.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write assertions use assertj-db {@link
 *       Table} directly against the test DataSource, NOT the repository's read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path fixtures inserted via {@link
 *       DispatcherDaoTestSupport#insertDirectly}, NOT repository.save.
 * </ol>
 *
 * <p>{@code @DirtiesContext(BEFORE_EACH_TEST_METHOD)}: rebuilds the application context (and thus
 * the H2 in-memory DB) before each test, giving full isolation.
 *
 * <p>RED-first per DEC-22 / AC-DEC26-COMPLIANT-DAO-IT.
 *
 * <p>Story: E37S05
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:dispatcher-it-${random.uuid};"
                    + "DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.locations=classpath:db/migration/identity",
            "spring.flyway.enabled=true"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class KeyRegistrationRepositoryIT {

    @Autowired private KeyRegistrationRepository repository;

    @Autowired private DataSource dataSource;

    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        // Rule 2: assertj-db connection for independent persistence verification
        assertDb = DispatcherDaoTestSupport.assertDbOf(dataSource);
    }

    // -------------------------------------------------------------------------
    // Write-path tests (Rule 2: verify via assertj-db, NOT repository.findBy*)
    // -------------------------------------------------------------------------

    @Test
    void savePersistsNewRegistration() {
        KeyRegistration entity = buildEntity(UUID.randomUUID(), "worker", "Ed25519", new byte[32]);
        repository.save(entity);

        // Rule 2: independent verification via assertj-db Table assertion
        Table table = assertDb.table("key_registration").build();
        assertThat(table).hasNumberOfRows(1);
        assertThat(table).row(0).value("role").isEqualTo("worker");
        assertThat(table).row(0).value("algorithm").isEqualTo("Ed25519");
    }

    @Test
    void saveTwoDistinctWorkerIds() {
        KeyRegistration e1 = buildEntity(UUID.randomUUID(), "worker", "Ed25519", new byte[32]);
        KeyRegistration e2 = buildEntity(UUID.randomUUID(), "submitter", "Ed25519", new byte[32]);
        repository.save(e1);
        repository.save(e2);

        Table table = assertDb.table("key_registration").build();
        assertThat(table).hasNumberOfRows(2);
    }

    @Test
    void uniqueWorkerIdConstraintEnforced() {
        UUID workerId = UUID.randomUUID();
        KeyRegistration e1 = buildEntity(workerId, "worker", "Ed25519", new byte[32]);
        KeyRegistration e2 = buildEntity(workerId, "submitter", "Ed25519", new byte[32]);
        repository.save(e1);

        // Rule 2: verify constraint violation at DB level
        assertThatThrownBy(() -> repository.save(e2)).isInstanceOf(Exception.class);
    }

    // -------------------------------------------------------------------------
    // Read-path tests (Rule 3: fixtures via insertDirectly, NOT repository.save)
    // -------------------------------------------------------------------------

    @Test
    void findByWorkerIdReturnsRegistrationWhenPresent() {
        UUID workerId = UUID.randomUUID();
        Instant registeredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Rule 3: fixture via direct JDBC — not via repository.save
        DispatcherDaoTestSupport.insertDirectly(
                dataSource,
                "key_registration",
                Map.of(
                        "worker_id", workerId.toString(),
                        "algorithm", "Ed25519",
                        "public_key_bytes", new byte[32],
                        "role", "worker",
                        "registered_at", registeredAt));

        Optional<KeyRegistration> found = repository.findByWorkerId(workerId);

        assertThat(found).isPresent();
        assertThat(found.get().getWorkerId()).isEqualTo(workerId);
        assertThat(found.get().getRole()).isEqualTo("worker");
    }

    @Test
    void findByWorkerIdReturnsEmptyWhenAbsent() {
        Optional<KeyRegistration> found = repository.findByWorkerId(UUID.randomUUID());
        assertThat(found).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static KeyRegistration buildEntity(
            UUID workerId, String role, String algorithm, byte[] keyBytes) {
        KeyRegistration entity = new KeyRegistration();
        entity.setWorkerId(workerId);
        entity.setRole(role);
        entity.setAlgorithm(algorithm);
        entity.setPublicKeyBytes(keyBytes);
        entity.setRegisteredAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        return entity;
    }
}
