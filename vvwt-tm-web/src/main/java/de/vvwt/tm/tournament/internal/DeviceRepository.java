package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.Device;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped repository for {@link Device} entities (E21S06, DEC-26 three-rule compliance).
 *
 * <p>Implements the boundary API for the {@code device} aggregate in the {@code tournament} bounded
 * context.
 *
 * <p>Uses plain {@link JdbcTemplate} (not Spring Data JDBC CrudRepository) to avoid entity-mapping
 * conflicts with the parallel legacy {@code de.vvwt.tm.domain.Device} entity that maps to the same
 * {@code devices} table during the reconstruction-in-place phase (DEC-21/DEC-22). At the E21S13
 * atomic cutover, the legacy entity is deleted and this repository can optionally be migrated to
 * Spring Data JDBC.
 *
 * <p>Tenant scoping is enforced via the active {@link TenantContext} binding for all queries.
 *
 * <h2>Boundary-API coverage (5 cross-context importers)</h2>
 *
 * <ul>
 *   <li>{@link #findByDeviceToken} — used by DeviceTokenHandshakeInterceptor,
 *       WebSocketSecurityConfig
 *   <li>{@link #findByPin} — used by admin lookup in DeviceController
 *   <li>{@link #findByLocationAndField} — conflict check for field assignment
 *   <li>{@link #countByTenant} — device limit enforcement
 *   <li>{@link #isPinTaken} — PIN uniqueness guard
 * </ul>
 *
 * @see Device
 * @see DeviceService
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-24">DEC-24 — device location nullable (V16)</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 290)</a>
 */
@Repository("tmDeviceRepository")
public class DeviceRepository {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    private static final String INSERT_SQL =
            "INSERT INTO devices (id, tenant_id, location_id, device_token, pin, device_type,"
                    + " assigned_field, status, registered_at, last_seen_at, device_name,"
                    + " configuration) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE devices SET location_id=?, device_token=?, pin=?, device_type=?,"
                    + " assigned_field=?, status=?, last_seen_at=?, device_name=?,"
                    + " configuration=? WHERE id=? AND tenant_id=?";

    private static final String SELECT_BY_ID = "SELECT * FROM devices WHERE id=? AND tenant_id=?";

    private static final String SELECT_BY_TOKEN = "SELECT * FROM devices WHERE device_token=?";

    private static final String SELECT_BY_PIN = "SELECT * FROM devices WHERE tenant_id=? AND pin=?";

    private static final String SELECT_BY_LOCATION_AND_FIELD =
            "SELECT * FROM devices WHERE tenant_id=? AND location_id=? AND assigned_field=?";

    private static final String SELECT_ALL_BY_TENANT = "SELECT * FROM devices WHERE tenant_id=?";

    private static final String COUNT_BY_TENANT = "SELECT COUNT(*) FROM devices WHERE tenant_id=?";

    private static final String EXISTS_BY_ID =
            "SELECT COUNT(*) FROM devices WHERE id=? AND tenant_id=?";

    private static final String PIN_TAKEN =
            "SELECT COUNT(*) FROM devices WHERE tenant_id=? AND pin=?";

    private static final String DELETE_BY_ID = "DELETE FROM devices WHERE id=? AND tenant_id=?";

    /**
     * Constructs the repository with its required collaborators.
     *
     * @param jdbc the JdbcTemplate
     * @param tenantContext the active tenant context
     */
    public DeviceRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    // -------------------------------------------------------------------------
    // Write path
    // -------------------------------------------------------------------------

    /**
     * Persists a device. Inserts if new (no existing row with this id + tenantId), updates
     * otherwise. Tenant scoping is enforced — the entity's tenantId is set to the current tenant
     * before insert.
     *
     * @param device the device to save (id must be set by caller)
     * @return the saved device (same reference)
     */
    public Device save(Device device) {
        UUID currentTenantId = tenantContext.current();
        device.setTenantId(currentTenantId);

        Integer existsCount =
                jdbc.queryForObject(EXISTS_BY_ID, Integer.class, device.getId(), currentTenantId);
        boolean exists = existsCount != null && existsCount > 0;

        if (exists) {
            jdbc.update(
                    UPDATE_SQL,
                    device.getLocationId(),
                    device.getDeviceToken(),
                    device.getPin(),
                    device.getDeviceType(),
                    device.getAssignedField(),
                    device.getStatus(),
                    device.getLastSeenAt(),
                    device.getDeviceName(),
                    device.getConfiguration(),
                    device.getId(),
                    currentTenantId);
        } else {
            jdbc.update(
                    INSERT_SQL,
                    device.getId(),
                    currentTenantId,
                    device.getLocationId(),
                    device.getDeviceToken(),
                    device.getPin(),
                    device.getDeviceType(),
                    device.getAssignedField(),
                    device.getStatus() != null ? device.getStatus() : Device.STATUS_REGISTERED,
                    device.getRegisteredAt() != null
                            ? device.getRegisteredAt()
                            : LocalDateTime.now(),
                    device.getLastSeenAt(),
                    device.getDeviceName(),
                    device.getConfiguration());
        }
        return device;
    }

    // -------------------------------------------------------------------------
    // Read path
    // -------------------------------------------------------------------------

    /**
     * Returns the device with the given id, scoped to the current tenant.
     *
     * @param id the device UUID
     * @return Optional.of(device) if found, Optional.empty() if not found or wrong tenant
     */
    public Optional<Device> findById(UUID id) {
        UUID tenantId = tenantContext.current();
        List<Device> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns the device with the given device token (cross-tenant lookup for WebSocket auth).
     *
     * <p>This query intentionally does NOT scope by tenant — the device token is globally unique
     * and serves as the authentication credential for WebSocket connections before tenant context
     * is established.
     *
     * @param deviceToken the opaque device token
     * @return Optional.of(device) if found, Optional.empty() otherwise
     */
    public Optional<Device> findByDeviceToken(String deviceToken) {
        List<Device> results = jdbc.query(SELECT_BY_TOKEN, ROW_MAPPER, deviceToken);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns the device with the given PIN for the current tenant.
     *
     * @param pin the 4–6 digit PIN
     * @return Optional.of(device) if found, Optional.empty() if no device has this PIN in this
     *     tenant
     */
    public Optional<Device> findByPin(String pin) {
        UUID tenantId = tenantContext.current();
        List<Device> results = jdbc.query(SELECT_BY_PIN, ROW_MAPPER, tenantId, pin);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns the device assigned to the given location and field number for the current tenant.
     *
     * <p>Used for field-assignment conflict checking — ensures no two devices are assigned to the
     * same location + field.
     *
     * @param locationId the location UUID
     * @param assignedField the field number (1-based)
     * @return Optional.of(device) if a device is assigned there, Optional.empty() otherwise
     */
    public Optional<Device> findByLocationAndField(UUID locationId, int assignedField) {
        UUID tenantId = tenantContext.current();
        List<Device> results =
                jdbc.query(
                        SELECT_BY_LOCATION_AND_FIELD,
                        ROW_MAPPER,
                        tenantId,
                        locationId,
                        assignedField);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns all devices for the current tenant.
     *
     * @param tenantId the tenant UUID (must match the current tenant context)
     * @return list of all devices for this tenant; never null
     */
    public List<Device> findAllByTenant(UUID tenantId) {
        return jdbc.query(SELECT_ALL_BY_TENANT, ROW_MAPPER, tenantId);
    }

    /**
     * Returns the count of all devices (all types) for the given tenant.
     *
     * <p>Used for device limit enforcement.
     *
     * @param tenantId the tenant UUID
     * @return number of devices registered for this tenant
     */
    public long countByTenant(UUID tenantId) {
        Long count = jdbc.queryForObject(COUNT_BY_TENANT, Long.class, tenantId);
        return count != null ? count : 0L;
    }

    /**
     * Returns {@code true} if the given PIN is already in use by any device of the current tenant.
     *
     * <p>Used for PIN uniqueness guard during SCORING_TABLET registration.
     *
     * @param pin the PIN to check
     * @return {@code true} if taken, {@code false} if available
     */
    public boolean isPinTaken(String pin) {
        UUID tenantId = tenantContext.current();
        Integer count = jdbc.queryForObject(PIN_TAKEN, Integer.class, tenantId, pin);
        return count != null && count > 0;
    }

    /**
     * Deletes the device with the given id, scoped to the current tenant.
     *
     * @param id the device UUID
     */
    public void deleteById(UUID id) {
        UUID tenantId = tenantContext.current();
        jdbc.update(DELETE_BY_ID, id, tenantId);
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<Device> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private static Device mapRow(ResultSet rs) throws SQLException {
        Device d = new Device();
        d.setId(rs.getObject("id", UUID.class));
        d.setTenantId(rs.getObject("tenant_id", UUID.class));
        d.setLocationId(rs.getObject("location_id", UUID.class));
        d.setDeviceToken(rs.getString("device_token"));
        d.setPin(rs.getString("pin"));
        d.setDeviceType(rs.getString("device_type"));
        d.setStatus(rs.getString("status"));
        int assignedField = rs.getInt("assigned_field");
        d.setAssignedField(rs.wasNull() ? null : assignedField);
        LocalDateTime registeredAt = rs.getObject("registered_at", LocalDateTime.class);
        d.setRegisteredAt(registeredAt);
        LocalDateTime lastSeenAt = rs.getObject("last_seen_at", LocalDateTime.class);
        d.setLastSeenAt(lastSeenAt);
        d.setDeviceName(rs.getString("device_name"));
        d.setConfiguration(rs.getString("configuration"));
        return d;
    }
}
