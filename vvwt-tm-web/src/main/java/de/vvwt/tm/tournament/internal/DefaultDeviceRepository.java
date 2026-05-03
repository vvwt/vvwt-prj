package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
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
 * Default implementation of {@link DeviceRepository} (DEC-35, E31S01).
 *
 * <p>Uses plain {@link JdbcTemplate}. Tenant scoping enforced via active {@link TenantContext}.
 *
 * <p>Bean qualifier {@code "tmDeviceRepository"} preserves injection compatibility with call sites
 * established in E21S06.
 *
 * @see DeviceRepository
 * @see Device
 * @see <a href="DEC-35">DEC-35 — package layout: impl in internal</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 290)</a>
 * @see <a href="E31S01">E31S01 — interface extraction</a>
 */
@Repository("tmDeviceRepository")
public class DefaultDeviceRepository implements DeviceRepository {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    private static final String INSERT_SQL =
            "INSERT INTO devices (id, tenant_id, location_id, device_token, pin, device_type,"
                    + " assigned_field, status, registered_at, last_seen_at, device_name,"
                    + " configuration) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE devices SET location_id=?, device_token=?, pin=?, device_type=?,"
                    + " assigned_field=?, status=?, last_seen_at=?, device_name=?,"
                    + " configuration=? WHERE id=?";

    private static final String SELECT_BY_ID = "SELECT * FROM devices WHERE id=?";

    private static final String SELECT_BY_TOKEN = "SELECT * FROM devices WHERE device_token=?";

    private static final String SELECT_BY_PIN = "SELECT * FROM devices WHERE pin=?";

    private static final String SELECT_BY_LOCATION_AND_FIELD_WITH_LOCATION =
            "SELECT * FROM devices WHERE location_id=? AND assigned_field=?";

    private static final String SELECT_BY_FIELD_NO_LOCATION =
            "SELECT * FROM devices WHERE location_id IS NULL AND assigned_field=?";

    private static final String SELECT_ALL_BY_TENANT = "SELECT * FROM devices WHERE tenant_id=?";

    private static final String COUNT_BY_TENANT = "SELECT COUNT(*) FROM devices WHERE tenant_id=?";

    private static final String COUNT_DISPLAY_BY_TENANT =
            "SELECT COUNT(*) FROM devices WHERE tenant_id=? AND device_type='DISPLAY'";

    private static final String EXISTS_BY_ID = "SELECT COUNT(*) FROM devices WHERE id=?";

    private static final String PIN_TAKEN = "SELECT COUNT(*) FROM devices WHERE pin=?";

    private static final String DELETE_BY_ID = "DELETE FROM devices WHERE id=?";

    private static final String DELETE_ALL_BY_TENANT = "DELETE FROM devices";

    private static final String COUNT_LOCATION_BY_TENANT =
            "SELECT COUNT(*) FROM locations WHERE id=? AND tenant_id=?";

    public DefaultDeviceRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /** {@inheritDoc} */
    @Override
    public Device save(Device device) {
        UUID currentTenantId = tenantContext.current();
        device.setTenantId(currentTenantId);

        Integer existsCount = jdbc.queryForObject(EXISTS_BY_ID, Integer.class, device.getId());
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
                    device.getId());
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

    /** {@inheritDoc} */
    @Override
    public Optional<Device> findById(UUID id) {
        List<Device> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Device> findByDeviceToken(String deviceToken) {
        List<Device> results = jdbc.query(SELECT_BY_TOKEN, ROW_MAPPER, deviceToken);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Device> findByPin(String pin) {
        List<Device> results = jdbc.query(SELECT_BY_PIN, ROW_MAPPER, pin);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Device> findByLocationAndField(UUID locationId, int assignedField) {
        List<Device> results;
        if (locationId == null) {
            results = jdbc.query(SELECT_BY_FIELD_NO_LOCATION, ROW_MAPPER, assignedField);
        } else {
            results =
                    jdbc.query(
                            SELECT_BY_LOCATION_AND_FIELD_WITH_LOCATION,
                            ROW_MAPPER,
                            locationId,
                            assignedField);
        }
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public List<Device> findAllByTenant(UUID tenantId) {
        return jdbc.query(SELECT_ALL_BY_TENANT, ROW_MAPPER, tenantId);
    }

    /** {@inheritDoc} */
    @Override
    public long countByTenant(UUID tenantId) {
        Long count = jdbc.queryForObject(COUNT_BY_TENANT, Long.class, tenantId);
        return count != null ? count : 0L;
    }

    /** {@inheritDoc} */
    @Override
    public long countDisplayByTenant(UUID tenantId) {
        Long count = jdbc.queryForObject(COUNT_DISPLAY_BY_TENANT, Long.class, tenantId);
        return count != null ? count : 0L;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPinTaken(String pin) {
        Integer count = jdbc.queryForObject(PIN_TAKEN, Integer.class, pin);
        return count != null && count > 0;
    }

    /** {@inheritDoc} */
    @Override
    public void deleteById(UUID id) {
        jdbc.update(DELETE_BY_ID, id);
    }

    /** {@inheritDoc} */
    @Override
    public boolean locationExistsForTenant(UUID locationId, UUID tenantId) {
        Integer count =
                jdbc.queryForObject(COUNT_LOCATION_BY_TENANT, Integer.class, locationId, tenantId);
        return count != null && count > 0;
    }

    /** {@inheritDoc} */
    @Override
    public void deleteAllByTenant() {
        jdbc.update(DELETE_ALL_BY_TENANT);
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
