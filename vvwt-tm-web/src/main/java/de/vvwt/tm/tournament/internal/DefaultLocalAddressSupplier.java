// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.LocalAddressSupplier;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link LocalAddressSupplier}.
 *
 * <p>Enumerates all {@link InetAddress} instances from up local network interfaces using {@link
 * NetworkInterface#getNetworkInterfaces()}. No external network call is made (DEC-15 / DEC-16
 * zero-internet constraint).
 *
 * <p>Exceptions during interface enumeration are caught and logged at WARN level; the method
 * returns an empty list rather than propagating. Individual per-interface {@link SocketException}s
 * during {@link NetworkInterface#isUp()} are silently skipped per the same defensive contract
 * established in E49S04.
 *
 * @see LocalAddressSupplier
 * @see DefaultLanHostDetector
 * @see <a href="../../../../../../../../docs/governance/stories/E49S05.story.md">Story E49S05</a>
 */
@Service
class DefaultLocalAddressSupplier implements LocalAddressSupplier {

    private static final Logger log = LoggerFactory.getLogger(DefaultLocalAddressSupplier.class);

    /**
     * {@inheritDoc}
     *
     * <p>Reads the local network interface table via {@link
     * NetworkInterface#getNetworkInterfaces()}. Only interfaces that are {@link
     * NetworkInterface#isUp() up} contribute addresses. Interfaces that throw {@link
     * SocketException} on the {@code isUp()} check are silently skipped.
     *
     * <p>No external network call is ever made (DEC-15 / DEC-16).
     */
    @Override
    public List<InetAddress> getLocalAddresses() {
        List<InetAddress> result = new ArrayList<>();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return result;
            }
            for (NetworkInterface nic : Collections.list(interfaces)) {
                try {
                    if (!nic.isUp()) {
                        continue;
                    }
                } catch (SocketException e) {
                    continue;
                }
                result.addAll(Collections.list(nic.getInetAddresses()));
            }
        } catch (SocketException e) {
            log.warn("[E49S05] Could not enumerate network interfaces: {}", e.getMessage());
        }
        return result;
    }
}
