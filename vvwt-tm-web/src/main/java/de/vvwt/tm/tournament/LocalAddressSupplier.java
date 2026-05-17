// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.net.InetAddress;
import java.util.List;

/**
 * Production collaborator that supplies the list of local {@link InetAddress} instances from the
 * server's own network interfaces.
 *
 * <p>Implementations MUST NOT make any outbound network call. Detection works with zero internet
 * connectivity (DEC-15 / DEC-16). The supplier reads only local network interfaces as provided by
 * the OS.
 *
 * <p>This interface exists so that {@link de.vvwt.tm.tournament.internal.DefaultLanHostDetector}
 * can receive the address list through its single production constructor (Spring constructor
 * injection), replacing the test-only {@code Supplier} seam removed in E49S05.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultLocalAddressSupplier
 * @see de.vvwt.tm.tournament.internal.DefaultLanHostDetector
 * @see <a href="../../../../../../../../docs/governance/stories/E49S05.story.md">Story E49S05</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-15.md">DEC-15</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-16.md">DEC-16</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-58.md">DEC-58</a>
 */
public interface LocalAddressSupplier {

    /**
     * Returns all {@link InetAddress} instances from the server's active local network interfaces.
     *
     * <p>The returned list may be empty when the machine has no up network interfaces.
     * Implementations MUST NOT throw checked exceptions — interface-enumeration errors are
     * swallowed and logged at WARN, returning an empty list (same defensive contract as the
     * production path in E49S04).
     *
     * @return list of all local addresses from up network interfaces; never {@code null}
     */
    List<InetAddress> getLocalAddresses();
}
