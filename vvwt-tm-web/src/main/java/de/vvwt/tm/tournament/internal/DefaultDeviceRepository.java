package de.vvwt.tm.tournament.internal;

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
 * @see <a href="E49S01">E49S01 — PIN out-of-band + inline-row assignment</a>
 */
@Repository("tmDeviceRepository")
public class DefaultDeviceRepository implements DeviceRepository {

    private final JdbcTemplate jdbc;

    private static final String INSERT_SQL =
            "INSERT INTO devices (id, location_id, device_token, pin, device_type,"
                    + " assigned_field, status, registered_at, last_seen_at, device_name,"
                    + " configuration) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE devices SET location_id=?, device_token=?, pin=?, device_type=?,"
                    + " assigned_field=?, status=?, last_seen_at=?, device_name=?,"
                    + " configuration=? WHERE id=?";

    private static final String SELECT_BY_ID = "SELECT * FROM devices WHERE id=?";

    private static final String SELECT_BY_TOKEN = "SELECT * FROM devices WHERE device_token=?";

    private static final String SELECT_BY_LOCATION_AND_FIELD_WITH_LOCATION =
            "SELECT * FROM devices WHERE location_id=? AND assigned_field=?";

    private static final String SELECT_BY_FIELD_NO_LOCATION =
            "SELECT * FROM devices WHERE location_id IS NULL AND assigned_field=?";

    private static final String SELECT_ALL = "SELECT * FROM devices";

    private static final String COUNT_ALL = "SELECT COUNT(*) FROM devices";

    private static final String COUNT_DISPLAY =
            "SELECT COUNT(*) FROM devices WHERE device_type='DISPLAY'";

    private static final String EXISTS_BY_ID = "SELECT COUNT(*) FROM devices WHERE id=?";

    private static final String PIN_TAKEN = "SELECT COUNT(*) FROM devices WHERE pin=?";

    private static final String DELETE_BY_ID = "DELETE FROM devices WHERE id=?";

    private static final String DELETE_ALL_BY_TENANT = "DELETE FROM devices";

    private static final String COUNT_LOCATION = "SELECT COUNT(*) FROM locations WHERE id=?";

    private static final String NAME_TAKEN = "SELECT COUNT(*) FROM devices WHERE device_name=?";

    private static final String INCREMENT_PIN_FAIL_COUNT =
            "UPDATE devices SET pin_fail_count = pin_fail_count + 1 WHERE id=?";

    private static final String RESET_PIN_FAIL_COUNT =
            "UPDATE devices SET pin_fail_count = 0 WHERE id=?";

    private static final String GET_PIN_FAIL_COUNT =
            "SELECT pin_fail_count FROM devices WHERE id=?";

    public DefaultDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Device save(Device device) {
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
    public List<Device> findAllByTenant() {
        return jdbc.query(SELECT_ALL, ROW_MAPPER);
    }

    /** {@inheritDoc} */
    @Override
    public long countByTenant() {
        Long count = jdbc.queryForObject(COUNT_ALL, Long.class);
        return count != null ? count : 0L;
    }

    /** {@inheritDoc} */
    @Override
    public long countDisplayByTenant() {
        Long count = jdbc.queryForObject(COUNT_DISPLAY, Long.class);
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
    public boolean locationExistsForTenant(UUID locationId) {
        Integer count = jdbc.queryForObject(COUNT_LOCATION, Integer.class, locationId);
        return count != null && count > 0;
    }

    /** {@inheritDoc} */
    @Override
    public void deleteAllByTenant() {
        jdbc.update(DELETE_ALL_BY_TENANT);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isNameTaken(String name) {
        Integer count = jdbc.queryForObject(NAME_TAKEN, Integer.class, name);
        return count != null && count > 0;
    }

    /** {@inheritDoc} */
    @Override
    public void incrementPinFailCount(UUID id) {
        jdbc.update(INCREMENT_PIN_FAIL_COUNT, id);
    }

    /** {@inheritDoc} */
    @Override
    public void resetPinFailCount(UUID id) {
        jdbc.update(RESET_PIN_FAIL_COUNT, id);
    }

    /** {@inheritDoc} */
    @Override
    public int getPinFailCount(UUID id) {
        Integer count = jdbc.queryForObject(GET_PIN_FAIL_COUNT, Integer.class, id);
        return count != null ? count : 0;
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<Device> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private static Device mapRow(ResultSet rs) throws SQLException {
        Device d = new Device();
        d.setId(rs.getObject("id", UUID.class));
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
