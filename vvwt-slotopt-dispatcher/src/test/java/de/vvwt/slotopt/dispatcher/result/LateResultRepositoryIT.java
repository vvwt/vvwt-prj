package de.vvwt.slotopt.dispatcher.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.identity.testsupport.DispatcherDaoTestSupport;
import java.time.Instant;
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
 * Integration test for {@link LateResultRepository}.
 *
 * <p>Applies DEC-26 three-rule DAO governance analogously to the dispatcher module:
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> Flyway runs {@code
 *       db/migration/result/V5__create_late_result_table.sql} automatically.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write assertions use assertj-db directly
 *       against the DataSource, NOT the repository's read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path fixtures inserted via {@link
 *       DispatcherDaoTestSupport#insertDirectly}.
 * </ol>
 *
 * <p>RED-first per DEC-22 / AC-DEC26-COMPLIANT-DAO-ITs (E37S09).
 *
 * <p>Story: E37S09; AC-LATE-RESULT-REPOSITORY; AC-DEC26-COMPLIANT-DAO-ITs; DEC-26
 */
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:dispatcher-late-result-it-${random.uuid};"
                    + "DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.locations=classpath:db/migration/result",
            "spring.flyway.enabled=true"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LateResultRepositoryIT {

    @Autowired private LateResultRepository repository;

    @Autowired private DataSource dataSource;

    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        assertDb = DispatcherDaoTestSupport.assertDbOf(dataSource);
    }

    // -------------------------------------------------------------------------
    // Write-path tests (Rule 2: verify via assertj-db, NOT repository.find*)
    // -------------------------------------------------------------------------

    @Test
    void savePersistsNewLateResult() {
        LateResult lr = buildLateResult(UUID.randomUUID(), UUID.randomUUID());
        repository.save(lr);

        Table table = assertDb.table("late_result").build();
        assertThat(table).hasNumberOfRows(1);
        assertThat(table).row(0).value("algorithm").isEqualTo("Ed25519");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static LateResult buildLateResult(UUID packetId, UUID workerId) {
        LateResult lr = new LateResult();
        lr.setPacketId(packetId);
        lr.setWorkerId(workerId);
        lr.setAlgorithm("Ed25519");
        lr.setSignature(new byte[] {1, 2, 3});
        lr.setResultPayloadJson("{\"bestRank\":0}");
        lr.setReceivedAt(Instant.now());
        return lr;
    }
}
