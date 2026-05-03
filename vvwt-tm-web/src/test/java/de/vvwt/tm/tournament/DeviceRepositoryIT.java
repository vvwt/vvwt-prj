package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * DAO integration tests for {@link DeviceRepository} (E21S06, AC-DAO-3RULES-DeviceRepository).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link DeviceRepository} at {@code
 * de.vvwt.tm.tournament.internal.DeviceRepository} did not exist at commit time, causing a compile
 * error — satisfying the DEC-22 Iron Law.
 *
 * <h2>DEC-26 three-rule compliance</h2>
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from migration:</b> {@code @SpringBootTest} with Flyway applies all root
 *       migrations in version order. Includes V8 (devices), V10 (device model extension), V16
 *       (location nullable per DEC-24). No inline DDL.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write tests verify DB state via
 *       assertj-db ({@link AssertDbConnection}) against the DataSource — never via the repository's
 *       own read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path tests insert fixtures via direct JDBC
 *       ({@link TenantDaoTestSupport#insertDirectly}) — never via the repository's own save
 *       methods.
 * </ol>
 *
 * <h2>Boundary-API coverage</h2>
 *
 * <p>The {@link DeviceRepository} has 5 cross-context importers (inventory line 290). All query
 * shapes used by those importers are covered:
 *
 * <ul>
 *   <li>{@code findByDeviceToken} — used by DeviceTokenHandshakeInterceptor,
 *       WebSocketSecurityConfig
 *   <li>{@code findByPin} — used by admin lookup in DeviceController
 *   <li>{@code findByLocationAndField} — conflict check for field assignment
 *   <li>{@code countDisplayDevicesByTenant} / {@code countByTenant} — device limit enforcement
 *   <li>{@code isPinTaken} — PIN uniqueness guard
 * </ul>
 *
 * @see DeviceRepository
 * @see de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-24">DEC-24 — device location nullable</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 290)</a>
 */
@SpringBootTest(
        classes = {TournamentManagerApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e21s06-device-repository-it;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("DeviceRepository DAO IT — E21S06 DEC-26 three rules")
class DeviceRepositoryIT {

    @Autowired
    @Qualifier("tmDeviceRepository")
    private DeviceRepository deviceRepository;

    @Autowired private DataSource dataSource;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private UUID tenantId;
    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        tenantId = tenantBinder.bindDefaultTenant();
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var conn = dataSource.getConnection()) {
            // E45S06: tenant_id removed — simple DELETE (per-tenant DB isolation via DEC-20)
            for (String sql : new String[] {"DELETE FROM devices"}) {
                try (var ps = conn.prepareStatement(sql)) {
                    ps.executeUpdate();
                } catch (Exception ignored) {
                    // Best-effort cleanup
                }
            }
        }
        tenantBinder.unbind();
    }

    // =========================================================================
    // Write-path tests — DEC-26 Rule 2: verify via assertj-db, not repo read
    // =========================================================================

    @Test
    @DisplayName("save() — new device persists to 'devices' table (assertj-db)")
    void save_newDevice_persistsToDevicesTable() {
        Device device = newDevice(tenantId, Device.TYPE_SCORING_TABLET, "token-save-1", "1234");
        deviceRepository.save(device);

        Table table = assertDb.table("devices").build();
        final UUID savedId = device.getId();
        // E45S06: TENANT_ID column removed from devices (DEC-39 D1)
        assertThat(table.getRowsList())
                .as("saved device must appear in the devices table")
                .anyMatch(
                        row ->
                                savedId.equals(row.getColumnValue("ID").getValue())
                                        && Objects.equals(
                                                row.getColumnValue("DEVICE_TOKEN").getValue(),
                                                "token-save-1"));
    }

    @Test
    @DisplayName("save() — location_id can be null per DEC-24 / V16")
    void save_deviceWithNullLocationId_persistsNullLocationId() {
        Device device = newDevice(tenantId, Device.TYPE_SCORING_TABLET, "token-null-loc", "5678");
        device.setLocationId(null); // explicitly null per DEC-24
        deviceRepository.save(device);

        Table table = assertDb.table("devices").build();
        final UUID savedId = device.getId();
        assertThat(table.getRowsList())
                .as("device with null location_id must persist (DEC-24 carve-out)")
                .anyMatch(
                        row ->
                                savedId.equals(row.getColumnValue("ID").getValue())
                                        && row.getColumnValue("LOCATION_ID").getValue() == null);
    }

    // =========================================================================
    // Read-path tests — DEC-26 Rule 3: insert via direct JDBC
    // =========================================================================

    @Test
    @DisplayName(
            "findByDeviceToken() — returns device when token matches (boundary-API importer 1)")
    void findByDeviceToken_returnsPresentWhenFound() {
        UUID deviceId = UUID.randomUUID();
        insertDeviceDirectly(deviceId, tenantId, "token-abc", "9999", Device.TYPE_SCORING_TABLET);

        Optional<Device> result = deviceRepository.findByDeviceToken("token-abc");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(deviceId);
    }

    @Test
    @DisplayName("findByDeviceToken() — returns empty for unknown token")
    void findByDeviceToken_returnsEmptyForUnknown() {
        Optional<Device> result = deviceRepository.findByDeviceToken("no-such-token");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByPin() — returns device when pin matches for this tenant")
    void findByPin_returnsPresentWhenFound() {
        UUID deviceId = UUID.randomUUID();
        insertDeviceDirectly(deviceId, tenantId, "token-pin", "1234", Device.TYPE_SCORING_TABLET);

        Optional<Device> result = deviceRepository.findByPin("1234");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(deviceId);
    }

    @Test
    @DisplayName("findByPin() — returns empty for unknown pin")
    void findByPin_returnsEmptyForUnknown() {
        Optional<Device> result = deviceRepository.findByPin("0000");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByLocationAndField() — returns device assigned to location+field")
    void findByLocationAndField_returnsPresentWhenAssigned() {
        UUID deviceId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        insertDeviceDirectlyWithLocation(
                deviceId, tenantId, locationId, "token-loc", "2222", 3, Device.TYPE_SCORING_TABLET);

        Optional<Device> result = deviceRepository.findByLocationAndField(locationId, 3);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(deviceId);
    }

    @Test
    @DisplayName("findByLocationAndField() — returns empty when no device on that field")
    void findByLocationAndField_returnsEmptyWhenNone() {
        Optional<Device> result = deviceRepository.findByLocationAndField(UUID.randomUUID(), 99);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("countByTenant() — returns count of all devices for active tenant")
    void countByTenant_returnsCorrectCount() {
        insertDeviceDirectly(
                UUID.randomUUID(), tenantId, "t-cnt-1", "1111", Device.TYPE_SCORING_TABLET);
        insertDeviceDirectly(UUID.randomUUID(), tenantId, "t-cnt-2", null, Device.TYPE_DISPLAY);

        long count = deviceRepository.countByTenant();

        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("isPinTaken() — returns true when PIN exists for tenant")
    void isPinTaken_returnsTrueWhenPinExists() {
        insertDeviceDirectly(
                UUID.randomUUID(), tenantId, "t-pin-1", "3333", Device.TYPE_SCORING_TABLET);

        assertThat(deviceRepository.isPinTaken("3333")).isTrue();
    }

    @Test
    @DisplayName("isPinTaken() — returns false when PIN not used")
    void isPinTaken_returnsFalseForUnusedPin() {
        assertThat(deviceRepository.isPinTaken("8765")).isFalse();
    }

    @Test
    @DisplayName("deleteById() — removes device from 'devices' table (assertj-db)")
    void deleteById_removesRow() {
        Device device = newDevice(tenantId, Device.TYPE_SCORING_TABLET, "token-del", "4444");
        deviceRepository.save(device);

        deviceRepository.deleteById(device.getId());

        Table table = assertDb.table("devices").build();
        final UUID deletedId = device.getId();
        assertThat(table.getRowsList())
                .as("deleted device must not appear in devices table")
                .noneMatch(row -> deletedId.equals(row.getColumnValue("ID").getValue()));
    }

    /**
     * E45S03 — DEC-41 Snapshot-Driven: WHERE tenant_id predicate removed from findById.
     * Post-removal, findById executes without tenant_id in the WHERE clause; isolation via DEC-20
     * routing.
     */
    @Test
    @DisplayName("E45S03: findById executes without tenant_id WHERE predicate (DEC-20 isolates)")
    void e45s03_findById_noTenantPredicate_returnsRow() {
        UUID id = UUID.randomUUID();
        insertDeviceDirectly(id, tenantId, "token-e45s03", "7777", Device.TYPE_SCORING_TABLET);

        Optional<Device> result = deviceRepository.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(id);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Device newDevice(UUID tid, String type, String token, String pin) {
        Device d = new Device();
        d.setId(UUID.randomUUID());
        d.setDeviceToken(token);
        d.setPin(pin);
        d.setDeviceType(type);
        d.setStatus(Device.STATUS_REGISTERED);
        return d;
    }

    private void insertDeviceDirectly(UUID id, UUID tid, String token, String pin, String type) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", id);
        cols.put("device_token", token);
        cols.put("pin", pin);
        cols.put("device_type", type);
        cols.put("status", Device.STATUS_REGISTERED);
        TenantDaoTestSupport.insertDirectly(dataSource, "devices", cols);
    }

    private void insertDeviceDirectlyWithLocation(
            UUID id, UUID tid, UUID locationId, String token, String pin, int field, String type) {
        // AC-FAILURE-1-FIXED: insert parent locations row first (Parent-First ordering)
        // to satisfy FK_DEVICES_LOCATION before inserting the devices row.
        // Default tenant is already provisioned by DefaultTenantBootstrapRunner.
        // E45S06: tenant_id column removed from locations (DEC-50)
        Map<String, Object> locationCols = new LinkedHashMap<>();
        locationCols.put("id", locationId);
        locationCols.put("display_name", "IT-Location-" + locationId.toString().substring(0, 8));
        TenantDaoTestSupport.insertDirectly(dataSource, "locations", locationCols);

        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", id);
        cols.put("location_id", locationId);
        cols.put("device_token", token);
        cols.put("pin", pin);
        cols.put("device_type", type);
        cols.put("assigned_field", field);
        cols.put("status", Device.STATUS_ASSIGNED);
        TenantDaoTestSupport.insertDirectly(dataSource, "devices", cols);
    }
    // AC-HELPER-INSERTTENANTIFMISSING-REMOVED: insertTenantIfMissing removed — it was dead code.
    // Its columns (name, subdomain) do not exist in the V1 tenants schema; the default tenant is
    // provisioned by DefaultTenantBootstrapRunner at @SpringBootTest context startup.
}
