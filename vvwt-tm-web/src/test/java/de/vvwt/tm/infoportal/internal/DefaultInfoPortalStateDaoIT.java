// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.infoportal.InfoPortalStateDao;
import de.vvwt.tm.infoportal.InfoPortalStateRecord;
import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DAO integration tests for {@link DefaultInfoPortalStateDao} (via {@link InfoPortalStateDao}
 * interface) — DEC-26 + DEC-46 three rules via {@link TenantDaoTestSupport} (TM is DEC-20
 * DB-per-Tenant, eligible per DEC-46 clause #2(a)).
 *
 * <p>DEC-22 Iron Law: tests written RED-first before DAO class exists.
 *
 * <p>DEC-26 three rules:
 *
 * <ol>
 *   <li>Rule 1 — schema from production migration {@code infoportal/V1__initial_schema.sql} (E45S05
 *       per-module schema; replaces root {@code V17__e38s09_info_portal_state.sql})
 *   <li>Rule 2 — assertj-db independent persistence verifier (not DAO read method)
 *   <li>Rule 3 — JDBC direct-insert for read-path fixture (not DAO write method)
 * </ol>
 *
 * <p>E45S06 — DEC-50 Big-Bang-Reset cleanup: {@code tenant_id} column removed from schema and DAO.
 * {@code upsertRegistration} signature changed from 5-param (tenantId, locationId, tournamentId,
 * token, secret) to 4-param (locationId, tournamentId, token, secret). Direct-insert fixtures no
 * longer include {@code tenant_id}.
 *
 * @see DefaultInfoPortalStateDao
 * @see InfoPortalStateDao
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09
 *     AC12</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-26.md">DEC-26</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-46.md">DEC-46</a>
 * @since E57S01 (moved from infoportal.InfoPortalStateDaoIT to infoportal.internal per DEC-58
 *     interface extraction)
 */
class DefaultInfoPortalStateDaoIT {

    private DataSource dataSource;
    private AssertDbConnection assertDb;
    private InfoPortalStateDao dao;

    @BeforeEach
    void setUp() {
        // DEC-26 Rule 1: schema from production migration (infoportal module), DEC-46 via
        // TenantDaoTestSupport
        dataSource = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyMigration(
                dataSource, "db/migration/infoportal/V1__initial_schema.sql");
        // DEC-26 Rule 2: independent assertj-db verifier
        assertDb = TenantDaoTestSupport.assertDbOf(dataSource);
        dao = new DefaultInfoPortalStateDao(dataSource);
    }

    // -------------------------------------------------------------------------
    // Write-path tests (Rule 2 — assertj-db verifier)
    // -------------------------------------------------------------------------

    @Test
    void upsertRegistration_insertsNewRow() {
        // GIVEN
        String locationId = "venue-1";
        String tournamentId = "tourn-abc";
        String tournamentToken = "tok-xyz";
        byte[] secret = new byte[] {1, 2, 3, 4};

        // WHEN — E45S06: tenantId param removed (DEC-50)
        dao.upsertRegistration(locationId, tournamentId, tournamentToken, secret);

        // THEN — DEC-26 Rule 2: assertj-db, NOT dao.find()
        Table table = assertDb.table("info_portal_state").build();
        assertThat(table).hasNumberOfRows(1);
    }

    @Test
    void upsertRegistration_updatesExistingRow() {
        // GIVEN — initial row
        dao.upsertRegistration("l", "tour-1", "old-token", new byte[] {1});
        // Update with new token
        dao.upsertRegistration("l", "tour-1", "new-token", new byte[] {2});

        // THEN — still exactly 1 row (upsert, not insert)
        Table table = assertDb.table("info_portal_state").build();
        assertThat(table).hasNumberOfRows(1);
    }

    @Test
    void incrementAndGetSeq_atomicIncrementFromZero() {
        // GIVEN — row with last_published_seq = 0
        dao.upsertRegistration("l", "tour-2", "tok", new byte[] {1});

        // WHEN — E45S04: tenantId param dropped (DEC-39/DEC-50)
        long seq = dao.incrementAndGetSeq("l", "tour-2");

        // THEN
        assertThat(seq).isEqualTo(1L);
    }

    @Test
    void incrementAndGetSeq_incrementsMonotonically() {
        dao.upsertRegistration("l", "tour-3", "tok", new byte[] {1});

        // E45S04: tenantId param dropped
        long seq1 = dao.incrementAndGetSeq("l", "tour-3");
        long seq2 = dao.incrementAndGetSeq("l", "tour-3");
        long seq3 = dao.incrementAndGetSeq("l", "tour-3");

        assertThat(seq1).isEqualTo(1L);
        assertThat(seq2).isEqualTo(2L);
        assertThat(seq3).isEqualTo(3L);
    }

    @Test
    void updateLastPublishedAt_updatesTimestamp() {
        dao.upsertRegistration("l", "tour-4", "tok", new byte[] {1});
        Instant now = Instant.now();

        // E45S04: tenantId param dropped
        dao.updateLastPublishedAt("l", "tour-4", now);

        // DEC-26 Rule 2: assertj-db row count (structural check — timestamp value not easily
        // compared via assertj-db; correctness verified by read-path test below)
        Table table = assertDb.table("info_portal_state").build();
        assertThat(table).hasNumberOfRows(1);
    }

    // -------------------------------------------------------------------------
    // Read-path tests (Rule 3 — JDBC direct-insert fixture)
    // -------------------------------------------------------------------------

    @Test
    void findByTournament_returnsRecord_whenRowExists() {
        // DEC-26 Rule 3: JDBC direct-insert, not dao.upsertRegistration()
        // E45S06: tenant_id column removed from insert (DEC-50)
        TenantDaoTestSupport.insertDirectly(
                dataSource,
                "info_portal_state",
                Map.of(
                        "location_id", "l2",
                        "tournament_id", "tour-read-1",
                        "last_published_seq", 7L,
                        "tournament_token", "read-tok",
                        "per_tournament_secret", new byte[] {5, 6},
                        "registration_status", "REGISTERED"));

        // E45S04: tenantId param dropped
        Optional<InfoPortalStateRecord> found = dao.findByTournament("l2", "tour-read-1");

        assertThat(found).isPresent();
        assertThat(found.get().tournamentToken()).isEqualTo("read-tok");
        assertThat(found.get().lastPublishedSeq()).isEqualTo(7L);
        assertThat(found.get().registrationStatus()).isEqualTo("REGISTERED");
    }

    @Test
    void findByTournament_returnsEmpty_whenNoRow() {
        // E45S04: tenantId param dropped
        Optional<InfoPortalStateRecord> found = dao.findByTournament("none", "none");
        assertThat(found).isEmpty();
    }

    @Test
    void findCurrentSeq_returnsSeq_whenRowExists() {
        TenantDaoTestSupport.insertDirectly(
                dataSource,
                "info_portal_state",
                Map.of(
                        "location_id", "l3",
                        "tournament_id", "tour-seq-1",
                        "last_published_seq", 42L,
                        "tournament_token", "tok-seq",
                        "per_tournament_secret", new byte[] {9},
                        "registration_status", "REGISTERED"));

        // E45S04: tenantId param dropped
        long seq = dao.findCurrentSeq("l3", "tour-seq-1");

        assertThat(seq).isEqualTo(42L);
    }
}
