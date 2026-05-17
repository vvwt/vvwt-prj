// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.e2e;

/**
 * Minimal subprocess helper for {@link DispatcherProcessLauncherTest}.
 *
 * <p>Prints a sentinel diagnostic line to stderr and exits with code 1, simulating a dispatcher
 * subprocess that fails during startup with a recognisable error message.
 *
 * <p>Used exclusively to exercise the {@link DispatcherProcessLauncher} output-capture path
 * without requiring a real dispatcher JAR.
 */
class SentinelAndExitMain {

    static final String SENTINEL_LINE = "SENTINEL: fake-dispatcher-startup-error";

    public static void main(String[] args) {
        System.err.println(SENTINEL_LINE);
        System.err.flush();
        System.exit(1);
    }
}
