package de.vvwt.tm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the V8__e06s03_devices.sql Flyway migration is applied correctly (AC1, E06S03).
 *
 * <p>Checks:
 * <ul>
 *   <li>The {@code devices} table exists after migration</li>
 *   <li>All expected columns are present with correct types and constraints</li>
 *   <li>Default value for {@code device_type} is {@code SCORING_TABLET}</li>
 *   <li>Default value for {@code status} is {@code REGISTERED}</li>
 *   <li>Unique constraint on {@code device_token} is present</li>
 *   <li>Unique constraint on {@code (tenant_id, pin)} is present</li>
 * </ul>
 *
 * @see <a href="../../../../../.gaai/project/contexts/artefacts/stories/E06S03.story.md">Story E06S03</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e06s03migdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class E06S03MigrationIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void devicesTableExists() {
        // H2 INFORMATION_SCHEMA.TABLES uses uppercase table names
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'DEVICES'",
                Long.class);
        assertThat(count)
                .as("AC1 — devices table must exist after V8 migration")
                .isEqualTo(1L);
    }

    @Test
    void devicesTableHasAllExpectedColumns() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'DEVICES' "
                + "ORDER BY COLUMN_NAME");

        List<String> columnNames = columns.stream()
                .map(row -> (String) row.get("COLUMN_NAME"))
                .map(String::toLowerCase)
                .toList();

        assertThat(columnNames).as("AC1 — devices table column set (includes E07S01 additions: device_name, configuration)")
                .containsExactlyInAnyOrder(
                        "id", "tenant_id", "location_id", "device_token", "pin",
                        "device_type", "assigned_field", "status",
                        "registered_at", "last_seen_at",
                        "device_name", "configuration");  // E07S01 AC1 additions
    }

    @Test
    void deviceTypeDefaultIsScoringTablet() {
        // Insert a minimal devices row without specifying device_type to verify DEFAULT
        // We need a real tenant+location row first
        String tenantId = java.util.UUID.randomUUID().toString();
        String locationId = java.util.UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO tenants (id, display_name, tenant_location_count, is_default, created_at)"
                + " VALUES (?, 'Test', 1, FALSE, CURRENT_TIMESTAMP)", tenantId);
        jdbcTemplate.update(
                "INSERT INTO locations (id, tenant_id, display_name, created_at)"
                + " VALUES (?, ?, 'Loc', CURRENT_TIMESTAMP)", locationId, tenantId);

        String deviceId = java.util.UUID.randomUUID().toString();
        String token = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO devices (id, tenant_id, location_id, device_token, pin) "
                + "VALUES (?, ?, ?, ?, ?)",
                deviceId, tenantId, locationId, token, "1234");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT device_type, status FROM devices WHERE id = ?", deviceId);

        assertThat(row.get("DEVICE_TYPE"))
                .as("AC1 — device_type DEFAULT must be 'SCORING_TABLET'")
                .isEqualTo("SCORING_TABLET");
        assertThat(row.get("STATUS"))
                .as("AC1 — status DEFAULT must be 'REGISTERED'")
                .isEqualTo("REGISTERED");
    }

    @Test
    void deviceTokenUniqueConstraintEnforced() {
        String tenantId = java.util.UUID.randomUUID().toString();
        String locationId = java.util.UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO tenants (id, display_name, tenant_location_count, is_default, created_at)"
                + " VALUES (?, 'Test2', 1, FALSE, CURRENT_TIMESTAMP)", tenantId);
        jdbcTemplate.update(
                "INSERT INTO locations (id, tenant_id, display_name, created_at)"
                + " VALUES (?, ?, 'Loc2', CURRENT_TIMESTAMP)", locationId, tenantId);

        String sharedToken = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO devices (id, tenant_id, location_id, device_token, pin) "
                + "VALUES (?, ?, ?, ?, ?)",
                java.util.UUID.randomUUID().toString(), tenantId, locationId, sharedToken, "5678");

        // Second insert with same token must fail
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                jdbcTemplate.update(
                        "INSERT INTO devices (id, tenant_id, location_id, device_token, pin) "
                        + "VALUES (?, ?, ?, ?, ?)",
                        java.util.UUID.randomUUID().toString(), tenantId, locationId,
                        sharedToken, "9999"),
                "AC1 — device_token must be unique (UNIQUE constraint on device_token)");
    }

    @Test
    void tenantPinUniqueConstraintEnforced() {
        String tenantId = java.util.UUID.randomUUID().toString();
        String locationId = java.util.UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO tenants (id, display_name, tenant_location_count, is_default, created_at)"
                + " VALUES (?, 'Test3', 1, FALSE, CURRENT_TIMESTAMP)", tenantId);
        jdbcTemplate.update(
                "INSERT INTO locations (id, tenant_id, display_name, created_at)"
                + " VALUES (?, ?, 'Loc3', CURRENT_TIMESTAMP)", locationId, tenantId);

        jdbcTemplate.update(
                "INSERT INTO devices (id, tenant_id, location_id, device_token, pin) "
                + "VALUES (?, ?, ?, ?, ?)",
                java.util.UUID.randomUUID().toString(), tenantId, locationId,
                java.util.UUID.randomUUID().toString(), "7777");

        // Second device in same tenant with same PIN must fail
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                jdbcTemplate.update(
                        "INSERT INTO devices (id, tenant_id, location_id, device_token, pin) "
                        + "VALUES (?, ?, ?, ?, ?)",
                        java.util.UUID.randomUUID().toString(), tenantId, locationId,
                        java.util.UUID.randomUUID().toString(), "7777"),
                "AC1 — PIN must be unique per tenant (UNIQUE constraint on (tenant_id, pin))");
    }
}
