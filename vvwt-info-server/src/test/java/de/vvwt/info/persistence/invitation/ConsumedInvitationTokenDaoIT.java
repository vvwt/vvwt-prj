// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.invitation;

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
 * DAO integration test for {@link ConsumedInvitationTokenDao} — both H2 and PostgreSQL backends.
 *
 * <p>DEC-26/DEC-46 three-rule compliance via {@link InfoDaoTestSupport}:
 *
 * <ol>
 *   <li>Rule 1 — Schema from production migration: {@link InfoDaoTestSupport#applyFullH2Schema} /
 *       {@link InfoDaoTestSupport#applyFullPostgresSchema}
 *   <li>Rule 2 — Independent assertj-db verifier: {@link InfoDaoTestSupport#assertDbOf}
 *   <li>Rule 3 — Read-path fixtures via {@link InfoDaoTestSupport#insertDirectly}, not DAO writes
 * </ol>
 *
 * <p>The DAO's Spring Data JDBC wiring is exercised at the application-context level by {@code
 * RegistrationControllerIT} (full {@code @SpringBootTest}). This test focuses on the
 * consumed_invitation_tokens schema contract (V3 migration).
 *
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-26.md">DEC-26</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-46.md">DEC-46</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC10</a>
 */
@Testcontainers
class ConsumedInvitationTokenDaoIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    // -------------------------------------------------------------------------
    // H2 backend — schema shape and constraints (Rule 1, Rule 2)
    // -------------------------------------------------------------------------

    @Test
    void h2_v3_migration_creates_consumed_invitation_tokens_table() {
        // Rule 1: schema from production V3 migration (consumed_invitation_tokens)
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);

        // Rule 3: insert directly with null tenant-id (consumed_by_tenant_id is nullable per AC10)
        InfoDaoTestSupport.insertDirectly(
                ds,
                "consumed_invitation_tokens",
                Map.of(
                        "token_value",
                        "TOKEN_H2_001",
                        "consumed_at",
                        LocalDateTime.of(2026, 4, 27, 12, 0, 0)));

        // Rule 2: assertj-db verifies row — not the DAO
        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("consumed_invitation_tokens").build())
                .hasNumberOfRows(1)
                .row(0)
                .value("token_value")
                .isEqualTo("TOKEN_H2_001")
                .value("consumed_by_tenant_id")
                .isNull();
    }

    @Test
    void h2_consumed_by_tenant_id_nullable() {
        // AC10: tenant_id is nullable — orphaned token consumption is allowed
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);

        InfoDaoTestSupport.insertDirectly(
                ds,
                "consumed_invitation_tokens",
                Map.of(
                        "token_value",
                        "ORPHAN_TOKEN",
                        "consumed_at",
                        LocalDateTime.of(2026, 4, 27, 10, 0, 0)));

        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("consumed_invitation_tokens").build())
                .hasNumberOfRows(1)
                .row(0)
                .value("token_value")
                .isEqualTo("ORPHAN_TOKEN")
                .value("consumed_by_tenant_id")
                .isNull();
    }

    @Test
    void h2_token_value_is_primary_key_unique() {
        // Primary key enforcement: inserting duplicate token_value must fail
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);

        InfoDaoTestSupport.insertDirectly(
                ds,
                "consumed_invitation_tokens",
                Map.of(
                        "token_value",
                        "UNIQUE_TOKEN",
                        "consumed_at",
                        LocalDateTime.of(2026, 4, 27, 9, 0, 0)));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                InfoDaoTestSupport.insertDirectly(
                                        ds,
                                        "consumed_invitation_tokens",
                                        Map.of(
                                                "token_value",
                                                "UNIQUE_TOKEN",
                                                "consumed_at",
                                                LocalDateTime.of(2026, 4, 27, 10, 0, 0))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void h2_token_value_length_64_accepted() {
        // AC10: token_value VARCHAR(64) — exact max-length token is accepted
        DataSource ds = InfoDaoTestSupport.freshH2DataSource();
        InfoDaoTestSupport.applyFullH2Schema(ds);

        String token64 = "A".repeat(64);
        InfoDaoTestSupport.insertDirectly(
                ds,
                "consumed_invitation_tokens",
                Map.of(
                        "token_value",
                        token64,
                        "consumed_at",
                        LocalDateTime.of(2026, 4, 27, 12, 0, 0)));

        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("consumed_invitation_tokens").build())
                .hasNumberOfRows(1)
                .row(0)
                .value("token_value")
                .isEqualTo(token64);
    }

    // -------------------------------------------------------------------------
    // PostgreSQL backend (Rule 1 + Rule 2 at minimum per DEC-26 AC3)
    // -------------------------------------------------------------------------

    @Test
    void postgres_v3_migration_creates_consumed_invitation_tokens_table() {
        DataSource ds = InfoDaoTestSupport.freshPostgresDataSource(POSTGRES);
        InfoDaoTestSupport.applyFullPostgresSchema(ds);

        InfoDaoTestSupport.insertDirectly(
                ds,
                "consumed_invitation_tokens",
                Map.of(
                        "token_value",
                        "TOKEN_PG_001",
                        "consumed_at",
                        LocalDateTime.of(2026, 4, 27, 12, 0, 0)));

        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("consumed_invitation_tokens").build())
                .hasNumberOfRows(1)
                .row(0)
                .value("token_value")
                .isEqualTo("TOKEN_PG_001")
                .value("consumed_by_tenant_id")
                .isNull();
    }

    @Test
    void postgres_consumed_by_tenant_id_nullable() {
        DataSource ds = InfoDaoTestSupport.freshPostgresDataSource(POSTGRES);
        InfoDaoTestSupport.applyFullPostgresSchema(ds);

        InfoDaoTestSupport.insertDirectly(
                ds,
                "consumed_invitation_tokens",
                Map.of(
                        "token_value",
                        "PG_ORPHAN_TOKEN",
                        "consumed_at",
                        LocalDateTime.of(2026, 4, 27, 10, 0, 0)));

        AssertDbConnection assertDb = InfoDaoTestSupport.assertDbOf(ds);
        assertThat(assertDb.table("consumed_invitation_tokens").build())
                .row(0)
                .value("consumed_by_tenant_id")
                .isNull();
    }
}
