package de.vvwt.slotopt.dispatcher.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.identity.testsupport.DispatcherDaoTestSupport;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.Table;
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
 * Integration test for {@link CachedResultRepository}.
 *
 * <p>Applies DEC-26 three-rule DAO governance analogously to the dispatcher module:
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> Flyway runs {@code
 *       db/migration/cache/V7__create_cached_result_table.sql} automatically.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write assertions use assertj-db directly
 *       against the DataSource, NOT the repository's read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path fixtures inserted via {@link
 *       DispatcherDaoTestSupport#insertDirectly}.
 * </ol>
 *
 * <p>RED-first per DEC-22 / AC-CACHED-RESULT-REPOSITORY (E37S10).
 *
 * <p>Story: E37S10; AC-CACHED-RESULT-REPOSITORY; AC-DEC26-COMPLIANT-DAO-IT; DEC-26
 */
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:dispatcher-cache-it-${random.uuid};"
                    + "DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.locations=classpath:db/migration/cache",
            "spring.flyway.enabled=true"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CachedResultRepositoryIT {

    @Autowired private CachedResultRepository repository;
    @Autowired private DataSource dataSource;

    /** Rule 1 compliance: schema from V7 migration; Rule 2: assertj-db verifies write. */
    @Test
    void savePersistsEntity() {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0xAA);

        CachedResultEntity entity = new CachedResultEntity();
        entity.setId(new CachedResultId(fingerprint, "test-mode"));
        entity.setResultPayloadJson("{\"bestRank\":42}");
        entity.setCachedAt(Instant.parse("2026-04-26T10:00:00Z"));
        entity.setFirstAcceptedJobId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        repository.save(entity);

        // Rule 2: verify via assertj-db (independent of repository read methods)
        AssertDbConnection assertDb = DispatcherDaoTestSupport.assertDbOf(dataSource);
        Table table = assertDb.table("cached_result").build();
        assertThat(table).hasNumberOfRows(1);
    }

    /**
     * Rule 3 — Read/write decoupling: fixture inserted via direct JDBC, not via repository.save.
     */
    @Test
    void findByCompositeKeyReturnsStoredEntity() {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0xBB);
        Instant now = Instant.parse("2026-04-26T12:00:00Z");
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Rule 3: read-path fixture inserted via direct JDBC (not via repository.save)
        DispatcherDaoTestSupport.insertDirectly(
                dataSource,
                "cached_result",
                Map.of(
                        "structural_fingerprint", fingerprint,
                        "game_mode", "find-mode",
                        "result_payload_json", "{\"bestRank\":99}",
                        "cached_at", now,
                        "first_accepted_job_id", jobId));

        Optional<CachedResultEntity> found = repository.findByKey(fingerprint, "find-mode");

        assertThat(found).isPresent();
        assertThat(found.get().getGameMode()).isEqualTo("find-mode");
        assertThat(found.get().getResultPayloadJson()).isEqualTo("{\"bestRank\":99}");
    }

    @Test
    void findByCompositeKeyReturnsMissForUnknownKey() {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0xFF);

        Optional<CachedResultEntity> found = repository.findByKey(fingerprint, "no-such-mode");

        assertThat(found).isEmpty();
    }
}
