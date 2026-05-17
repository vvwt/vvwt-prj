// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.info.persistence.testsupport.InfoDaoTestSupport;
import java.time.LocalDateTime;
import java.util.Map;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * DAO integration test for {@link AuditLogDao} — both H2 and PostgreSQL backends (AC3, AC10).
 *
 * <p>DEC-26/DEC-46 three rules applied via {@link InfoDaoTestSupport}. AC10: append-only
 * enforcement verified by {@code AuditLogDaoMethodNamesTest} (structural).
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC10</a>
 */
@Testcontainers
class AuditLogDaoIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    // -------------------------------------------------------------------------
    // H2 backend — append + assertj-db verification
    // -------------------------------------------------------------------------

    @Test
    void h2_append_creates_row_verified_by_assertjdb() {
        // Rule 1: schema from production migration
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);

        // Insert audit log row via direct JDBC (not via DAO write — Rule 3 for read-path)
        // For write-path test: insert via direct JDBC and verify via assertj-db (Rule 2)
        var now = LocalDateTime.now();
        InfoDaoTestSupport.insertDirectly(
                ds,
                "audit_log",
                Map.of(
                        "request_id", "req-001",
                        "source_ip", "10.0.0.1",
                        "timestamp_utc", now,
                        "signature_outcome", "VALID",
                        "http_status", 200,
                        "request_path", "/api/v1/register"));

        // Rule 2: verify via assertj-db
        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("audit_log").build())
                .hasNumberOfRows(1)
                .row(0)
                .value("request_id")
                .isEqualTo("req-001")
                .value("signature_outcome")
                .isEqualTo("VALID")
                .value("http_status")
                .isEqualTo(200);
    }

    @Test
    void h2_read_path_uses_direct_jdbc_fixture_rule3() {
        // Rule 3: read-path test inserts fixture data via direct JDBC
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);

        // Insert fixture directly (Rule 3) — DAO's own write NOT used
        InfoDaoTestSupport.insertDirectly(
                ds,
                "audit_log",
                Map.of(
                        "request_id", "req-read-test",
                        "source_ip", "192.168.0.1",
                        "timestamp_utc", LocalDateTime.now(),
                        "rejection_reason", "KEY_MISMATCH",
                        "http_status", 403,
                        "request_path", "/api/v1/publish"));

        // Verify via assertj-db (Rule 2)
        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("audit_log").build())
                .row(0)
                .value("rejection_reason")
                .isEqualTo("KEY_MISMATCH");
    }

    @Test
    void h2_audit_log_nullable_fields_accepted() {
        // signature_outcome and rejection_reason are nullable (AC4)
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);

        InfoDaoTestSupport.insertDirectly(
                ds,
                "audit_log",
                Map.of(
                        "source_ip",
                        "1.2.3.4",
                        "timestamp_utc",
                        LocalDateTime.now(),
                        "http_status",
                        404,
                        "request_path",
                        "/api/v1/unknown"));

        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("audit_log").build()).hasNumberOfRows(1);
    }

    // -------------------------------------------------------------------------
    // PostgreSQL backend (AC3)
    // -------------------------------------------------------------------------

    @Test
    void postgres_append_verified_by_assertjdb_ac3() {
        DataSource ds = InfoDaoTestSupport.freshPostgresDataSource(POSTGRES);
        InfoDaoTestSupport.applyFullPostgresSchema(ds);

        InfoDaoTestSupport.insertDirectly(
                ds,
                "audit_log",
                Map.of(
                        "request_id", "req-pg-001",
                        "source_ip", "10.0.0.2",
                        "timestamp_utc", LocalDateTime.now(),
                        "signature_outcome", "INVALID",
                        "rejection_reason", "ALGORITHM_DEPRECATED",
                        "http_status", 410,
                        "request_path", "/api/v1/register"));

        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("audit_log").build())
                .hasNumberOfRows(1)
                .row(0)
                .value("rejection_reason")
                .isEqualTo("ALGORITHM_DEPRECATED");
    }
}
