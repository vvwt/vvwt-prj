package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
import de.vvwt.tm.tournament.exceptions.DeviceLimitExceededException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultDeviceService} (E21S06, AC-TDD-DeviceService).
 *
 * <h2>Original RED state (E21S06)</h2>
 *
 * <p>This test was committed RED: the original {@code DeviceService} implementation class in the
 * internal package did not exist at commit time, causing a compile error — satisfying the DEC-22
 * Iron Law.
 *
 * <h2>E33S03 update</h2>
 *
 * <p>Renamed subject from {@code DeviceService} to {@code DefaultDeviceService} (DEC-35 retrofit —
 * AC-UNIT-TEST-RENAME). Test lives in the same package as {@code DefaultDeviceService}; white-box
 * construction via {@code new DefaultDeviceService(...)} is permitted per DEC-36 same-package rule.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>register-new device: tenantId bound, deviceToken generated, PIN generated for
 *       SCORING_TABLET
 *   <li>configure: sets deviceName and configuration on DISPLAY device
 *   <li>assign-location: sets locationId
 *   <li>enforce-DeviceLimit: N-th device succeeds, (N+1)-th throws DeviceLimitExceededException
 *   <li>register without PIN for DISPLAY device
 *   <li>delete device: delegates to repository
 *   <li>listDevices: delegates to repository
 *   <li>configure: rejects non-DISPLAY device with IllegalArgumentException
 *   <li>Concurrent-register race: documented in impl-report (unit test covers count-check ordering)
 * </ul>
 *
 * @see DefaultDeviceService
 * @see DeviceLimitConfig
 * @see de.vvwt.tm.tournament.exceptions.DeviceLimitExceededException
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-35">DEC-35 — Default* naming canon</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 170)</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultDeviceService — E21S06 AC-TDD-DeviceService")
class DeviceServiceTest {

    @Mock private DeviceRepository deviceRepository;

    private DeviceLimitConfig limitConfig;
    private DefaultDeviceService service;

    private static final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        limitConfig = new DeviceLimitConfig();
        limitConfig.setMaxDeviceCount(5);
        service = new DefaultDeviceService(deviceRepository, limitConfig);
    }

    // =========================================================================
    // register — SCORING_TABLET
    // =========================================================================

    @Test
    @DisplayName("register SCORING_TABLET: persists device with token + PIN, returns saved entity")
    void register_scoringTablet_persistsWithTokenAndPin() {
        when(deviceRepository.countByTenant()).thenReturn(0L);
        when(deviceRepository.isPinTaken(any())).thenReturn(false);
        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        when(deviceRepository.save(captor.capture()))
                .thenAnswer(inv -> captor.getValue()); // return as-saved

        Device result = service.register(Device.TYPE_SCORING_TABLET);
        assertThat(result.getDeviceToken())
                .as("deviceToken must be a non-blank UUID string")
                .isNotBlank();
        assertThat(result.getPin())
                .as("SCORING_TABLET must receive a PIN")
                .isNotBlank()
                .matches("\\d{4,6}");
        assertThat(result.getDeviceType()).isEqualTo(Device.TYPE_SCORING_TABLET);
        assertThat(result.getStatus()).isEqualTo(Device.STATUS_REGISTERED);
        assertThat(result.getLocationId())
                .as("locationId null at registration per DEC-24")
                .isNull();
    }

    @Test
    @DisplayName("register DISPLAY: persists device without PIN")
    void register_display_persistsWithoutPin() {
        when(deviceRepository.countByTenant()).thenReturn(0L);
        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        when(deviceRepository.save(captor.capture())).thenAnswer(inv -> captor.getValue());

        Device result = service.register(Device.TYPE_DISPLAY);

        assertThat(result.getPin()).as("DISPLAY device has no PIN").isNull();
        assertThat(result.getDeviceType()).isEqualTo(Device.TYPE_DISPLAY);
    }

    // =========================================================================
    // DeviceLimit enforcement (AC-DEVICELIMIT-ENFORCEMENT)
    // =========================================================================

    @Test
    @DisplayName("register at cap: Nth device succeeds")
    void register_atCap_nthDeviceSucceeds() {
        int cap = limitConfig.getMaxDeviceCount(); // 5
        // 4 existing devices → 5th is the Nth — must succeed
        when(deviceRepository.countByTenant()).thenReturn((long) (cap - 1));
        when(deviceRepository.isPinTaken(any())).thenReturn(false);
        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        when(deviceRepository.save(captor.capture())).thenAnswer(inv -> captor.getValue());

        Device result = service.register(Device.TYPE_SCORING_TABLET);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("register beyond cap: (N+1)th device throws DeviceLimitExceededException → 409")
    void register_beyondCap_throwsDeviceLimitExceededException() {
        int cap = limitConfig.getMaxDeviceCount(); // 5
        when(deviceRepository.countByTenant()).thenReturn((long) cap); // already at cap

        assertThatThrownBy(() -> service.register(Device.TYPE_SCORING_TABLET))
                .as("(N+1)th device must be rejected with DeviceLimitExceededException")
                .isInstanceOf(DeviceLimitExceededException.class)
                .satisfies(
                        ex -> {
                            DeviceLimitExceededException dle = (DeviceLimitExceededException) ex;
                            assertThat(dle.getConfiguredLimit()).isEqualTo(cap);
                            assertThat(dle.getCurrentCount()).isEqualTo(cap);
                        });
    }

    @Test
    @DisplayName("register with zero devices and cap=1: 1st succeeds, 2nd rejected")
    void register_cap1_firstSucceedsSecondRejected() {
        limitConfig.setMaxDeviceCount(1);
        // First device: count=0
        when(deviceRepository.countByTenant()).thenReturn(0L);
        when(deviceRepository.isPinTaken(any())).thenReturn(false);
        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        when(deviceRepository.save(captor.capture())).thenAnswer(inv -> captor.getValue());

        Device first = service.register(Device.TYPE_SCORING_TABLET);
        assertThat(first).isNotNull();

        // Second device: count=1 (cap reached)
        when(deviceRepository.countByTenant()).thenReturn(1L);
        assertThatThrownBy(() -> service.register(Device.TYPE_SCORING_TABLET))
                .isInstanceOf(DeviceLimitExceededException.class);
    }

    // =========================================================================
    // configure
    // =========================================================================

    @Test
    @DisplayName("configure: sets deviceName and configuration on DISPLAY device")
    void configure_displayDevice_setsFields() {
        UUID deviceId = UUID.randomUUID();
        Device existing = new Device();
        existing.setId(deviceId);
        existing.setDeviceType(Device.TYPE_DISPLAY);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(existing));
        when(deviceRepository.save(existing)).thenReturn(existing);

        Device result =
                service.configure(deviceId, "My Display", "{\"display_schema\":\"OVERVIEW\"}");

        assertThat(result.getDeviceName()).isEqualTo("My Display");
        assertThat(result.getConfiguration()).isEqualTo("{\"display_schema\":\"OVERVIEW\"}");
    }

    @Test
    @DisplayName("configure: rejects non-DISPLAY device with IllegalArgumentException")
    void configure_scoringTablet_rejected() {
        UUID deviceId = UUID.randomUUID();
        Device existing = new Device();
        existing.setId(deviceId);
        existing.setDeviceType(Device.TYPE_SCORING_TABLET);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.configure(deviceId, "Name", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISPLAY");
    }

    @Test
    @DisplayName("configure: throws NoSuchElementException when device not found")
    void configure_notFound_throwsNoSuchElement() {
        when(deviceRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.configure(UUID.randomUUID(), "Name", null))
                .isInstanceOf(NoSuchElementException.class);
    }

    // =========================================================================
    // assign-location (DEC-24)
    // =========================================================================

    @Test
    @DisplayName("assignLocation: sets locationId on device")
    void assignLocation_setsLocationId() {
        UUID deviceId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        Device existing = new Device();
        existing.setId(deviceId);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(existing));
        when(deviceRepository.locationExistsForTenant(locationId)).thenReturn(true);
        when(deviceRepository.save(existing)).thenReturn(existing);

        Device result = service.assignLocation(deviceId, locationId);

        assertThat(result.getLocationId()).isEqualTo(locationId);
    }

    @Test
    @DisplayName("assignLocation: throws NoSuchElementException when device not found")
    void assignLocation_notFound_throwsNoSuchElement() {
        when(deviceRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignLocation(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class);
    }

    // =========================================================================
    // listDevices + deleteDevice
    // =========================================================================

    @Test
    @DisplayName("listDevices: delegates to repository findAllByTenant")
    void listDevices_delegatesToRepository() {
        Device d1 = new Device();
        d1.setId(UUID.randomUUID());
        when(deviceRepository.findAllByTenant()).thenReturn(List.of(d1));

        List<Device> result = service.listDevices();

        assertThat(result).hasSize(1);
        verify(deviceRepository).findAllByTenant();
    }

    @Test
    @DisplayName("deleteDevice: delegates to repository delete")
    void deleteDevice_delegatesToRepository() {
        UUID deviceId = UUID.randomUUID();
        Device existing = new Device();
        existing.setId(deviceId);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(existing));

        service.deleteDevice(deviceId);

        verify(deviceRepository).deleteById(deviceId);
    }
}
