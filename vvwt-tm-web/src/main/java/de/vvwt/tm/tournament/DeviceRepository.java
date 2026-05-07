package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public port for tenant-scoped {@link Device} persistence (DEC-35, E31S01).
 *
 * <p>Hand-authored interface port per DEC-35 §2. Cross-context consumers (e.g., {@code
 * de.vvwt.tm.infrastructure.score.ScoreEntryService}, {@code
 * de.vvwt.tm.display.DisplayOverviewService}, {@code
 * de.vvwt.tm.infrastructure.web.WebSocketSecurityConfig}, {@code
 * de.vvwt.tm.infrastructure.web.DeviceTokenHandshakeInterceptor}) reference this interface instead
 * of the former {@code tournament.internal.DeviceRepository} concrete class, eliminating forbidden
 * {@code .internal} imports per DEC-35.
 *
 * <p>The canonical implementation is {@link
 * de.vvwt.tm.tournament.internal.DefaultDeviceRepository}.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultDeviceRepository
 * @see Device
 * @see <a href="DEC-35">DEC-35 — package layout: interfaces in public package</a>
 * @see <a href="E31S01">E31S01 — interface extraction</a>
 * @see <a href="E49S01">E49S01 — PIN out-of-band + inline-row assignment</a>
 */
public interface DeviceRepository {

    /**
     * Persists a device (upsert). Tenant scoping is enforced.
     *
     * @param device the device to save (id must be set by caller)
     * @return the saved device
     */
    Device save(Device device);

    /**
     * Returns the device with the given id, scoped to the current tenant.
     *
     * @param id the device UUID
     * @return Optional.of(device) if found, Optional.empty() otherwise
     */
    Optional<Device> findById(UUID id);

    /**
     * Returns the device with the given device token (cross-tenant lookup for WebSocket auth).
     *
     * <p>Intentionally does NOT scope by tenant — the device token is globally unique and serves as
     * the authentication credential for WebSocket connections before tenant context is established.
     *
     * @param deviceToken the opaque device token
     * @return Optional.of(device) if found, Optional.empty() otherwise
     */
    Optional<Device> findByDeviceToken(String deviceToken);

    /**
     * Returns the device assigned to the given location and field number for the current tenant.
     *
     * @param locationId the location UUID (may be null)
     * @param assignedField the field number (1-based)
     * @return Optional.of(device) if a device is assigned there, Optional.empty() otherwise
     */
    Optional<Device> findByLocationAndField(UUID locationId, int assignedField);

    /**
     * Returns all devices for the current tenant (DB-per-tenant routing provides scoping).
     *
     * @return list of all devices; never null
     */
    List<Device> findAllByTenant();

    /**
     * Returns the count of all devices (all types) for the current tenant.
     *
     * @return number of devices in the current tenant database
     */
    long countByTenant();

    /**
     * Returns the count of DISPLAY devices for the current tenant.
     *
     * @return number of DISPLAY devices in the current tenant database
     */
    long countDisplayByTenant();

    /**
     * Returns whether the given PIN is already in use by any device of the current tenant.
     *
     * @param pin the PIN to check
     * @return true if taken, false if available
     */
    boolean isPinTaken(String pin);

    /**
     * Returns whether the given device name is already in use by any device of the current tenant.
     *
     * <p>Used by {@link
     * de.vvwt.tm.tournament.internal.DefaultDeviceService#generateUniqueDeviceName()} to check for
     * name collisions before assignment.
     *
     * @param name the device name to check (e.g., "Tablet-A3F2")
     * @return true if name is already taken, false if available
     * @see <a href="E49S01">E49S01 — AC1: unique device name generation</a>
     */
    boolean isNameTaken(String name);

    /**
     * Deletes the device with the given id, scoped to the current tenant.
     *
     * @param id the device UUID
     */
    void deleteById(UUID id);

    /**
     * Returns whether the given location exists in the current tenant database.
     *
     * @param locationId the location UUID to validate
     * @return true if the location exists, false otherwise
     */
    boolean locationExistsForTenant(UUID locationId);

    /** Deletes all devices for the current tenant. */
    void deleteAllByTenant();

    /**
     * Atomically increments the {@code pin_fail_count} for the device with the given id.
     *
     * <p>Uses {@code UPDATE devices SET pin_fail_count = pin_fail_count + 1 WHERE id = ?} to avoid
     * a read-modify-write race condition.
     *
     * @param id the device UUID
     * @see <a href="E49S01">E49S01 — AC6: per-device fail-counter</a>
     */
    void incrementPinFailCount(UUID id);

    /**
     * Resets the {@code pin_fail_count} to 0 for the device with the given id.
     *
     * <p>Called on successful assign or on admin reset via {@code POST
     * /api/devices/{id}/pin-lock/reset}.
     *
     * @param id the device UUID
     * @see <a href="E49S01">E49S01 — AC6: fail-counter reset</a>
     */
    void resetPinFailCount(UUID id);

    /**
     * Returns the current {@code pin_fail_count} for the device with the given id.
     *
     * @param id the device UUID
     * @return current consecutive wrong-PIN attempt count; 0 if device not found or counter not set
     * @see <a href="E49S01">E49S01 — AC6: per-device fail-counter read</a>
     */
    int getPinFailCount(UUID id);
}
