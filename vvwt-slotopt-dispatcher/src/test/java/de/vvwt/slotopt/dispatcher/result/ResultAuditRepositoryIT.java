package de.vvwt.slotopt.dispatcher.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.identity.testsupport.DispatcherDaoTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Integration test for {@link ResultAuditRepository}.
 *
 * <p>Applies DEC-26 three-rule DAO governance analogously to the dispatcher module:
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> Flyway runs {@code
 *       db/migration/result/V6__create_result_audit_entry_table.sql} automatically.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write assertions use assertj-db.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path fixtures via {@link
 *       DispatcherDaoTestSupport#insertDirectly}.
 * </ol>
 *
 * <p>RED-first per DEC-22 / AC-DEC26-COMPLIANT-DAO-ITs (E37S09).
 *
 * <p>Story: E37S09; AC-RESULT-AUDIT; AC-DEC26-COMPLIANT-DAO-ITs; DEC-26
 */
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:dispatcher-result-audit-it-${random.uuid};"
                    + "DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.locations=classpath:db/migration/result",
            "spring.flyway.enabled=true"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResultAuditRepositoryIT {

    @Autowired private ResultAuditRepository repository;

    @Autowired private DataSource dataSource;

    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        assertDb = DispatcherDaoTestSupport.assertDbOf(dataSource);
    }

    // -------------------------------------------------------------------------
    // Write-path tests (Rule 2: verify via assertj-db)
    // -------------------------------------------------------------------------

    @Test
    void savePersistsAuditEntry() {
        ResultAuditEntry entry = buildAuditEntry(UUID.randomUUID(), UUID.randomUUID(), "ACCEPTED");
        repository.save(entry);

        Table table = assertDb.table("result_audit_entry").build();
        assertThat(table).hasNumberOfRows(1);
        assertThat(table).row(0).value("outcome").isEqualTo("ACCEPTED");
    }

    // -------------------------------------------------------------------------
    // Read-path tests (Rule 3: fixtures via insertDirectly)
    // -------------------------------------------------------------------------

    @Test
    void findByPacketIdReturnsEntryWhenPresent() {
        UUID packetId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();

        DispatcherDaoTestSupport.insertDirectly(
                dataSource,
                "result_audit_entry",
                Map.of(
                        "packet_id",
                        packetId.toString(),
                        "worker_id",
                        workerId.toString(),
                        "algorithm",
                        "Ed25519",
                        "source_ip",
                        "10.0.0.1",
                        "received_at",
                        Instant.now().toString(),
                        "outcome",
                        "SUPERSEDED"));

        List<ResultAuditEntry> found = repository.findByPacketId(packetId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getOutcome()).isEqualTo("SUPERSEDED");
    }

    @Test
    void findByPacketIdReturnsEmptyWhenAbsent() {
        List<ResultAuditEntry> found = repository.findByPacketId(UUID.randomUUID());
        assertThat(found).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ResultAuditEntry buildAuditEntry(UUID packetId, UUID workerId, String outcome) {
        ResultAuditEntry entry = new ResultAuditEntry();
        entry.setPacketId(packetId);
        entry.setWorkerId(workerId);
        entry.setAlgorithm("Ed25519");
        entry.setSourceIp("127.0.0.1");
        entry.setReceivedAt(Instant.now());
        entry.setOutcome(outcome);
        return entry;
    }
}
