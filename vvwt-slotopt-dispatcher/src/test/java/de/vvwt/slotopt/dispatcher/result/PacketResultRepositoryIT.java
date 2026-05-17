// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.identity.testsupport.DispatcherDaoTestSupport;
import java.util.List;
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
 * Integration test for {@link PacketResultRepository}.
 *
 * <p>Applies DEC-26/DEC-46 three-rule DAO governance:
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> Flyway runs {@code
 *       db/migration/result/V8__create_packet_result_table.sql} automatically.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write assertions use assertj-db directly
 *       against the DataSource, NOT the repository's read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path fixtures inserted via {@link
 *       DispatcherDaoTestSupport#insertDirectly}.
 * </ol>
 *
 * <p>RED-first per DEC-22 / AC-TEST-RESULT-RETAINED-ON-ACCEPT, AC-TEST-RETENTION-DURABLE,
 * AC-TEST-RETAINED-RESULTS-QUERYABLE-BY-JOB, AC-GOV-DAO-IT-THREE-RULE.
 *
 * <p>Story: E60S02; DEC-22, DEC-26, DEC-46
 */
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:dispatcher-packet-result-it-${random.uuid};"
                    + "DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.locations=classpath:db/migration/result",
            "spring.flyway.enabled=true"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PacketResultRepositoryIT {

    @Autowired private PacketResultRepository repository;

    @Autowired private DataSource dataSource;

    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        assertDb = DispatcherDaoTestSupport.assertDbOf(dataSource);
    }

    // -------------------------------------------------------------------------
    // Write-path test (Rule 2: verify via assertj-db, NOT repository.find*)
    // AC-TEST-RESULT-RETAINED-ON-ACCEPT, AC-TEST-RETENTION-DURABLE
    // -------------------------------------------------------------------------

    @Test
    void savePersistsPacketResult() {
        // Rule 1: schema from V8 migration (via Flyway above)
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        PacketResult pr = buildPacketResult(packetId, jobId, 3, 42.5);
        repository.save(pr);

        // Rule 2: verify via assertj-db (independent verifier)
        Table table = assertDb.table("packet_result").build();
        assertThat(table).hasNumberOfRows(1);
        assertThat(table).row(0).value("packet_id").isEqualTo(packetId);
        assertThat(table).row(0).value("job_id").isEqualTo(jobId);
        assertThat(table).row(0).value("best_rank").isEqualTo(3);
        assertThat(table).row(0).value("best_score").isEqualTo(42.5);
    }

    // -------------------------------------------------------------------------
    // Read-path test (Rule 3: fixture via insertDirectly, NOT repository.save)
    // AC-TEST-RETAINED-RESULTS-QUERYABLE-BY-JOB
    // -------------------------------------------------------------------------

    @Test
    void findAllByJobId_returnsAllRetainedResultsForJob() {
        UUID jobId = UUID.randomUUID();
        UUID packetId1 = UUID.randomUUID();
        UUID packetId2 = UUID.randomUUID();
        UUID otherJobId = UUID.randomUUID();
        UUID otherPacketId = UUID.randomUUID();

        // Rule 3: insert via direct JDBC (not repository.save)
        java.util.Map<String, Object> row1 = new java.util.LinkedHashMap<>();
        row1.put("packet_id", packetId1);
        row1.put("job_id", jobId);
        row1.put("best_rank", 1);
        row1.put("best_score", 10.0);
        DispatcherDaoTestSupport.insertDirectly(dataSource, "packet_result", row1);

        java.util.Map<String, Object> row2 = new java.util.LinkedHashMap<>();
        row2.put("packet_id", packetId2);
        row2.put("job_id", jobId);
        row2.put("best_rank", 2);
        row2.put("best_score", 20.0);
        DispatcherDaoTestSupport.insertDirectly(dataSource, "packet_result", row2);

        // Packet for a different job — must NOT appear in results
        java.util.Map<String, Object> row3 = new java.util.LinkedHashMap<>();
        row3.put("packet_id", otherPacketId);
        row3.put("job_id", otherJobId);
        row3.put("best_rank", 5);
        row3.put("best_score", 99.0);
        DispatcherDaoTestSupport.insertDirectly(dataSource, "packet_result", row3);

        List<PacketResult> results = repository.findAllByJobId(jobId);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(PacketResult::getJobId).containsOnly(jobId);
    }

    @Test
    void findAllByJobId_noResultsForJob_returnsEmptyList() {
        List<PacketResult> results = repository.findAllByJobId(UUID.randomUUID());
        assertThat(results).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static PacketResult buildPacketResult(
            UUID packetId, UUID jobId, int bestRank, double bestScore) {
        PacketResult pr = new PacketResult();
        pr.setPacketId(packetId);
        pr.setJobId(jobId);
        pr.setBestRank(bestRank);
        pr.setBestScore(bestScore);
        return pr;
    }
}
