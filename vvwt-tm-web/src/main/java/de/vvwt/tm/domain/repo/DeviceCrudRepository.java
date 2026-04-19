package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Device;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC delegate for {@link Device} persistence.
 *
 * <p>Wired into {@link DeviceRepository} as the low-level CRUD provider. Not intended for direct
 * use by domain/service code — use {@link DeviceRepository} instead to ensure tenant scoping is
 * enforced.
 *
 * @see DeviceRepository
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S03.story.md">Story
 *     E06S03</a>
 */
interface DeviceCrudRepository extends CrudRepository<Device, UUID> {

    /**
     * Finds a device by its unique device token (all tenants — low-level delegate). Callers must
     * apply tenant filtering; use {@link DeviceRepository#findByDeviceToken} instead.
     */
    Optional<Device> findByDeviceToken(String deviceToken);

    /**
     * Finds a device by tenant and PIN (AC4). Callers should use {@link DeviceRepository#findByPin}
     * which adds tenant scoping.
     */
    Optional<Device> findByTenantIdAndPin(UUID tenantId, String pin);

    /**
     * Finds the device assigned to a specific field within a tenant+location (AC5 conflict check).
     */
    Optional<Device> findByTenantIdAndLocationIdAndAssignedField(
            UUID tenantId, UUID locationId, Integer assignedField);

    /**
     * Counts devices of a given type within a tenant+location (E07S02 AC2 — display device limit).
     * Callers should use {@link DeviceRepository#countByDeviceType} which adds tenant scoping.
     */
    long countByTenantIdAndLocationIdAndDeviceType(
            UUID tenantId, UUID locationId, String deviceType);

    /**
     * Counts devices of a given type within a tenant (E14S08 — location-agnostic display limit).
     * Used when devices register without a location (DEC-24).
     */
    long countByTenantIdAndDeviceType(UUID tenantId, String deviceType);

    // Note: No deleteByIdAndTenantId — Spring Data JDBC does not support compound deletes cleanly.
    // Deletion is performed via findById (tenant-scoped) + deleteById in DeviceRepository.
}
