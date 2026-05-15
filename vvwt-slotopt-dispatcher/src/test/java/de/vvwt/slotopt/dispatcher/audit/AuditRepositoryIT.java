package de.vvwt.slotopt.dispatcher.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.identity.testsupport.DispatcherDaoTestSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link AuditRepository}.
 *
 * <p>Applies DEC-26 three-rule DAO governance analogously to the dispatcher module:
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> Flyway runs the production migration
 *       automatically via {@code spring.flyway.locations} test property. Schema source = {@code
 *       db/migration/audit/V2__create_audit_entry_table.sql} — NOT inline test DDL.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write assertions use assertj-db {@link
 *       Table} directly against the test DataSource, NOT the repository's read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path fixtures inserted via {@link
 *       DispatcherDaoTestSupport#insertDirectly}, NOT repository.save.
 * </ol>
 *
 * <p>RED-first per DEC-22 / AC-DEC26-COMPLIANT-DAO-IT.
 *
 * <p>Story: E37S06
 */
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:audit-it-${random.uuid};"
                    + "DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.locations=classpath:db/migration/identity,classpath:db/migration/audit",
            "spring.flyway.enabled=true"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditRepositoryIT {

    @Autowired private AuditRepository repository;

    @Autowired private DataSource dataSource;

    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        assertDb = DispatcherDaoTestSupport.assertDbOf(dataSource);
    }

    // -------------------------------------------------------------------------
    // Write-path tests (Rule 2: verify via assertj-db, NOT repository.findBy*)
    // -------------------------------------------------------------------------

    @Test
    void savePersistsNewAuditEntry() {
        AuditEntry entry = buildEntry(UUID.randomUUID(), "KEY_REGISTERED", "127.0.0.1", "{}");
        repository.save(entry);

        // Rule 2: independent verification via assertj-db Table assertion
        Table table = assertDb.table("audit_entry").build();
        assertThat(table).hasNumberOfRows(1);
        assertThat(table).row(0).value("event_type").isEqualTo("KEY_REGISTERED");
        assertThat(table).row(0).value("source_ip").isEqualTo("127.0.0.1");
    }

    @Test
    void saveTwoDistinctAuditEntries() {
        AuditEntry e1 = buildEntry(UUID.randomUUID(), "KEY_REGISTERED", "10.0.0.1", "{}");
        AuditEntry e2 = buildEntry(UUID.randomUUID(), "KEY_ROLE_CONFLICT", "10.0.0.2", "{}");
        repository.save(e1);
        repository.save(e2);

        Table table = assertDb.table("audit_entry").build();
        assertThat(table).hasNumberOfRows(2);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AuditEntry buildEntry(
            UUID workerId, String eventType, String sourceIp, String detailJson) {
        AuditEntry entry = new AuditEntry();
        entry.setOccurredAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        entry.setEventType(eventType);
        entry.setWorkerId(workerId);
        entry.setSourceIp(sourceIp);
        entry.setDetailJson(detailJson);
        return entry;
    }
}
