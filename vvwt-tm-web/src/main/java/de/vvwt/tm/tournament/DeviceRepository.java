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
     * Returns the device with the given PIN for the current tenant.
     *
     * @param pin the 4–6 digit PIN
     * @return Optional.of(device) if found, Optional.empty() otherwise
     */
    Optional<Device> findByPin(String pin);

    /**
     * Returns the device assigned to the given location and field number for the current tenant.
     *
     * @param locationId the location UUID (may be null)
     * @param assignedField the field number (1-based)
     * @return Optional.of(device) if a device is assigned there, Optional.empty() otherwise
     */
    Optional<Device> findByLocationAndField(UUID locationId, int assignedField);

    /**
     * Returns all devices for the current tenant.
     *
     * @param tenantId the tenant UUID
     * @return list of all devices; never null
     */
    List<Device> findAllByTenant(UUID tenantId);

    /**
     * Returns the count of all devices (all types) for the given tenant.
     *
     * @param tenantId the tenant UUID
     * @return number of devices registered for this tenant
     */
    long countByTenant(UUID tenantId);

    /**
     * Returns the count of DISPLAY devices for the given tenant.
     *
     * @param tenantId the tenant UUID
     * @return number of DISPLAY devices registered for this tenant
     */
    long countDisplayByTenant(UUID tenantId);

    /**
     * Returns whether the given PIN is already in use by any device of the current tenant.
     *
     * @param pin the PIN to check
     * @return true if taken, false if available
     */
    boolean isPinTaken(String pin);

    /**
     * Deletes the device with the given id, scoped to the current tenant.
     *
     * @param id the device UUID
     */
    void deleteById(UUID id);

    /**
     * Returns whether the given location exists for the given tenant.
     *
     * @param locationId the location UUID to validate
     * @param tenantId the active tenant UUID
     * @return true if the location exists in this tenant, false otherwise
     */
    boolean locationExistsForTenant(UUID locationId, UUID tenantId);

    /** Deletes all devices for the current tenant. */
    void deleteAllByTenant();
}
