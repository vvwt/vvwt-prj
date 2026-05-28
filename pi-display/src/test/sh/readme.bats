# SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# readme.bats — content-anchored bats test suite for pi-display/README.md
#
# Verifies the operator runbook contains all required sections, the SPDX header,
# the two escalation instructions, and the absence of forbidden inline-fix strings.
#
# AC8:  SPDX header assertion
# AC9:  section-heading greps (Build, Flash, Boot & Verify, Watchdog Attestation,
#       Splash Attestation, Known Limitations, Licensing) + escalation-instruction
#       greps (Brief D-7-b and Brief S-2 stop-and-open-new-story)
# AC11: forbidden-string assertion (systemctl edit, nano /etc/systemd)
#
# DEC-22 RED-first: this file was authored and committed in RED state before
# pi-display/README.md was expanded from its E69S01 stub.

README="${BATS_TEST_FILENAME%/src/test/sh/*}/README.md"

# ─── AC8: SPDX header ─────────────────────────────────────────────────────────
@test "README.md contains SPDX-License-Identifier: AGPL-3.0-or-later" {
    run grep -q 'SPDX-License-Identifier: AGPL-3.0-or-later' "${README}"
    [ "$status" -eq 0 ]
}

# ─── AC9: section headings ────────────────────────────────────────────────────
@test "README.md contains ## Build section" {
    run grep -q '^## Build' "${README}"
    [ "$status" -eq 0 ]
}

@test "README.md contains ## Flash section" {
    run grep -q '^## Flash' "${README}"
    [ "$status" -eq 0 ]
}

@test "README.md contains ## Boot & Verify section" {
    run grep -q '^## Boot' "${README}"
    [ "$status" -eq 0 ]
}

@test "README.md contains ## Watchdog Attestation section" {
    run grep -q '^## Watchdog Attestation' "${README}"
    [ "$status" -eq 0 ]
}

@test "README.md contains ## Splash Attestation section" {
    run grep -q '^## Splash Attestation' "${README}"
    [ "$status" -eq 0 ]
}

@test "README.md contains ## Known Limitations section" {
    run grep -q '^## Known Limitations' "${README}"
    [ "$status" -eq 0 ]
}

@test "README.md contains ## Licensing section" {
    run grep -q '^## Licensing' "${README}"
    [ "$status" -eq 0 ]
}

# ─── AC9: escalation instructions ────────────────────────────────────────────
# Watchdog escalation per Brief D-7-b: operator instructed to open a new story
@test "README.md contains watchdog escalation instruction (open a new story)" {
    run grep -qi 'new story' "${README}"
    [ "$status" -eq 0 ]
}

# Splash escalation per Brief S-2: similar new-story instruction
@test "README.md contains splash escalation instruction (stop and open)" {
    run grep -qi 'stop and open' "${README}"
    [ "$status" -eq 0 ]
}

# ─── AC11: forbidden strings (no inline systemd drop-ins) ────────────────────
@test "README.md does not contain 'systemctl edit'" {
    run grep -q 'systemctl edit' "${README}"
    [ "$status" -ne 0 ]
}

@test "README.md does not contain 'nano /etc/systemd'" {
    run grep -q 'nano /etc/systemd' "${README}"
    [ "$status" -ne 0 ]
}

# ─── E69S07 AC3: readme.bats content-anchor for CustomPiOS 1.5.0 pin Note ────
# Verifies README.md contains a triple-anchored token set for the CustomPiOS pin Note:
# (i) 'CustomPiOS' AND '1.5.0' AND 'libconfig9' — each required in the README.
#
# Per AC3 spec: assertion fails RED against pre-story README (no CustomPiOS pin Note);
# passes GREEN after the fix. Routes to readme.bats per E69S06 AC6 routing precedent.
# Static content inspection only.

@test "E69S07 AC3(i): README.md contains CustomPiOS pin Note anchor — 'CustomPiOS' present with '1.5.0'" {
    run bash -c "grep -q 'CustomPiOS' \"${README}\" && grep -q '1\.5\.0' \"${README}\""
    [ "$status" -eq 0 ]
}

@test "E69S07 AC3(ii): README.md contains CustomPiOS pin Note anchor — 'libconfig9' present" {
    run grep -q 'libconfig9' "${README}"
    [ "$status" -eq 0 ]
}
