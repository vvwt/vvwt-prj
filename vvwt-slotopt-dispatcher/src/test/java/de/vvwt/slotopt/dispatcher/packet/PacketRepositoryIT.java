package de.vvwt.slotopt.dispatcher.packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.identity.testsupport.DispatcherDaoTestSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Integration test for {@link PacketRepository}.
 *
 * <p>Applies DEC-26 three-rule DAO governance analogously to the dispatcher module:
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> Flyway runs {@code
 *       db/migration/packet/V4__create_packet_table.sql} automatically. Schema source = production
 *       file — NOT inline test DDL.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write assertions use assertj-db {@link
 *       Table} directly against the test DataSource, NOT the repository's read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path fixtures inserted via {@link
 *       DispatcherDaoTestSupport#insertDirectly}, NOT repository.save.
 * </ol>
 *
 * <p>RED-first per DEC-22 / AC-DEC26-COMPLIANT-DAO-ITs (E37S08).
 *
 * <p>Story: E37S08; AC-DEC26-COMPLIANT-DAO-ITs; DEC-26
 */
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:dispatcher-packet-it-${random.uuid};"
                    + "DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.locations=classpath:db/migration/packet",
            "spring.flyway.enabled=true"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PacketRepositoryIT {

    @Autowired private PacketRepository repository;

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
    void savePersistsNewPacket() {
        PacketRecord record = buildPacketRecord(UUID.randomUUID(), UUID.randomUUID(), "UNCLAIMED");
        repository.save(record);

        // Rule 2: independent verification via assertj-db Table assertion
        Table table = assertDb.table("packet").build();
        assertThat(table).hasNumberOfRows(1);
        assertThat(table).row(0).value("status").isEqualTo("UNCLAIMED");
    }

    @Test
    void uniquePacketIdConstraintEnforced() {
        UUID packetId = UUID.randomUUID();
        PacketRecord r1 = buildPacketRecord(packetId, UUID.randomUUID(), "UNCLAIMED");
        PacketRecord r2 = buildPacketRecord(packetId, UUID.randomUUID(), "CLAIMED");
        repository.save(r1);

        // Rule 2: verify constraint violation at DB level
        assertThatThrownBy(() -> repository.save(r2)).isInstanceOf(Exception.class);
    }

    // -------------------------------------------------------------------------
    // Read-path tests (Rule 3: fixtures via insertDirectly, NOT repository.save)
    // -------------------------------------------------------------------------

    @Test
    void findByPacketIdReturnsRecordWhenPresent() {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        // Rule 3: fixture via direct JDBC — not via repository.save
        DispatcherDaoTestSupport.insertDirectly(
                dataSource,
                "packet",
                Map.of(
                        "packet_id",
                        packetId.toString(),
                        "job_id",
                        jobId.toString(),
                        "packet_payload_json",
                        "{\"rankFrom\":0,\"rankTo\":100}",
                        "status",
                        "UNCLAIMED"));

        Optional<PacketRecord> found = repository.findByPacketId(packetId);

        assertThat(found).isPresent();
        assertThat(found.get().getPacketId()).isEqualTo(packetId);
        assertThat(found.get().getJobId()).isEqualTo(jobId);
        assertThat(found.get().getStatus()).isEqualTo("UNCLAIMED");
    }

    @Test
    void findByPacketIdReturnsEmptyWhenAbsent() {
        Optional<PacketRecord> found = repository.findByPacketId(UUID.randomUUID());
        assertThat(found).isEmpty();
    }

    @Test
    void findByJobIdReturnsAllPacketsForJob() {
        UUID jobId = UUID.randomUUID();

        // Rule 3: insert 2 packets for same job
        DispatcherDaoTestSupport.insertDirectly(
                dataSource,
                "packet",
                Map.of(
                        "packet_id",
                        UUID.randomUUID().toString(),
                        "job_id",
                        jobId.toString(),
                        "packet_payload_json",
                        "{\"rankFrom\":0,\"rankTo\":50}",
                        "status",
                        "UNCLAIMED"));
        DispatcherDaoTestSupport.insertDirectly(
                dataSource,
                "packet",
                Map.of(
                        "packet_id",
                        UUID.randomUUID().toString(),
                        "job_id",
                        jobId.toString(),
                        "packet_payload_json",
                        "{\"rankFrom\":50,\"rankTo\":100}",
                        "status",
                        "CLAIMED"));

        List<PacketRecord> found = repository.findByJobId(jobId);

        assertThat(found).hasSize(2);
        assertThat(found).allMatch(p -> p.getJobId().equals(jobId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static PacketRecord buildPacketRecord(UUID packetId, UUID jobId, String status) {
        PacketRecord record = new PacketRecord();
        record.setPacketId(packetId);
        record.setJobId(jobId);
        record.setPacketPayloadJson("{\"rankFrom\":0,\"rankTo\":100000000}");
        record.setStatus(status);
        return record;
    }
}
