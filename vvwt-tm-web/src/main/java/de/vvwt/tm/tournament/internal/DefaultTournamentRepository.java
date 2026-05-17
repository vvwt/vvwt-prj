// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Default implementation of {@link TournamentRepository} (DEC-35, E31S01).
 *
 * <p>Uses plain {@link JdbcTemplate} (not Spring Data JDBC CrudRepository). Tenant scoping is
 * enforced via the active {@link TenantContext} binding for all queries.
 *
 * <p>Bean qualifier {@code "tmTournamentRepository"} preserves injection compatibility with
 * {@code @Qualifier("tmTournamentRepository")} call sites established in E21S02.
 *
 * <p>E46S01: snapshot-at-INSERT logic added. On INSERT, {@code tournament.organizer} is populated
 * from {@code tenants.display_name} via an intra-DB JDBC SELECT against the bound per-tenant
 * DataSource. The SELECT runs before the INSERT on the same {@link JdbcTemplate} — no cross-module
 * call to {@code TenantRegistryPort} or any other tenant-module API. If no tenants row exists
 * (DEC-5 invariant violation), an {@link IllegalStateException} is thrown and no tournament row is
 * written.
 *
 * @see TournamentRepository
 * @see <a href="DEC-5">DEC-5 — per-tenant DB invariants</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-35">DEC-35 — package layout: impl in internal</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction (inventory line 311)</a>
 * @see <a href="E31S01">E31S01 — interface extraction + findByIdForUpdate</a>
 * @see <a href="E46S01">E46S01 — Data foundation: snapshot-at-INSERT for tournament.organizer</a>
 */
@Repository("tmTournamentRepository")
public class DefaultTournamentRepository implements TournamentRepository {

    private final JdbcTemplate jdbc;

    /**
     * E46S01: Snapshot SELECT — reads the tenant's display name from the per-tenant {@code tenants}
     * table. Per DEC-5, every per-tenant H2 file has exactly one row in {@code tenants}; no WHERE
     * clause is required. If the result set is empty (invariant violation), {@link
     * #save(Tournament)} throws {@link IllegalStateException} before executing the INSERT.
     *
     * <p>This SELECT runs on the same {@link JdbcTemplate} as the INSERT — it is intra-DB, using
     * the bound per-tenant DataSource. No cross-module call to {@code TenantRegistryPort} is made.
     *
     * @see <a href="E46S01">E46S01 — AC-INSERT-SNAPSHOT-FROM-TENANTS-DB</a>
     */
    private static final String SELECT_TENANT_DISPLAY_NAME = "SELECT display_name FROM tenants";

    private static final String INSERT_SQL =
            "INSERT INTO tournament (id, location_id, description, match_format,"
                    + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                    + " status, created_at, appointment, field_count, team_count,"
                    + " planned_start_time, draft_json, organizer, optimize)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE tournament SET description=?, match_format=?, scoring_rule_id=?,"
                    + " set_validation_rule_id=?, match_generator_id=?, status=?,"
                    + " appointment=?, field_count=?, team_count=?, planned_start_time=?,"
                    + " draft_json=? WHERE id=?";

    private static final String SELECT_BY_ID = "SELECT * FROM tournament WHERE id=?";

    private static final String SELECT_BY_ID_FOR_UPDATE =
            "SELECT * FROM tournament WHERE id=? FOR UPDATE";

    private static final String SELECT_ALL = "SELECT * FROM tournament";

    private static final String DELETE_BY_ID = "DELETE FROM tournament WHERE id=?";

    private static final String EXISTS_BY_ID = "SELECT COUNT(*) FROM tournament WHERE id=?";

    public DefaultTournamentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Inserts if new (no existing row with this id), updates otherwise.
     *
     * <p><b>E46S01 snapshot-at-INSERT (AC-INSERT-SNAPSHOT-FROM-TENANTS-DB):</b> On the INSERT path,
     * this method reads {@code display_name} from the per-tenant {@code tenants} table via {@link
     * #SELECT_TENANT_DISPLAY_NAME} and populates {@code tournament.organizer} with the result. The
     * SELECT runs on the same {@link JdbcTemplate} as the INSERT (intra-DB, no cross-module call).
     * If the {@code tenants} table has no row — a DEC-5 invariant violation — an {@link
     * IllegalStateException} is thrown before the INSERT executes. On the UPDATE path, {@code
     * organizer} is NOT included in the SET clause ({@link #UPDATE_SQL}) — the snapshot value is
     * immutable once stored.
     *
     * @throws IllegalStateException if the INSERT path is taken and the {@code tenants} table
     *     contains no row (missing-tenants-row fail-fast per
     *     AC-INSERT-MISSING-TENANTS-ROW-FAIL-FAST)
     */
    @Override
    public Tournament save(Tournament tournament) {
        Integer count = jdbc.queryForObject(EXISTS_BY_ID, Integer.class, tournament.getId());
        boolean exists = count != null && count > 0;

        if (exists) {
            jdbc.update(
                    UPDATE_SQL,
                    tournament.getDescription(),
                    tournament.getMatchFormat(),
                    tournament.getScoringRuleId(),
                    tournament.getSetValidationRuleId(),
                    tournament.getMatchGeneratorId(),
                    tournament.getStatus(),
                    tournament.getAppointment(),
                    tournament.getFieldCount(),
                    tournament.getTeamCount(),
                    tournament.getPlannedStartTime(),
                    tournament.getDraftJson(),
                    tournament.getId());
        } else {
            // E46S01: snapshot-at-INSERT — read organizer from tenants.display_name (intra-DB)
            List<String> displayNames = jdbc.queryForList(SELECT_TENANT_DISPLAY_NAME, String.class);
            if (displayNames.isEmpty()) {
                throw new IllegalStateException(
                        "INSERT requires tenants row: no row found in tenants table"
                                + " — per-tenant invariant violated (DEC-5)");
            }
            String organizer = displayNames.get(0);

            jdbc.update(
                    INSERT_SQL,
                    tournament.getId(),
                    tournament.getLocationId(),
                    tournament.getDescription(),
                    tournament.getMatchFormat(),
                    tournament.getScoringRuleId(),
                    tournament.getSetValidationRuleId(),
                    tournament.getMatchGeneratorId(),
                    tournament.getStatus(),
                    tournament.getCreatedAt() != null
                            ? tournament.getCreatedAt()
                            : LocalDateTime.now(),
                    tournament.getAppointment(),
                    tournament.getFieldCount(),
                    tournament.getTeamCount(),
                    tournament.getPlannedStartTime(),
                    tournament.getDraftJson(),
                    organizer,
                    tournament.isOptimize());
        }
        return tournament;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Tournament> findById(UUID id) {
        List<Tournament> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public List<Tournament> findAll() {
        return jdbc.query(SELECT_ALL, ROW_MAPPER);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteById(UUID id) {
        jdbc.update(DELETE_BY_ID, id);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Acquires a pessimistic DB row-lock via {@code SELECT … FOR UPDATE} on the {@code
     * tournament} table. Structural clone of {@link #findById(UUID)} with a {@code FOR UPDATE} SQL
     * suffix — same RowMapper, same exception contract.
     *
     * @throws IllegalArgumentException if no tournament with the given id exists for the current
     *     tenant
     * @see <a href="DEC-37">DEC-37 — cascade serialization via per-tournament pessimistic lock</a>
     */
    @Override
    public Tournament findByIdForUpdate(UUID id) {
        List<Tournament> results = jdbc.query(SELECT_BY_ID_FOR_UPDATE, ROW_MAPPER, id);
        if (results.isEmpty()) {
            throw new IllegalArgumentException("Tournament not found for update: id=" + id);
        }
        return results.get(0);
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<Tournament> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    @SuppressWarnings("try") // ResultSet is not AutoCloseable; suppress spurious try-resource lint
    private static Tournament mapRow(ResultSet rs) throws SQLException {
        Tournament t = new Tournament();
        t.setId(rs.getObject("id", UUID.class));
        t.setLocationId(rs.getObject("location_id", UUID.class));
        t.setDescription(rs.getString("description"));
        t.setMatchFormat(rs.getString("match_format"));
        t.setScoringRuleId(rs.getString("scoring_rule_id"));
        t.setSetValidationRuleId(rs.getString("set_validation_rule_id"));
        t.setMatchGeneratorId(rs.getString("match_generator_id"));
        t.setStatus(rs.getString("status"));
        t.setCreatedAt(
                rs.getObject("created_at") != null
                        ? rs.getTimestamp("created_at").toLocalDateTime()
                        : null);
        t.setAppointment(
                rs.getObject("appointment") != null
                        ? rs.getTimestamp("appointment").toLocalDateTime()
                        : null);
        t.setFieldCount(rs.getInt("field_count"));
        t.setTeamCount(rs.getInt("team_count"));
        LocalTime pst =
                rs.getObject("planned_start_time") != null
                        ? rs.getTime("planned_start_time").toLocalTime()
                        : null;
        t.setPlannedStartTime(pst);
        t.setDraftJson(rs.getString("draft_json"));
        t.setOrganizer(rs.getString("organizer"));
        // E51S03 — DEC-55 D-5: optimize flag (column added by E51S01 V3 migration)
        t.setOptimize(rs.getBoolean("optimize"));
        return t;
    }
}
