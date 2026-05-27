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

Before building an image you need a Linux host with:

- **Docker** — FullPageOS uses Docker internally for the build environment.
  On Debian/Ubuntu: `sudo apt-get install docker.io`.
  **`podman` + `podman-docker`** is a supported substitute on Linux hosts where
  Docker is not installed — the `podman-docker` package provides a `docker` shim
  that satisfies the pre-flight check. Note: **`sudo` invocation is still required**
  (the script does not auto-elevate; see "Run with sudo" below).
- **`qemu-user-static`** — installed on the Docker host for ARM emulation.
  On Debian/Ubuntu: `sudo apt-get install qemu-user-static`.
- **`wget`** — for downloading the Raspbian base image required by the FullPageOS
  build pipeline. On Debian/Ubuntu: `sudo apt-get install wget`.
- **`sudo`** — the script must be invoked with `sudo` (see "Run with sudo" below).
  On Debian/Ubuntu: `sudo apt-get install sudo`.
- **Kernel loop-module support** — `sudo modprobe loop` must succeed on the host.
  Required by the CustomPiOS `mount_image` step inside the Docker build.
  Standard on Linux kernel installations; may need activation in some containers.
- A working **internet connection** — the build clones FullPageOS 0.14.0,
  clones CustomPiOS, downloads the Raspbian base image, and installs packages.
  CustomPiOS is cloned automatically by the script; the operator does not need
  to clone it manually — only outbound internet access to GitHub is required.
- **`git`** — to clone `vvwt-prj` and for the FullPageOS / CustomPiOS clones.
  On Debian/Ubuntu: `sudo apt-get install git`.
- **Python packages required by the FullPageOS / CustomPiOS Python build scripts:
  `python3-git`** (GitPython) **+ `python3-yaml`** (PyYAML). On Debian/Ubuntu:
  `sudo apt-get install python3-git python3-yaml`. On other distros: install the
  equivalents that provide the `git` and `yaml` Python modules (the script's
  pre-flight check verifies the Python imports, not specific apt package names —
  distro-portable by design).

**Run with sudo:** invoke the script as `sudo ./build-image.sh --server-url=…`
The script does not auto-elevate internally — this preserves principle-of-least-privilege
and the operator's audit visibility into elevation. If you run without sudo, the build
fails fast at the `build_dist` step with an actionable message.

The upstream FullPageOS README at
<https://github.com/guysoft/FullPageOS> documents the exact Docker build
procedure in detail; consult it if the build environment needs further tuning.

### Invocation

From the `vvwt-prj/pi-display/` directory:

```bash
# Recommended: full image build with kiosk URL pre-baked (requires sudo)
sudo ./build-image.sh --server-url=https://your-tm-host/

# Dry-run: exercise overlay logic without fetching FullPageOS or building an image
sudo ./build-image.sh --dry-run --server-url=https://your-tm-host/

# Show all options
sudo ./build-image.sh --help
```

The `--server-url` argument bakes the TM Display URL into `/boot/fullpageos.txt`
inside the image so Chromium opens it automatically on first boot.

### Expected output

A successful build places a `.img` file under the `src/workspace/` subdirectory
of the FullPageOS clone that `build-image.sh` creates in a temporary directory
(typically printed at the end of the build as
`${WORK_DIR}/FullPageOS/src/workspace/*.img`). The exact path is printed
at the end of a successful build.

This path follows the CustomPiOS convention: `BASE_WORKSPACE=${DIST_PATH}/workspace`
where `DIST_PATH` is the FullPageOS `src/` directory.

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

## Image-Build Attestation

Perform this scenario on your **Linux build host** before declaring the
single-command image build working. Record the actual outcome next to the
expected outcome. If any gap is observed, **stop and open a new story per
the E69S01 Brief D-7-b precedent** — do not author inline systemd drop-ins,
container-driver shims, or any other in-runbook fix here.

### Preparation

Install all prerequisites listed in the Prerequisites section above. Verify:

- Docker (or `podman+podman-docker`) is installed and the daemon is running.
- `qemu-user-static`, `wget`, `sudo`, and `git` are present.
- `sudo modprobe loop` succeeds.
- Outbound internet access to GitHub (`github.com`) is available.

Also verify the bats test suite passes on your checkout:

```bash
mvn -pl pi-display -am verify
```

Expected: `BUILD SUCCESS`.

### Action

From the `vvwt-prj/pi-display/` directory:

```bash
sudo ./build-image.sh --server-url=https://your-tm-host/
```

### Expected outcome at FullPageOS 0.14.0

(i) A `.img` file is produced under `${WORK_DIR}/FullPageOS/src/workspace/`
(the exact path is printed at the end of the build).

(ii) Zero error output is emitted during the build (Docker build log may contain
informational output; errors are distinguished by non-zero exit or explicit
`ERROR:` lines).

(iii) The bats assertions pass on your local checkout:
`mvn -pl pi-display -am verify` reports `BUILD SUCCESS`.

### Actual outcome — E69S03 attest (2026-05-27 19:02) — gap event

(i) .img produced: No — build failed before image production.

(ii) Error output: `build_dist: line 4: .../FullPageOS/src/custompios_path: No such file or directory` — `CUSTOM_PI_OS_PATH=` — `/build_custom_os: not found`. **Resolved by E69S04** (argument to `update-custompios-paths` corrected from `FullPageOS` to `FullPageOS/src`).

(iii) mvn verify: BUILD SUCCESS (bats tests pass; full Docker build not run in CI).

### Actual outcome — E69S04 post-fix re-attestation (fill in)

(i) .img produced: ___________________________________________

(ii) Error output: ___________________________________________

(iii) mvn verify: ___________________________________________

### Actual outcome — E69S05 post-fix re-attestation (fill in)

**Preparation:** Ensure `python3-git` and `python3-yaml` are installed (see Prerequisites above).
If validating the pre-flight check itself, temporarily remove one or both packages and verify
the script exits non-zero at pre-flight time with the actionable English error message, naming
the missing module and the apt-install remedy hint. Reinstall before running the full build.

(i) .img produced: ___________________________________________

(ii) Error output: ___________________________________________

(iii) mvn verify: ___________________________________________

**Escalation:** If after installing `python3-git` + `python3-yaml` the build still fails inside
the FullPageOS chroot with a Python `ModuleNotFoundError` for a different module, **stop and open
a new story per the E69S03 AC5 Escalation-Clause** — the new story follows the same Bug-Triage
Flow (root cause established empirically before authoring acceptance criteria). Do not author inline
fixes here.

### Escalation

If the operator observes a gap between the expected and actual outcome,
**stop and open a new story per the E69S01 Brief D-7-b precedent** — do not
author inline systemd drop-ins, container-driver shims, or any other
in-runbook fix here. The new story follows the same Bug-Triage Flow as E69S03
(root cause established empirically before authoring acceptance criteria).

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
