package de.vvwt.tm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that the V10__e07s01_device_model_extension.sql Flyway migration is applied
 * correctly (AC1–AC4, E07S01).
 *
 * <p>Checks:
 * <ul>
 *   <li>AC1 — {@code pin} column is now nullable</li>
 *   <li>AC1 — {@code device_name} column is present and nullable</li>
 *   <li>AC1 — {@code configuration} column is present and nullable</li>
 *   <li>AC3 — Two devices with {@code pin = NULL} in the same tenant do NOT violate any
 *       constraint (unique constraint on (tenant_id, pin) was dropped in V10)</li>
 *   <li>AC4 — Existing scoring tablet inserts (with pin) still work; values retained</li>
 *   <li>AC5 — JSON configuration string can be stored and retrieved from the TEXT column</li>
 * </ul>
 *
 * @see <a href="../../../../../.gaai/project/contexts/artefacts/stories/E07S01.story.md">Story E07S01</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e07s01migdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class E07S01MigrationIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // AC1 — Schema verification: new columns present and nullable
    // -------------------------------------------------------------------------

    @Test
    void devicesTableHasDeviceNameColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'DEVICES' AND COLUMN_NAME = 'DEVICE_NAME'");

        assertThat(columns).as("AC1 — device_name column must exist").hasSize(1);
        String nullable = (String) columns.get(0).get("IS_NULLABLE");
        assertThat(nullable).as("AC1 — device_name must be nullable").isEqualToIgnoringCase("YES");
    }

    @Test
    void devicesTableHasConfigurationColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'DEVICES' AND COLUMN_NAME = 'CONFIGURATION'");

        assertThat(columns).as("AC1 — configuration column must exist").hasSize(1);
        String nullable = (String) columns.get(0).get("IS_NULLABLE");
        assertThat(nullable).as("AC1 — configuration must be nullable").isEqualToIgnoringCase("YES");
    }

    @Test
    void pinColumnIsNullable() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'DEVICES' AND COLUMN_NAME = 'PIN'");

        assertThat(columns).as("AC1 — pin column must exist").hasSize(1);
        String nullable = (String) columns.get(0).get("IS_NULLABLE");
        assertThat(nullable).as("AC1 — pin must be nullable after V10 migration")
                .isEqualToIgnoringCase("YES");
    }

    // -------------------------------------------------------------------------
    // AC3 — Two NULL-pin devices in same tenant do not violate any constraint
    // -------------------------------------------------------------------------

    @Test
    void twoNullPinDevicesInSameTenantDoNotViolateConstraint() {
        String tenantId = UUID.randomUUID().toString();
        String locationId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO tenants (id, display_name, tenant_location_count, is_default, created_at)"
                + " VALUES (?, 'AC3Tenant', 1, FALSE, CURRENT_TIMESTAMP)", tenantId);
        jdbcTemplate.update(
                "INSERT INTO locations (id, tenant_id, display_name, created_at)"
                + " VALUES (?, ?, 'AC3Loc', CURRENT_TIMESTAMP)", locationId, tenantId);

        // Insert first display device with pin = NULL
        assertDoesNotThrow(() ->
                jdbcTemplate.update(
                        "INSERT INTO devices (id, tenant_id, location_id, device_token, pin,"
                        + " device_type, status, registered_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'DISPLAY', 'REGISTERED', CURRENT_TIMESTAMP)",
                        UUID.randomUUID().toString(), tenantId, locationId,
                        UUID.randomUUID().toString()),
                "AC3 — first NULL-pin insert must succeed");

        // Insert second display device with pin = NULL in same tenant — must NOT throw
        assertDoesNotThrow(() ->
                jdbcTemplate.update(
                        "INSERT INTO devices (id, tenant_id, location_id, device_token, pin,"
                        + " device_type, status, registered_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'DISPLAY', 'REGISTERED', CURRENT_TIMESTAMP)",
                        UUID.randomUUID().toString(), tenantId, locationId,
                        UUID.randomUUID().toString()),
                "AC3 — second NULL-pin insert in same tenant must NOT violate constraint "
                + "(unique constraint on (tenant_id, pin) was dropped in V10)");
    }

    // -------------------------------------------------------------------------
    // AC4 — Backward compatibility: existing scoring tablet inserts still work
    // -------------------------------------------------------------------------

    @Test
    void scoringTabletWithPinStillInsertsAndRetainsPin() {
        String tenantId = UUID.randomUUID().toString();
        String locationId = UUID.randomUUID().toString();
        String pin = "4242";

        jdbcTemplate.update(
                "INSERT INTO tenants (id, display_name, tenant_location_count, is_default, created_at)"
                + " VALUES (?, 'AC4Tenant', 1, FALSE, CURRENT_TIMESTAMP)", tenantId);
        jdbcTemplate.update(
                "INSERT INTO locations (id, tenant_id, display_name, created_at)"
                + " VALUES (?, ?, 'AC4Loc', CURRENT_TIMESTAMP)", locationId, tenantId);

        String deviceId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO devices (id, tenant_id, location_id, device_token, pin,"
                + " device_type, status, registered_at) "
                + "VALUES (?, ?, ?, ?, ?, 'SCORING_TABLET', 'REGISTERED', CURRENT_TIMESTAMP)",
                deviceId, tenantId, locationId, UUID.randomUUID().toString(), pin);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT pin, device_type FROM devices WHERE id = ?", deviceId);

        assertThat(row.get("PIN"))
                .as("AC4 — scoring tablet pin must be retained after V10 migration")
                .isEqualTo(pin);
        assertThat(row.get("DEVICE_TYPE"))
                .as("AC4 — scoring tablet device_type must remain SCORING_TABLET")
                .isEqualTo("SCORING_TABLET");
    }

    // -------------------------------------------------------------------------
    // AC5 — JSON configuration string can be stored and retrieved
    // -------------------------------------------------------------------------

    @Test
    void configurationJsonCanBeStoredAndRetrieved() {
        String tenantId = UUID.randomUUID().toString();
        String locationId = UUID.randomUUID().toString();
        String configJson = "{\"display_schema\":\"OVERVIEW\"}";

        jdbcTemplate.update(
                "INSERT INTO tenants (id, display_name, tenant_location_count, is_default, created_at)"
                + " VALUES (?, 'AC5Tenant', 1, FALSE, CURRENT_TIMESTAMP)", tenantId);
        jdbcTemplate.update(
                "INSERT INTO locations (id, tenant_id, display_name, created_at)"
                + " VALUES (?, ?, 'AC5Loc', CURRENT_TIMESTAMP)", locationId, tenantId);

        String deviceId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO devices (id, tenant_id, location_id, device_token, pin,"
                + " device_type, status, registered_at, device_name, configuration) "
                + "VALUES (?, ?, ?, ?, NULL, 'DISPLAY', 'REGISTERED', CURRENT_TIMESTAMP,"
                + " 'Main Display', ?)",
                deviceId, tenantId, locationId, UUID.randomUUID().toString(), configJson);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT device_name, configuration FROM devices WHERE id = ?", deviceId);

        assertThat(row.get("DEVICE_NAME"))
                .as("AC5 — device_name must be stored and retrieved correctly")
                .isEqualTo("Main Display");
        assertThat(row.get("CONFIGURATION"))
                .as("AC5 — configuration JSON must be stored and retrieved correctly")
                .isEqualTo(configJson);
    }
}
