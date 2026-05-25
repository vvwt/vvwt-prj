<!-- SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# pi-display — Raspberry Pi Kiosk Display Provisioning

This module provides a repeatable build process for a FullPageOS-based kiosk
image that boots a Raspberry Pi directly into Chromium fullscreen at the TM
Display registration URL (`/display/register`).

This runbook walks an operator from a freshly-cloned `vvwt-prj` checkout to a
Raspberry Pi 4 mounted on a venue monitor showing the TM Display registration
page. Each section covers one deployment phase; follow them in order on first
setup.

## Module Contents

| File | Purpose |
|------|---------|
| `build-image.sh` | FullPageOS 0.14.0 overlay wrapper (see `--help`) |
| `src/test/sh/build-image.bats` | Bats test suite for `build-image.sh` (E69S01) |
| `src/test/sh/readme.bats` | Bats content-grep tests for this runbook (E69S02) |
| `pom.xml` | Maven module (packaging=pom; no Java; shellcheck+bats in integration-test phase) |

---

## Build

### Prerequisites

Before building an image you need a Linux host (or macOS with Docker) with:

- **Docker** — FullPageOS uses Docker internally for the build environment.
- **`qemu-user-static`** — installed on the Docker host for ARM emulation.
  On Debian/Ubuntu: `sudo apt-get install qemu-user-static`.
- A working internet connection — the build fetches the FullPageOS 0.14.0 release
  tarball and installs packages inside the image.
- `git` — to clone `vvwt-prj`.

The upstream FullPageOS README at
<https://github.com/guysoft/FullPageOS> documents the exact Docker build
procedure in detail; consult it if the build environment needs further tuning.

### Invocation

From the `vvwt-prj/pi-display/` directory:

```bash
# Recommended: full image build with kiosk URL pre-baked
./build-image.sh --server-url=https://your-tm-host/

# Dry-run: exercise overlay logic without fetching FullPageOS or building an image
./build-image.sh --dry-run --server-url=https://your-tm-host/

# Show all options
./build-image.sh --help
```

The `--server-url` argument bakes the TM Display URL into `/boot/fullpageos.txt`
inside the image so Chromium opens it automatically on first boot.

### Expected output

A successful build places a `.img` file under the `build/` subdirectory created
by the FullPageOS build system (typically
`build/image_YYYY-MM-DD-fullpageos.img` or similar). The exact path is printed
at the end of a successful build.

Approximate image size: ~2-3 GB (uncompressed). A 16 GB SD card provides ample
headroom.

### SHA-256 verification

To verify your image after download or copy:

```bash
sha256sum build/image_*.img
```

Record the hash and compare it on the target machine or after any copy step to
confirm integrity.

---

## Flash

### Recommended tool

Use [Raspberry Pi Imager](https://www.raspberrypi.com/software/) (Apache-2.0,
AGPL-3.0-forward-compatible per the FSF license compatibility matrix). It
handles SD-card writing reliably across operating systems.

### WiFi credential paths

You must choose **one** of the following two paths at flash time.

#### Path 1 — Raspberry Pi Imager headless-config (recommended)

Raspberry Pi Imager supports "Advanced options" (gear icon) that inject WiFi
credentials into the **rootfs** at flash time. The credentials are stored on the
`ext4` root partition, not on the FAT32 `/boot` partition.

**Security posture:** the rootfs partition requires Linux to mount; a Windows or
macOS machine that picks up the SD card cannot read the credentials without extra
tooling. This is meaningfully more secure than Path 2.

Steps in Raspberry Pi Imager:
1. Select the `.img` file you built.
2. Select the target SD card.
3. Click the gear icon to open "Advanced options".
4. Enable **Configure wireless LAN**.
5. Set:
   - **SSID** — the venue WiFi network name.
   - **Password** — the venue WiFi passphrase.
   - **Wireless LAN country** — your two-letter ISO country code (e.g., `DE`).
6. Optionally enable **Set hostname** and **Enable SSH** for operator access.
7. Click **Write**.

**Never commit WiFi credentials to the repository.**

#### Path 2 — `wpa_supplicant.conf` on the FAT32 partition

After flashing the SD card with any tool, mount the FAT32 `/boot` partition and
create the file `/boot/firmware/wpa_supplicant.conf` with the following content
(substitute your values for the placeholders):

```
ctrl_interface=DIR=/var/run/wpa_supplicant GROUP=netdev
update_config=1
country=COUNTRY_CODE

network={
    ssid="YOUR_WIFI_SSID"
    psk="YOUR_WIFI_PASSPHRASE"
    key_mgmt=WPA-PSK
}
```

Replace `COUNTRY_CODE` with your two-letter ISO country code (e.g., `DE`),
`YOUR_WIFI_SSID` with the network name, and `YOUR_WIFI_PASSPHRASE` with the
passphrase.

**Note on line endings:** both LF (Linux/macOS) and CRLF (Windows Notepad)
line endings work correctly for `wpa_supplicant.conf` on the FAT32 partition.

**Security posture:** `wpa_supplicant.conf` lives on the FAT32 partition, which
is **plaintext-readable by any computer** that inserts the SD card — Windows,
macOS, and Linux all auto-mount FAT32. Anyone who handles the SD card can read
the WiFi passphrase. Trust assumption: **operator-supervised physical security**.
If the SD card leaves the operator's custody, the passphrase is exposed.
Path 1 is preferred when the imager headless-config is available.

**Never commit this file to the repository.**

---

## Boot & Verify

Insert the prepared SD card into the Raspberry Pi 4, connect it to a monitor via
HDMI, and power it on. The expected first-boot sequence is:

1. **WiFi join (~30 s):** The Pi joins the venue WiFi network. Wait up to 60 s
   on first boot if the network is congested.

2. **Chromium launches fullscreen:** FullPageOS boots directly into Chromium in
   kiosk mode. The browser opens the URL stored in `/boot/fullpageos.txt` —
   the value you supplied via `--server-url` during the build.

3. **TM Display registration page:** If the URL resolves to your TM server's
   `/display/register` route, the browser shows the **DisplayRegisterPage**
   with a registration token. The page should be visible within 30-60 s of
   power-on once WiFi is established.

4. **Operator: accept pending display in TM admin:** Open the TM admin interface
   in a browser on your laptop/phone, navigate to **Devices**, and filter by
   display devices. The newly-booted Pi appears as a pending device.

5. **Configure the device:** Follow the existing E07 admin flow to assign the
   display to a tournament and location. Once accepted, the Pi reloads and shows
   the live display view for its assigned event.

**Pass/fail criteria at each step:**

| Step | Pass | Fail |
|------|------|------|
| 1 | WiFi LED solid / network reachable | No LED / Chromium shows "No internet" after 90 s |
| 2 | Chromium opens fullscreen with the correct URL | Desktop visible / wrong URL |
| 3 | Registration token visible on screen | "Cannot reach server" / blank page |
| 4 | Device listed under Devices (pending) | Device absent after 5 min |
| 5 | Display shows live event view | Error page / spinner indefinitely |

If any step fails, check: Is the URL in `/boot/fullpageos.txt` correct? Is the
TM server reachable from the venue network? Is the SD card fully written?

---

## Watchdog Attestation

Perform these three scenarios on a **freshly-provisioned Pi** before the
tournament begins. Record the actual outcome next to the expected outcome. If
any scenario shows a gap, **stop and open a new story** per Brief D-7-b —
do not author systemd drop-ins or any other inline fix in this runbook.

### Scenario W-1 — Process restart (`pkill chromium-browser`)

**Preparation:** SSH into the Pi (`ssh pi@<pi-ip>`).

**Action:** Run:

```bash
pkill chromium-browser
```

**Expected outcome at FullPageOS 0.14.0:** systemd detects the process exit
and respawns Chromium within approximately 5 seconds. The display briefly goes
black, then the kiosk page reloads.

**Actual outcome (fill in):** ___________________________________________

**Escalation:** If Chromium does not respawn within 30 s, **stop and open a
new story per Brief D-7-b** describing the observed gap. Do not author a systemd
drop-in or any other inline fix here.

---

### Scenario W-2 — Network disconnect

**Preparation:** Identify the Pi's active network interface.

**Action:** Unplug the network cable (or block the Pi's WiFi at the router).

**Expected outcome at FullPageOS 0.14.0:** FullPageOS displays its upstream
splash page or offline fallback within approximately 30 seconds of losing network
connectivity. The display does not show a blank screen.

**Actual outcome (fill in):** ___________________________________________

**Escalation:** If no visible splash appears within 60 s, or the screen goes
blank, **stop and open a new story per Brief D-7-b**. Do not author an HTML
fallback page or any other inline fix here.

---

### Scenario W-3 — TM server 5xx

**Preparation:** Configure a test TM instance (or temporarily break the server)
so that the kiosk URL returns HTTP 5xx.

**Action:** Point the kiosk URL at the error-returning server (edit
`/boot/fullpageos.txt` and reboot, or SSH into the Pi and update the kiosk
arguments directly).

**Expected outcome at FullPageOS 0.14.0:** Chromium shows the error page it
renders for a 5xx response (a browser error page or the server's own error page
if it returns HTML). The display does not hang indefinitely on a spinner.

**Actual outcome (fill in):** ___________________________________________

**Escalation:** If the browser hangs without showing any error indication,
**stop and open a new story per Brief D-7-b**. Do not author a browser-side
fallback or any other inline fix here.

---

## Splash Attestation

### Scenario S-1 — Server unreachable on boot

**Preparation:** Ensure the TM server is unreachable from the Pi's network (e.g.,
shut down the TM server or use a non-routable URL in `/boot/fullpageos.txt`).

**Action:** Boot the Pi normally.

**Expected outcome at FullPageOS 0.14.0:** FullPageOS displays a **visible
splash** or offline-notice page — not a blank screen — when the kiosk URL cannot
be reached on first load.

**Actual outcome (fill in):** ___________________________________________

**Escalation:** If no visible splash is shown and the screen is blank, **stop
and open a new story per Brief S-2** describing the gap. Do not author an HTML
fallback page or any other inline fix here.

---

## Known Limitations

The following limitations are documented as-is for this release (FullPageOS
0.14.0, E69):

1. **Pi-3 rendering performance not validated.** Only Raspberry Pi 4 is a
   tested platform for this release. Pi-3 hardware may render the TM display
   page with lower frame rates or fail to open some animations. If Pi-3 support
   is required, open a new story to test and document Pi-3 behaviour (Brief D-3,
   T-5).

2. **FullPageOS release cadence is irregular.** Historically FullPageOS tags
   have been 1-2+ years apart. The image is pinned to tag `0.14.0`. When a new
   upstream tag becomes available, a separate story is required to evaluate,
   test, and upgrade the pinned version (Brief O-7, D-9). Do not bump the tag
   without a dedicated story.

3. **`wpa_supplicant.conf` on FAT32 is plaintext-readable.** As described in
   the Flash section (Path 2), any computer that reads the SD card's FAT32
   partition can read the WiFi passphrase. Path 1 (Raspberry Pi Imager headless-
   config) avoids this; Path 2 is only appropriate when operator physical
   security of the SD card is ensured.

4. **Pi-side identity is anonymous.** The Pi has no first-party identifier bound
   to the TM server. The server-side device token (assigned during the Boot &
   Verify step) is the sole means of distinguishing display devices. If the SD
   card is replaced or the token is lost, the device must be re-registered.

---

## Licensing

### vvwt-prj

This module is part of `vvwt-prj`, licensed under the
**GNU Affero General Public License, version 3.0, or (at your option) any
later version** — SPDX identifier `AGPL-3.0-or-later`.

Copyright (C) 2026 Thomas Steinke.

See the `LICENSE` file at the repository root for the full license text.

### FullPageOS

[FullPageOS](https://github.com/guysoft/FullPageOS) is licensed under the
**GNU General Public License, version 3.0** (SPDX: `GPL-3.0-only`).

AGPL-3.0 is compatible with GPL-3.0 per the FSF license compatibility matrix:
<https://www.gnu.org/licenses/license-list.html#AGPL>
The combined work (the built image, which incorporates FullPageOS components) is
governed by AGPL-3.0 (the stronger copyleft) when distributed. FullPageOS is
fetched at build time from its upstream repository — it is not vendored
under `vvwt-prj`.

### Raspberry Pi Imager

[Raspberry Pi Imager](https://github.com/raspberrypi/rpi-imager) is licensed
under the **Apache License, Version 2.0** (SPDX: `Apache-2.0`).
Apache-2.0 is compatible with AGPL-3.0-or-later per the FSF license matrix.

### Transitive dependencies in the built image

The built image includes the following system packages and their transitive
dependencies. All are AGPL-3.0-compatible per the FSF matrix:

| Package | SPDX License | Compatibility note |
|---------|-------------|-------------------|
| RaspiOS Bookworm base | `GPL-2.0-or-later` / mixed | Core OS, GPL-family compatible |
| chromium-browser | `BSD-3-Clause` + component licenses | Permissive, compatible |
| systemd | `LGPL-2.1-or-later` | LGPL, compatible with GPL/AGPL |
| openbox | `GPL-2.0-or-later` | GPL-family, compatible |
| qemu-user-static (build-time) | `GPL-2.0-only` + components | Build-time only; not in final image |

These packages are installed by the FullPageOS build system at image build time.
Their licenses are assessed for AGPL-3.0-or-later compatibility per DEC-75 §D6.
