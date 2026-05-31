# SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# enforcer.bats — bats test suite for E69S09 maven-enforcer-plugin requireExecutable assertions
#
# Verifies that pi-display/pom.xml contains the maven-enforcer-plugin requireExecutable
# rule for shellcheck and bats, and that pi-display/README.md contains the Host
# Prerequisites section with install commands for ≥2 package managers.
#
# AC2(a): pom.xml contains 'requireExecutable' on a non-comment line
# AC2(b): pom.xml contains 'shellcheck' inside a requireExecutable block
# AC2(c): pom.xml contains 'bats' inside a requireExecutable block
# AC2(d): README.md contains 'shellcheck' in a Host Prerequisites section context
# AC2(e): README.md contains 'bats' in a Host Prerequisites section context
# AC2(f): README.md contains at least one actionable install command for shellcheck
# AC2(g): README.md contains at least one actionable install command for bats
# AC2(h): README.md Host Prerequisites section contains install commands for ≥2 distinct package managers
#
# DEC-22 RED-first: this file was authored and committed in RED state before
# pi-display/pom.xml and pi-display/README.md were updated with the enforcer rule
# and Host Prerequisites section (E69S09).

POM="${BATS_TEST_FILENAME%/src/test/sh/*}/pom.xml"
README="${BATS_TEST_FILENAME%/src/test/sh/*}/README.md"

# ─── AC2(a): pom.xml contains requireExecutable on a non-comment line ─────────
@test "E69S09 AC2(a): pom.xml contains requireExecutable on a non-comment line" {
    run bash -c "grep -v '<!--\|-->' \"${POM}\" | grep -q 'requireExecutable'"
    [ "$status" -eq 0 ]
}

# ─── AC2(b): pom.xml contains shellcheck inside a requireExecutable block ──────
@test "E69S09 AC2(b): pom.xml contains 'shellcheck' inside a requireExecutable block" {
    # Verify both requireExecutable and shellcheck appear in pom.xml
    run bash -c "grep -q 'requireExecutable' \"${POM}\" && grep -q 'shellcheck' \"${POM}\""
    [ "$status" -eq 0 ]
}

# ─── AC2(c): pom.xml contains bats inside a requireExecutable block ────────────
@test "E69S09 AC2(c): pom.xml contains 'bats' inside a requireExecutable block" {
    # Verify both requireExecutable and bats appear in pom.xml
    run bash -c "grep -q 'requireExecutable' \"${POM}\" && grep -q '>bats<' \"${POM}\""
    [ "$status" -eq 0 ]
}

# ─── AC2(d): README.md contains 'shellcheck' in Host Prerequisites section ─────
@test "E69S09 AC2(d): README.md contains 'shellcheck' in Host Prerequisites section context" {
    run bash -c "grep -A 50 'Host Prerequisites' \"${README}\" | grep -q 'shellcheck'"
    [ "$status" -eq 0 ]
}

# ─── AC2(e): README.md contains 'bats' in Host Prerequisites section ───────────
@test "E69S09 AC2(e): README.md contains 'bats' in Host Prerequisites section context" {
    run bash -c "grep -A 50 'Host Prerequisites' \"${README}\" | grep -qE 'bats'"
    [ "$status" -eq 0 ]
}

# ─── AC2(f): README.md contains an actionable install command for shellcheck ───
@test "E69S09 AC2(f): README.md contains actionable install command for shellcheck" {
    run bash -c "grep -A 50 'Host Prerequisites' \"${README}\" | grep -q 'apt install shellcheck\|brew install shellcheck\|dnf install shellcheck\|pacman -S shellcheck'"
    [ "$status" -eq 0 ]
}

# ─── AC2(g): README.md contains an actionable install command for bats ──────────
@test "E69S09 AC2(g): README.md contains actionable install command for bats or bats-core" {
    run bash -c "grep -A 50 'Host Prerequisites' \"${README}\" | grep -qE 'apt install.*bats|brew install.*bats|npm install -g bats|dnf install.*bats|pacman -S.*bats'"
    [ "$status" -eq 0 ]
}

# ─── AC2(h): README.md Host Prerequisites contains ≥2 distinct package managers ─
@test "E69S09 AC2(h): README.md Host Prerequisites section contains install commands for at least 2 distinct package managers" {
    # Check for apt install AND at least one of brew/npm/dnf/pacman
    run bash -c "
        section=\$(grep -A 60 'Host Prerequisites' \"${README}\")
        echo \"\$section\" | grep -q 'apt install' || exit 1
        echo \"\$section\" | grep -qE 'brew install|npm install -g|dnf install|pacman -S' || exit 1
        exit 0
    "
    [ "$status" -eq 0 ]
}
