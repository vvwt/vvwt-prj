# pi-display — Raspberry Pi Kiosk Display Provisioning

This module provides a repeatable build process for a FullPageOS-based kiosk
image that boots a Raspberry Pi directly into Chromium fullscreen at the TM
Display registration URL (`/display/register`).

The full operator runbook — build invocation, SD-flash procedure, WiFi options
walkthrough, boot verification, watchdog attestation, splash attestation, and
known limitations — is in **E69S02** (to be added).

## Module Contents

| File | Purpose |
|------|---------|
| `build-image.sh` | FullPageOS 0.14.0 overlay wrapper (see `--help`) |
| `src/test/sh/build-image.bats` | Bats test suite bound to `mvn verify` |
| `pom.xml` | Maven module (packaging=pom; no Java; shellcheck+bats in integration-test phase) |

## Quick Start

```bash
# Dry-run: exercise overlay logic without fetching FullPageOS or building an image
./build-image.sh --dry-run

# Dry-run with kiosk URL pre-baked
./build-image.sh --dry-run --server-url=https://my-tm-server/

# Full image build (Linux host with Docker + qemu-user-static required)
./build-image.sh --server-url=https://my-tm-server/
```

See `./build-image.sh --help` for all options.

## Security Notes

### WiFi Credentials Posture

When you place WiFi credentials into `/boot/firmware/wpa_supplicant.conf` on the
SD card, be aware of the following:

**Path 1 — `/boot/firmware/wpa_supplicant.conf` (FAT32 partition):**
WiFi credentials placed in `/boot/firmware/wpa_supplicant.conf` live in
**plaintext on a FAT32 partition** that any computer can read simply by
inserting the SD card. Trust assumption: **operator-supervised physical
security**. If the SD card leaves physical custody of the tournament
operator, the WiFi passphrase is exposed.

**Path 2 — RaspiOS Imager headless-config path:**
The alternative is to use the [Raspberry Pi Imager](https://www.raspberrypi.com/software/)
(Apache-2.0) headless-config feature, which injects WiFi credentials
into the **rootfs** (not the FAT32 partition) at flash time, using
systemd-based first-boot processing. This reduces the SD-card-readability
surface because the rootfs partition is ext4 (requires Linux to mount)
rather than FAT32 (mountable on any OS).

Both paths are summarised here. Procedural detail — which path to choose,
step-by-step instructions, and tradeoffs — is in the full operator runbook
(E69S02).

**No credentials of any kind are committed under `pi-display/`.**

### License

`build-image.sh` is licensed under AGPL-3.0-or-later (see
`SPDX-License-Identifier` comment at the top of the file).

FullPageOS (cloned at build time, not vendored here) is licensed under GPL-3.0.
AGPL-3.0 is compatible with GPL-3.0 per the FSF license compatibility matrix:
<https://www.gnu.org/licenses/license-list.html#AGPL>
The combined work is governed by AGPL-3.0 (the stronger copyleft).
