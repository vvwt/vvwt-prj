package de.vvwt.slotopt.dispatcher.job;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Integration test for {@link JobRepository}.
 *
 * <p>Applies DEC-26 three-rule DAO governance analogously to the dispatcher module:
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> Flyway runs {@code
 *       db/migration/job/V3__create_job_table.sql} automatically. Schema source = production file —
 *       NOT inline test DDL.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write assertions use assertj-db {@link
 *       Table} directly against the test DataSource, NOT the repository's read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path fixtures inserted via {@link
 *       DispatcherDaoTestSupport#insertDirectly}, NOT repository.save.
 * </ol>
 *
 * <p>RED-first per DEC-22 / AC-DEC26-COMPLIANT-DAO-IT (E37S07).
 *
 * <p>Story: E37S07; AC-DEC26-COMPLIANT-DAO-IT; DEC-26
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:dispatcher-job-it-${random.uuid};"
                    + "DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.locations=classpath:db/migration/job",
            "spring.flyway.enabled=true"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobRepositoryIT {

    @Autowired private JobRepository repository;

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
    void savePersistsNewJob() {
        JobRecord record = buildJobRecord(UUID.randomUUID(), "RECEIVED", "{\"n\":3}");
        repository.save(record);

        // Rule 2: independent verification via assertj-db Table assertion
        Table table = assertDb.table("job").build();
        assertThat(table).hasNumberOfRows(1);
        assertThat(table).row(0).value("status").isEqualTo("RECEIVED");
    }

    @Test
    void uniqueJobIdConstraintEnforced() {
        UUID jobId = UUID.randomUUID();
        JobRecord r1 = buildJobRecord(jobId, "RECEIVED", "{\"n\":3}");
        JobRecord r2 = buildJobRecord(jobId, "DECOMPOSED", "{\"n\":3}");
        repository.save(r1);

        // Rule 2: verify constraint violation at DB level
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.save(r2))
                .isInstanceOf(Exception.class);
    }

    // -------------------------------------------------------------------------
    // Read-path tests (Rule 3: fixtures via insertDirectly, NOT repository.save)
    // -------------------------------------------------------------------------

    @Test
    void findByJobIdReturnsRecordWhenPresent() {
        UUID jobId = UUID.randomUUID();
        Instant submittedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Rule 3: fixture via direct JDBC — not via repository.save
        DispatcherDaoTestSupport.insertDirectly(
                dataSource,
                "job",
                Map.of(
                        "job_id",
                        jobId.toString(),
                        "submitted_at",
                        submittedAt,
                        "job_def_json",
                        "{\"n\":3}",
                        "status",
                        "RECEIVED"));

        Optional<JobRecord> found = repository.findByJobId(jobId);

        assertThat(found).isPresent();
        assertThat(found.get().getJobId()).isEqualTo(jobId);
        assertThat(found.get().getStatus()).isEqualTo("RECEIVED");
    }

    @Test
    void findByJobIdReturnsEmptyWhenAbsent() {
        Optional<JobRecord> found = repository.findByJobId(UUID.randomUUID());
        assertThat(found).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static JobRecord buildJobRecord(UUID jobId, String status, String jobDefJson) {
        JobRecord record = new JobRecord();
        record.setJobId(jobId);
        record.setSubmittedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        record.setJobDefJson(jobDefJson);
        record.setStatus(status);
        return record;
    }
}
