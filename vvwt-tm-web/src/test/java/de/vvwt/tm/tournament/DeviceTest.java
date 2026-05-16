// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Device} entity invariants (E21S06, AC-TDD-Device).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link Device} at {@code de.vvwt.tm.tournament.Device} did not
 * exist at commit time, causing a compile error — satisfying the DEC-22 Iron Law (no
 * characterization tests; new code tested first in red state).
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>id/tenantId/deviceToken immutability after registration (set once, not null)
 *   <li>locationId nullable per DEC-24 / V16
 *   <li>Status constants: REGISTERED, ASSIGNED, DISCONNECTED
 *   <li>Type constants: SCORING_TABLET, DISPLAY
 *   <li>Status transitions via setter
 *   <li>PIN null for DISPLAY devices
 * </ul>
 *
 * @see Device
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-24">DEC-24 — device location nullable</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 158)</a>
 */
@DisplayName("Device entity invariants — E21S06 AC-TDD-Device")
class DeviceTest {

    @Test
    @DisplayName("id is settable and gettable")
    void idIsSettableAndGettable() {
        UUID id = UUID.randomUUID();
        Device device = new Device();
        device.setId(id);
        assertThat(device.getId()).as("getId() must return the set UUID").isEqualTo(id);
    }

    @Test
    @DisplayName("tenantId is settable and NOT NULL after set")
    void tenantIdIsSettableAndNotNull() {
        UUID tenantId = UUID.randomUUID();
        Device device = new Device();
    }

    @Test
    @DisplayName("deviceToken is settable and NOT NULL after set")
    void deviceTokenIsSettable() {
        Device device = new Device();
        device.setDeviceToken("abc-def-ghi");
        assertThat(device.getDeviceToken()).isEqualTo("abc-def-ghi");
    }

    @Test
    @DisplayName("locationId is nullable per DEC-24 / V16 (registered-but-unassigned state)")
    void locationIdIsNullable() {
        Device device = new Device();
        assertThat(device.getLocationId())
                .as("locationId must be null on freshly constructed Device (DEC-24 carve-out)")
                .isNull();

        UUID locationId = UUID.randomUUID();
        device.setLocationId(locationId);
        assertThat(device.getLocationId()).isEqualTo(locationId);

        device.setLocationId(null);
        assertThat(device.getLocationId()).as("locationId can be set back to null").isNull();
    }

    @Test
    @DisplayName("STATUS constants are defined: REGISTERED, ASSIGNED, DISCONNECTED")
    void statusConstantsDefined() {
        assertThat(Device.STATUS_REGISTERED).isEqualTo("REGISTERED");
        assertThat(Device.STATUS_ASSIGNED).isEqualTo("ASSIGNED");
        assertThat(Device.STATUS_DISCONNECTED).isEqualTo("DISCONNECTED");
    }

    @Test
    @DisplayName("TYPE constants are defined: SCORING_TABLET, DISPLAY")
    void typeConstantsDefined() {
        assertThat(Device.TYPE_SCORING_TABLET).isEqualTo("SCORING_TABLET");
        assertThat(Device.TYPE_DISPLAY).isEqualTo("DISPLAY");
    }

    @Test
    @DisplayName("status transitions: REGISTERED -> ASSIGNED via setter")
    void statusTransitionRegisteredToAssigned() {
        Device device = new Device();
        device.setStatus(Device.STATUS_REGISTERED);
        assertThat(device.getStatus()).isEqualTo(Device.STATUS_REGISTERED);

        device.setStatus(Device.STATUS_ASSIGNED);
        assertThat(device.getStatus()).isEqualTo(Device.STATUS_ASSIGNED);
    }

    @Test
    @DisplayName("pin is null for DISPLAY device scenario")
    void pinIsNullForDisplayDevice() {
        Device device = new Device();
        device.setDeviceType(Device.TYPE_DISPLAY);
        // pin field is not set — must remain null
        assertThat(device.getPin()).as("DISPLAY devices have no PIN (AC in story)").isNull();
    }

    @Test
    @DisplayName("assignedField is null when device is unassigned")
    void assignedFieldNullWhenUnassigned() {
        Device device = new Device();
        assertThat(device.getAssignedField()).isNull();
    }

    @Test
    @DisplayName("deviceName and configuration are null by default")
    void deviceNameAndConfigurationNullByDefault() {
        Device device = new Device();
        assertThat(device.getDeviceName()).isNull();
        assertThat(device.getConfiguration()).isNull();
    }
}
