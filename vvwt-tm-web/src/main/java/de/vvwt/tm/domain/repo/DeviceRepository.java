package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Device;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link Device} entities.
 *
 * <p>Extends {@link TenantScopedRepository} to ensure all device queries are filtered
 * by the active tenant (DEC-5, DEC-17, AC9).
 *
 * <h2>Additional tenant-scoped query methods</h2>
 * <ul>
 *   <li>{@link #findByDeviceToken(String)} — finds device by token, filtered to active tenant
 *       (AC2, AC3, AC8)</li>
 *   <li>{@link #findByPin(String)} — finds device by PIN within active tenant (AC4)</li>
 *   <li>{@link #findByLocationAndField(UUID, Integer)} — finds device assigned to a field
 *       within a location for the active tenant (AC5 conflict check)</li>
 * </ul>
 *
 * @see TenantScopedRepository
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S03.story.md">Story E06S03</a>
 */
@Repository
public class DeviceRepository extends TenantScopedRepository<Device, UUID> {

    private final DeviceCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public DeviceRepository(DeviceCrudRepository delegate,
                            TenantContext tenantContext,
                            JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override protected CrudRepository<Device, UUID> delegate() { return delegate; }
    @Override protected TenantContext tenantContext() { return tenantContext; }
    @Override protected JdbcAggregateOperations jdbcOperations() { return jdbcOps; }
    @Override protected UUID extractTenantId(Device e) { return e.getTenantId(); }
    @Override protected void setTenantId(Device e, UUID id) { e.setTenantId(id); }
    @Override protected UUID extractId(Device e) { return e.getId(); }

    /**
     * Finds a device by its device token, scoped to the active tenant (AC3, AC8).
     *
     * <p>Used by the tablet to poll its own status and by any endpoint that validates
     * device token ownership.
     *
     * @param deviceToken the opaque device token
     * @return the device if found and owned by the active tenant; {@link Optional#empty()} otherwise
     */
    public Optional<Device> findByDeviceToken(String deviceToken) {
        UUID tenantId = activeTenantId();
        return delegate.findByDeviceToken(deviceToken)
                .filter(d -> tenantId.equals(d.getTenantId()));
    }

    /**
     * Finds a device by its PIN within the active tenant (AC4).
     *
     * <p>Used by the admin UI to look up a registered tablet by the PIN displayed on its screen.
     *
     * @param pin the numeric PIN
     * @return the device if found; {@link Optional#empty()} otherwise
     */
    public Optional<Device> findByPin(String pin) {
        UUID tenantId = activeTenantId();
        return delegate.findByTenantIdAndPin(tenantId, pin);
    }

    /**
     * Finds the device currently assigned to a specific field within a location, scoped
     * to the active tenant (AC5 — conflict check before assignment).
     *
     * @param locationId  the location UUID
     * @param fieldNumber the court field number
     * @return the device assigned to that field, or {@link Optional#empty()} if unoccupied
     */
    public Optional<Device> findByLocationAndField(UUID locationId, Integer fieldNumber) {
        UUID tenantId = activeTenantId();
        return delegate.findByTenantIdAndLocationIdAndAssignedField(tenantId, locationId,
                fieldNumber);
    }

    /**
     * Returns whether a PIN is already in use for the active tenant (AC7 — PIN uniqueness guard).
     *
     * @param pin the PIN to check
     * @return {@code true} if the PIN is already taken by an existing device
     */
    public boolean isPinTaken(String pin) {
        return findByPin(pin).isPresent();
    }

    /**
     * Counts devices of the given type within the active tenant and specified location (E07S02 AC2).
     *
     * <p>Used to enforce the configurable device limit before registration. The count is
     * scoped to both tenant (DEC-5 isolation) and location (devices are per-location per DEC-5).
     *
     * @param locationId the location UUID
     * @param deviceType the device type to count (e.g., {@code Device.TYPE_DISPLAY})
     * @return number of registered devices of that type for the active tenant+location
     */
    public long countByDeviceType(UUID locationId, String deviceType) {
        UUID tenantId = activeTenantId();
        return delegate.countByTenantIdAndLocationIdAndDeviceType(tenantId, locationId, deviceType);
    }

    /**
     * Deletes a device by its ID, scoped to the active tenant (E07S02 AC5).
     *
     * <p>First looks up the device by ID within the active tenant scope (DEC-5 isolation).
     * If found, deletes it. Returns {@code false} if the device does not exist for the
     * active tenant (→ caller should return 404).
     *
     * @param deviceId the device UUID to delete
     * @return {@code true} if the device was found and deleted; {@code false} if not found
     */
    public boolean deleteDevice(UUID deviceId) {
        // Tenant-scoped findById: only returns device if it belongs to the active tenant
        return findById(deviceId)
                .map(device -> {
                    delegate.deleteById(device.getId());
                    return true;
                })
                .orElse(false);
    }
}
