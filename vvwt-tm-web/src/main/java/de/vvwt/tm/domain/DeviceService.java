package de.vvwt.tm.domain;

import de.vvwt.tm.config.DeviceLimitConfig;
import de.vvwt.tm.domain.repo.DeviceRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.web.ConflictException;
import de.vvwt.tm.tenant.DefaultTenantProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for device registration, assignment, and management (E06S03, E07S02).
 *
 * <h2>E06S03 Responsibilities</h2>
 * <ul>
 *   <li>AC2 — Register a device: generate device token + PIN, persist</li>
 *   <li>AC3 — Get device status by token</li>
 *   <li>AC4 — Find device by PIN</li>
 *   <li>AC5 — Assign device to a court field</li>
 *   <li>AC6 — Unassign device from a court field</li>
 *   <li>AC7 — PIN uniqueness within tenant; digit length escalation</li>
 *   <li>AC8 — Device token validation</li>
 *   <li>AC10 — PIN generation exhaustion handling</li>
 *   <li>AC11 — Cryptographically random device token</li>
 * </ul>
 *
 * <h2>PIN generation (AC7, AC10, AC11)</h2>
 * <p>PINs start at 4 digits and escalate to 5, then 6 digits if the space is exhausted.
 * The generation loop tries up to {@code MAX_PIN_ATTEMPTS} random candidates per digit
 * length before escalating. Trivially confusable sequences (all-same-digit, sequential
 * ascending/descending) are excluded. The PIN is NOT a security token — it is a short
 * human-readable assignment code.
 *
 * <h2>Tenant and location scope (DEC-5, DEC-17)</h2>
 * <p>In V1 default-tenant-LAN mode the location is resolved from the default location
 * of the active tenant. {@link DefaultTenantProvider} supplies the default tenant ID;
 * location resolution uses the first location associated with that tenant.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S03.story.md">Story E06S03</a>
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    /**
     * Maximum attempts to find an unused PIN at each digit length before escalating.
     * At 4 digits (9000 non-trivial candidates), 50 attempts is sufficient at low device counts.
     */
    private static final int MAX_PIN_ATTEMPTS = 50;

    /** Minimum and maximum PIN digit lengths (AC10 escalation). */
    private static final int PIN_DIGITS_MIN = 4;
    private static final int PIN_DIGITS_MAX = 6;

    /**
     * Confusable PIN patterns to skip (AC7):
     * - All-same-digit sequences: 0000, 1111, ..., 9999
     * - Sequential ascending: 0123, 1234, ..., 6789
     * - Sequential descending: 9876, 8765, ..., 3210
     */
    private static final Set<String> CONFUSABLE_PINS_4 = buildConfusable4DigitSet();

    private final DeviceRepository deviceRepository;
    private final TournamentRepository tournamentRepository;
    private final DeviceLimitConfig deviceLimitConfig;
    private final SecureRandom secureRandom;

    public DeviceService(DeviceRepository deviceRepository,
                         TournamentRepository tournamentRepository,
                         DeviceLimitConfig deviceLimitConfig) {
        this.deviceRepository = deviceRepository;
        this.tournamentRepository = tournamentRepository;
        this.deviceLimitConfig = deviceLimitConfig;
        this.secureRandom = new SecureRandom();
    }

    // -------------------------------------------------------------------------
    // AC2 (E06S03) + AC1, AC2, AC6 (E07S02) — Register device
    // -------------------------------------------------------------------------

    /**
     * Registers a new SCORING_TABLET device — backward-compatible overload (E06S03 AC2, E07S02 AC6).
     *
     * <p>Delegates to {@link #registerDevice(UUID, UUID, String)} with {@code SCORING_TABLET}.
     * Called by controllers that do not supply a deviceType (pre-E07S02 callers).
     *
     * @param tenantId   the tenant ID (resolved from request context)
     * @param locationId the location ID (resolved from request context)
     * @return the newly registered scoring tablet
     */
    public Device registerDevice(UUID tenantId, UUID locationId) {
        return registerDevice(tenantId, locationId, Device.TYPE_SCORING_TABLET);
    }

    /**
     * Registers a new device of the specified type (E07S02 AC1).
     *
     * <p>SCORING_TABLET path: generates a device token + PIN; limit not applied.
     * DISPLAY path: generates a device token only (pin=null); checks the display device
     * limit ({@code vvwt.devices.max-display-count}) and throws
     * {@link TooManyRequestsException} (→ HTTP 429) if the limit is reached (AC2).
     *
     * @param tenantId   the tenant ID (resolved from request context)
     * @param locationId the location ID (resolved from request context)
     * @param deviceType the device type: {@code SCORING_TABLET} or {@code DISPLAY}
     * @return the newly registered device
     * @throws TooManyRequestsException if {@code DISPLAY} and limit is reached (E07S02 AC2)
     * @throws IllegalArgumentException if {@code deviceType} is not a recognised value
     * @throws IllegalStateException    if PIN generation is exhausted for SCORING_TABLET (AC10)
     */
    public Device registerDevice(UUID tenantId, UUID locationId, String deviceType) {
        if (Device.TYPE_DISPLAY.equals(deviceType)) {
            checkDisplayLimit(locationId);
        } else if (!Device.TYPE_SCORING_TABLET.equals(deviceType)) {
            throw new IllegalArgumentException(
                    "Unknown deviceType: '" + deviceType + "'. Valid values: SCORING_TABLET, DISPLAY");
        }

        String deviceToken = UUID.randomUUID().toString();
        String pin = Device.TYPE_SCORING_TABLET.equals(deviceType)
                ? generateUniquePinForTenant()
                : null;

        Device device = new Device(
                UUID.randomUUID(),
                tenantId,
                locationId,
                deviceToken,
                pin,
                deviceType,
                null,                  // assignedField: null (unassigned)
                Device.STATUS_REGISTERED,
                LocalDateTime.now(),   // registered_at: set explicitly (Spring Data JDBC passes null for DB-default cols)
                null,                  // last_seen_at: null until first heartbeat
                null,                  // deviceName: null at registration time
                null                   // configuration: null at registration time
        );

        Device saved = deviceRepository.save(device);
        log.info("[devices] Registered {} id={} pin={} tenant={}",
                deviceType, saved.getId(), pin, tenantId);
        return saved;
    }

    // -------------------------------------------------------------------------
    // E07S02 AC2 — Display device limit check
    // -------------------------------------------------------------------------

    /**
     * Checks the display device limit for the given location (E07S02 AC2).
     *
     * <p>Counts existing DISPLAY devices in the active tenant+location. If the count
     * equals or exceeds {@code vvwt.devices.max-display-count}, throws
     * {@link TooManyRequestsException} (→ HTTP 429).
     *
     * @param locationId the location UUID to check (active tenant scoped via repository)
     * @throws TooManyRequestsException if the limit has been reached
     */
    private void checkDisplayLimit(UUID locationId) {
        int maxCount = deviceLimitConfig.getMaxDisplayCount();
        long currentCount = deviceRepository.countByDeviceType(locationId, Device.TYPE_DISPLAY);
        if (currentCount >= maxCount) {
            throw new TooManyRequestsException(
                    "Display device limit reached. Current: " + currentCount + ", Max: " + maxCount,
                    currentCount,
                    maxCount);
        }
    }

    // -------------------------------------------------------------------------
    // E07S02 AC4 — Configure display device
    // -------------------------------------------------------------------------

    /**
     * Sets the device name and configuration for a DISPLAY device (E07S02 AC4).
     *
     * <p>Returns 400 if the device is not of type DISPLAY.
     *
     * @param deviceId    the device UUID
     * @param deviceName  the human-readable device name (must not be null)
     * @param configuration the JSON configuration string (null clears the configuration)
     * @return the updated device
     * @throws NoSuchElementException   if the device is not found for the active tenant (→ 404)
     * @throws IllegalArgumentException if the device is not of type DISPLAY (→ 400)
     */
    public Device configureDevice(UUID deviceId, String deviceName, String configuration) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new NoSuchElementException("Device not found: " + deviceId));

        if (!Device.TYPE_DISPLAY.equals(device.getDeviceType())) {
            throw new IllegalArgumentException(
                    "Configure is only allowed for DISPLAY devices. "
                    + "Device " + deviceId + " is of type: " + device.getDeviceType());
        }

        device.setDeviceName(deviceName);
        device.setConfiguration(configuration);
        Device saved = deviceRepository.save(device);
        log.info("[devices] Configured display device id={} name='{}' tenant={}",
                deviceId, deviceName, device.getTenantId());
        return saved;
    }

    // -------------------------------------------------------------------------
    // E07S02 AC5 — Delete device
    // -------------------------------------------------------------------------

    /**
     * Deletes a device by its ID (E07S02 AC5).
     *
     * <p>Works for both SCORING_TABLET and DISPLAY devices. The deletion is scoped to the
     * active tenant (DEC-5). Throws {@link NoSuchElementException} (→ 404) if not found.
     *
     * @param deviceId the device UUID to delete
     * @throws NoSuchElementException if the device is not found for the active tenant (→ 404)
     */
    public void deleteDevice(UUID deviceId) {
        boolean deleted = deviceRepository.deleteDevice(deviceId);
        if (!deleted) {
            throw new NoSuchElementException("Device not found: " + deviceId);
        }
        log.info("[devices] Deleted device id={}", deviceId);
    }

    // -------------------------------------------------------------------------
    // AC3 — Get device status by token
    // -------------------------------------------------------------------------

    /**
     * Returns the device matching the given token, scoped to the active tenant (AC3).
     *
     * @param deviceToken the opaque device token
     * @return the device
     * @throws NoSuchElementException if no device matches the token for the active tenant (→ 404)
     */
    public Device getDeviceByToken(String deviceToken) {
        return deviceRepository.findByDeviceToken(deviceToken)
                .orElseThrow(() -> new NoSuchElementException(
                        "Device not found for token: [redacted]"));
    }

    // -------------------------------------------------------------------------
    // AC4 — Find device by PIN
    // -------------------------------------------------------------------------

    /**
     * Returns the device matching the given PIN for the active tenant (AC4).
     *
     * @param pin the numeric PIN
     * @return the device
     * @throws NoSuchElementException if no device matches the PIN for the active tenant (→ 404)
     */
    public Device getDeviceByPin(String pin) {
        return deviceRepository.findByPin(pin)
                .orElseThrow(() -> new NoSuchElementException(
                        "No device found for PIN: " + pin));
    }

    // -------------------------------------------------------------------------
    // AC5 — Assign device to a field
    // -------------------------------------------------------------------------

    /**
     * Assigns a device to a court field (AC5).
     *
     * <p>Validates that:
     * <ul>
     *   <li>No other device is already assigned to the same field within the same tenant
     *       and location (→ 409 if conflict)</li>
     *   <li>The field number does not exceed the active tournament's field count
     *       (→ 400 if invalid). If no active tournament exists, field assignment is
     *       still accepted (graceful — may be assigned before tournament activation).</li>
     * </ul>
     *
     * @param deviceId    the device UUID
     * @param fieldNumber the court field number to assign
     * @return the updated device
     * @throws NoSuchElementException if the device is not found for the active tenant (→ 404)
     * @throws ConflictException      if another device is already assigned to the same field (→ 409)
     * @throws IllegalArgumentException if field number exceeds tournament capacity (→ 400)
     */
    public Device assignDevice(UUID deviceId, int fieldNumber) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Device not found: " + deviceId));

        // AC5 — field count validation against active tournament
        validateFieldNumber(device.getTenantId(), fieldNumber);

        // AC5 — conflict check: another device already assigned to this field
        Optional<Device> existing = deviceRepository.findByLocationAndField(
                device.getLocationId(), fieldNumber);
        if (existing.isPresent() && !existing.get().getId().equals(deviceId)) {
            throw new ConflictException(
                    "Field " + fieldNumber + " is already assigned to another device");
        }

        device.setAssignedField(fieldNumber);
        device.setStatus(Device.STATUS_ASSIGNED);
        Device saved = deviceRepository.save(device);
        log.info("[devices] Assigned device id={} to field={} tenant={}", deviceId, fieldNumber,
                device.getTenantId());
        return saved;
    }

    // -------------------------------------------------------------------------
    // AC6 — Unassign device
    // -------------------------------------------------------------------------

    /**
     * Clears the field assignment of a device, transitioning status back to REGISTERED (AC6).
     *
     * <p>Idempotent: unassigning an already-unassigned device returns normally without error.
     *
     * @param deviceId the device UUID
     * @return the updated device
     * @throws NoSuchElementException if the device is not found for the active tenant (→ 404)
     */
    public Device unassignDevice(UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Device not found: " + deviceId));

        device.setAssignedField(null);
        device.setStatus(Device.STATUS_REGISTERED);
        Device saved = deviceRepository.save(device);
        log.info("[devices] Unassigned device id={} tenant={}", deviceId, device.getTenantId());
        return saved;
    }

    // -------------------------------------------------------------------------
    // AC8 — Device token validation
    // -------------------------------------------------------------------------

    /**
     * Validates that the given device token exists and belongs to the active tenant (AC8).
     *
     * <p>Returns the device if valid. Throws {@link UnauthorizedException} if the token
     * is not found or belongs to a different tenant, to avoid oracle attacks (401, not 404).
     *
     * @param deviceToken the token to validate
     * @return the device
     * @throws UnauthorizedException if the token is invalid or belongs to another tenant
     */
    public Device validateDeviceToken(String deviceToken) {
        return deviceRepository.findByDeviceToken(deviceToken)
                .orElseThrow(() -> new UnauthorizedException(
                        "Invalid or expired device token"));
    }

    // -------------------------------------------------------------------------
    // PIN generation helpers (AC7, AC10, AC11)
    // -------------------------------------------------------------------------

    /**
     * Generates a PIN that is unique within the active tenant (AC7).
     *
     * <p>Starts at {@value #PIN_DIGITS_MIN} digits. If the space is exhausted (or too
     * collisions are encountered), escalates to 5, then 6 digits. Throws
     * {@link IllegalStateException} if all digit lengths are exhausted (AC10).
     *
     * @return a unique PIN string
     * @throws IllegalStateException if all PIN spaces are exhausted (AC10)
     */
    private String generateUniquePinForTenant() {
        for (int digits = PIN_DIGITS_MIN; digits <= PIN_DIGITS_MAX; digits++) {
            for (int attempt = 0; attempt < MAX_PIN_ATTEMPTS; attempt++) {
                String candidate = generateRandomPin(digits);
                if (!isConfusable(candidate) && !deviceRepository.isPinTaken(candidate)) {
                    return candidate;
                }
            }
            log.warn("[devices] PIN space at {} digits exhausted after {} attempts, escalating",
                    digits, MAX_PIN_ATTEMPTS);
        }
        throw new IllegalStateException(
                "PIN generation exhausted at all digit lengths (4–6). "
                + "Too many devices registered for this tenant.");
    }

    /**
     * Generates a random numeric PIN with the given number of digits (AC11).
     *
     * <p>Uses {@link SecureRandom} to ensure the PIN is not guessable as an enumeration
     * attack vector, even though the PIN itself is not a security token.
     *
     * @param digits number of digits (4, 5, or 6)
     * @return a zero-padded numeric string of the specified length
     */
    private String generateRandomPin(int digits) {
        int min = (int) Math.pow(10, digits - 1);
        int max = (int) Math.pow(10, digits) - 1;
        int value = min + secureRandom.nextInt(max - min + 1);
        return String.format("%0" + digits + "d", value);
    }

    /**
     * Returns {@code true} if the PIN is a trivially confusable sequence (AC7).
     *
     * <p>Excluded patterns:
     * <ul>
     *   <li>All-same-digit: 0000, 1111, ..., 9999 (for 4-digit length)</li>
     *   <li>Sequential ascending: 0123, 1234, 2345, ..., 6789 (for 4-digit)</li>
     *   <li>Sequential descending: 9876, 8765, ... (for 4-digit)</li>
     * </ul>
     * For 5- and 6-digit PINs, only all-same-digit PINs are excluded (sequences are
     * uncommon enough not to need filtering at longer lengths).
     *
     * @param pin the PIN candidate
     * @return {@code true} if the PIN should be skipped
     */
    static boolean isConfusable(String pin) {
        if (pin == null || pin.isEmpty()) return true;

        // All-same-digit check (any length)
        char first = pin.charAt(0);
        boolean allSame = true;
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) != first) {
                allSame = false;
                break;
            }
        }
        if (allSame) return true;

        // Sequential checks for 4-digit PINs only
        if (pin.length() == 4 && CONFUSABLE_PINS_4.contains(pin)) return true;

        return false;
    }

    /**
     * Validates field number against the active tournament's field count (AC5).
     *
     * <p>If no active tournament exists for the tenant, field validation is skipped
     * (assignment before tournament activation is allowed).
     *
     * @param tenantId    the device's tenant ID
     * @param fieldNumber the proposed field number
     * @throws IllegalArgumentException if field number exceeds tournament capacity
     */
    private void validateFieldNumber(UUID tenantId, int fieldNumber) {
        if (fieldNumber < 1) {
            throw new IllegalArgumentException("Field number must be >= 1, got: " + fieldNumber);
        }
        // Find the active tournament for this tenant and validate field count
        tournamentRepository.findAll().stream()
                .filter(t -> "ACTIVE".equals(t.getStatus()))
                .findFirst()
                .ifPresent(tournament -> {
                    if (fieldNumber > tournament.getFieldCount()) {
                        throw new IllegalArgumentException(
                                "Field number " + fieldNumber + " exceeds tournament capacity "
                                + tournament.getFieldCount());
                    }
                });
    }

    /**
     * Builds the set of 4-digit confusable PINs: sequential ascending and descending (AC7).
     *
     * @return immutable set of confusable 4-digit PIN strings
     */
    private static Set<String> buildConfusable4DigitSet() {
        Set<String> set = new java.util.HashSet<>();
        // Sequential ascending: 0123, 1234, 2345, 3456, 4567, 5678, 6789
        for (int start = 0; start <= 6; start++) {
            set.add(String.format("%d%d%d%d", start, start + 1, start + 2, start + 3));
        }
        // Sequential descending: 9876, 8765, 7654, 6543, 5432, 4321, 3210
        for (int start = 9; start >= 3; start--) {
            set.add(String.format("%d%d%d%d", start, start - 1, start - 2, start - 3));
        }
        return java.util.Collections.unmodifiableSet(set);
    }
}
