// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
import de.vvwt.tm.tournament.exceptions.DeviceLimitExceededException;
import de.vvwt.tm.tournament.exceptions.DevicePinLockedException;
import de.vvwt.tm.tournament.exceptions.PinMismatchException;
import de.vvwt.tm.tournament.exceptions.PinMissingForTabletException;
import de.vvwt.tm.tournament.exceptions.RenameNotSupportedForDisplayException;
import de.vvwt.tm.tournament.exceptions.UnexpectedPinForDisplayException;
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
    @DisplayName(
            "register SCORING_TABLET: persists device with token + PIN + deviceName, returns saved"
                    + " entity")
    void register_scoringTablet_persistsWithTokenAndPin() {
        when(deviceRepository.countByTenant()).thenReturn(0L);
        when(deviceRepository.isPinTaken(any())).thenReturn(false);
        when(deviceRepository.isNameTaken(any())).thenReturn(false);
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
        assertThat(result.getDeviceName())
                .as("SCORING_TABLET must receive a unique device name at registration (E49S01 AC1)")
                .isNotBlank()
                .startsWith("Tablet-");
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
        when(deviceRepository.isNameTaken(any())).thenReturn(false);
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
        when(deviceRepository.isNameTaken(any())).thenReturn(false);
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

    // =========================================================================
    // E49S01 — assignDevice with PIN (AC5/AC6)
    // =========================================================================

    @Test
    @DisplayName(
            "E49S01 AC5: assignDevice SCORING_TABLET with correct PIN succeeds and resets"
                    + " fail-counter")
    void e49s01_assignDevice_scoringTablet_correctPin_succeeds() {
        UUID deviceId = UUID.randomUUID();
        Device device = scoringTablet(deviceId, "4567");
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(deviceRepository.getPinFailCount(deviceId)).thenReturn(0);
        when(deviceRepository.findByLocationAndField(null, 2)).thenReturn(Optional.empty());
        when(deviceRepository.save(device)).thenReturn(device);

        Device result = service.assignDevice(deviceId, 2, "4567");

        assertThat(result.getAssignedField()).isEqualTo(2);
        assertThat(result.getStatus()).isEqualTo(Device.STATUS_ASSIGNED);
        verify(deviceRepository).resetPinFailCount(deviceId);
    }

    @Test
    @DisplayName(
            "E49S01 AC5: assignDevice SCORING_TABLET without PIN throws"
                    + " PinMissingForTabletException")
    void e49s01_assignDevice_scoringTablet_nullPin_throws() {
        UUID deviceId = UUID.randomUUID();
        Device device = scoringTablet(deviceId, "4567");
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.assignDevice(deviceId, 1, null))
                .isInstanceOf(PinMissingForTabletException.class);
    }

    @Test
    @DisplayName(
            "E49S01 AC5: assignDevice SCORING_TABLET with wrong PIN throws PinMismatchException and"
                    + " increments counter")
    void e49s01_assignDevice_scoringTablet_wrongPin_incrementsCounterAndThrows() {
        UUID deviceId = UUID.randomUUID();
        Device device = scoringTablet(deviceId, "4567");
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(deviceRepository.getPinFailCount(deviceId)).thenReturn(0);

        assertThatThrownBy(() -> service.assignDevice(deviceId, 1, "9999"))
                .isInstanceOf(PinMismatchException.class);
        verify(deviceRepository).incrementPinFailCount(deviceId);
    }

    @Test
    @DisplayName(
            "E49S01 AC6: assignDevice SCORING_TABLET when locked throws DevicePinLockedException")
    void e49s01_assignDevice_scoringTablet_locked_throws() {
        UUID deviceId = UUID.randomUUID();
        Device device = scoringTablet(deviceId, "4567");
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(deviceRepository.getPinFailCount(deviceId)).thenReturn(10);

        assertThatThrownBy(() -> service.assignDevice(deviceId, 1, "4567"))
                .isInstanceOf(DevicePinLockedException.class);
    }

    @Test
    @DisplayName("E49S01 AC5: assignDevice DISPLAY with null PIN succeeds")
    void e49s01_assignDevice_display_nullPin_succeeds() {
        UUID deviceId = UUID.randomUUID();
        Device device = new Device();
        device.setId(deviceId);
        device.setDeviceType(Device.TYPE_DISPLAY);
        device.setStatus(Device.STATUS_REGISTERED);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(deviceRepository.findByLocationAndField(null, 1)).thenReturn(Optional.empty());
        when(deviceRepository.save(device)).thenReturn(device);

        Device result = service.assignDevice(deviceId, 1, null);

        assertThat(result.getAssignedField()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "E49S01 AC5: assignDevice DISPLAY with non-null PIN throws"
                    + " UnexpectedPinForDisplayException")
    void e49s01_assignDevice_display_withPin_throws() {
        UUID deviceId = UUID.randomUUID();
        Device device = new Device();
        device.setId(deviceId);
        device.setDeviceType(Device.TYPE_DISPLAY);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.assignDevice(deviceId, 1, "1234"))
                .isInstanceOf(UnexpectedPinForDisplayException.class);
    }

    @Test
    @DisplayName("E49S01 AC11: renameDevice SCORING_TABLET updates deviceName")
    void e49s01_renameDevice_scoringTablet_updatesName() {
        UUID deviceId = UUID.randomUUID();
        Device device = scoringTablet(deviceId, "1234");
        device.setDeviceName("Tablet-OLD1");
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(deviceRepository.save(device)).thenReturn(device);

        Device result = service.renameDevice(deviceId, "Tablet-NEW1");

        assertThat(result.getDeviceName()).isEqualTo("Tablet-NEW1");
    }

    @Test
    @DisplayName("E49S01 AC11: renameDevice DISPLAY throws RenameNotSupportedForDisplayException")
    void e49s01_renameDevice_display_throws() {
        UUID deviceId = UUID.randomUUID();
        Device device = new Device();
        device.setId(deviceId);
        device.setDeviceType(Device.TYPE_DISPLAY);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.renameDevice(deviceId, "Display-Name"))
                .isInstanceOf(RenameNotSupportedForDisplayException.class);
    }

    @Test
    @DisplayName("E49S01 AC6: resetPinLockCounter delegates to repository")
    void e49s01_resetPinLockCounter_delegatesToRepository() {
        UUID deviceId = UUID.randomUUID();
        Device device = scoringTablet(deviceId, "1234");
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        service.resetPinLockCounter(deviceId);

        verify(deviceRepository).resetPinFailCount(deviceId);
    }

    @Test
    @DisplayName("E49S01 AC1: generateUniqueDeviceName returns Tablet-XXXX format name")
    void e49s01_generateUniqueDeviceName_returnsTabbletFormat() {
        when(deviceRepository.isNameTaken(any())).thenReturn(false);

        String name = service.generateUniqueDeviceName();

        assertThat(name)
                .as("Device name must match Tablet-XXXX format")
                .matches("Tablet-[A-Z2-9]{4}");
    }

    // =========================================================================
    // AC-TEST-DEVICE-FIELD-IS-1-BASED-IT-RED (E53S09 / DEC-60 D-7)
    //
    // Device.assignedField is 1-based by operator-input convention (Devices.svelte min="1").
    // This regression guard verifies assignDevice(deviceId, N, null) stores assignedField=N
    // unchanged — no offset/conversion. D-7 of DEC-60 preserves this invariant.
    // =========================================================================

    /**
     * AC-TEST-DEVICE-FIELD-IS-1-BASED-IT-RED (DEC-60 D-7 / E53S09):
     *
     * <p>Operator assigns device to fieldNumber=1 (1-based, from "Feldnummer" min=1). assignDevice
     * must store assignedField=1 unchanged — no decrement to 0-based. Regression guard for DEC-60
     * D-7: Device.assignedField stays 1-based independent of the L2/L3 fieldNumber migration.
     */
    @Test
    @DisplayName(
            "AC-TEST-DEVICE-FIELD-IS-1-BASED-IT-RED: assignDevice stores 1-based fieldNumber"
                    + " unchanged (DEC-60 D-7 / E53S09)")
    void assignDevice_fieldNumber1_storesAsOneBased_noDecrement() {
        UUID deviceId = UUID.randomUUID();
        Device device = new Device();
        device.setId(deviceId);
        device.setDeviceType(Device.TYPE_DISPLAY);
        device.setStatus(Device.STATUS_REGISTERED);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(deviceRepository.findByLocationAndField(any(), anyInt())).thenReturn(Optional.empty());
        when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Device result = service.assignDevice(deviceId, 1 /* fieldNumber 1-based */, null);

        assertThat(result.getAssignedField())
                .as(
                        "AC-TEST-DEVICE-FIELD-IS-1-BASED-IT-RED: assignedField must store the"
                                + " operator-input value unchanged (1-based per DEC-60 D-7 /"
                                + " E53S09) — no decrement to 0-based")
                .isEqualTo(1);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Device scoringTablet(UUID id, String pin) {
        Device d = new Device();
        d.setId(id);
        d.setDeviceType(Device.TYPE_SCORING_TABLET);
        d.setPin(pin);
        d.setStatus(Device.STATUS_REGISTERED);
        return d;
    }
}
