package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.LanHostDetector;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link LanHostDetector}.
 *
 * <p>Resolution strategy (E49S04):
 *
 * <ol>
 *   <li>If {@code tm.public-host} is set to a non-blank value, return it immediately
 *       (AC-TEST-CONFIG-OVERRIDE-WINS-RED).
 *   <li>Otherwise enumerate local network interfaces via the injected {@link Supplier} (default:
 *       {@link NetworkInterface#getNetworkInterfaces()}) and select the first site-local,
 *       non-loopback IPv4 address (AC-TEST-LAN-HOST-DETECTED-RED /
 *       AC-SECURITY-SITE-LOCAL-PREFERENCE).
 *   <li>If no site-local, non-loopback address is found, fall back to {@code "localhost"} and log a
 *       WARN (AC-ERROR-NO-LAN-INTERFACE-FALLBACK).
 * </ol>
 *
 * <p>No external network call is ever made (DEC-15 / DEC-16 zero-internet constraint).
 *
 * <h2>Secondary constructor for testing</h2>
 *
 * <p>The package-private {@link #DefaultLanHostDetector(TmPublicHostProperties, Supplier)}
 * constructor accepts a custom address supplier so unit tests can inject deterministic address
 * lists without relying on the test machine's actual network configuration.
 *
 * @see LanHostDetector
 * @see TmPublicHostProperties
 * @see <a href="../../../../../../../../docs/governance/stories/E49S04.story.md">Story E49S04</a>
 */
@Service
public class DefaultLanHostDetector implements LanHostDetector {

    private static final Logger log = LoggerFactory.getLogger(DefaultLanHostDetector.class);

    /** Fallback used when no site-local, non-loopback address can be detected. */
    static final String LOOPBACK_FALLBACK = "localhost";

    private final TmPublicHostProperties properties;
    private final Supplier<List<InetAddress>> addressSupplier;

    /**
     * Production constructor — uses the real {@link NetworkInterface} enumeration.
     *
     * @param properties the public-host configuration properties
     */
    public DefaultLanHostDetector(TmPublicHostProperties properties) {
        this(properties, DefaultLanHostDetector::enumerateLocalAddresses);
    }

    /**
     * Testing constructor — accepts a custom address supplier for deterministic unit tests.
     *
     * @param properties the public-host configuration properties
     * @param addressSupplier supplies the list of {@link InetAddress} to consider
     */
    DefaultLanHostDetector(
            TmPublicHostProperties properties, Supplier<List<InetAddress>> addressSupplier) {
        this.properties = properties;
        this.addressSupplier = addressSupplier;
    }

    /**
     * {@inheritDoc}
     *
     * <p>When {@code tm.public-host} is configured (non-blank) the configured value is returned
     * immediately. Otherwise local network interfaces are enumerated and the first site-local,
     * non-loopback IPv4 address is selected. Falls back to {@value #LOOPBACK_FALLBACK} when no
     * usable interface is found.
     */
    @Override
    public String detectHost() {
        // Step 1: config override wins (AC-TEST-CONFIG-OVERRIDE-WINS-RED)
        if (properties.hasOverride()) {
            return properties.getPublicHost();
        }

        // Step 2: auto-detect — enumerate local interfaces (no external call; DEC-16)
        List<InetAddress> addresses = addressSupplier.get();
        for (InetAddress addr : addresses) {
            if (addr.isSiteLocalAddress() && !addr.isLoopbackAddress()) {
                return addr.getHostAddress();
            }
        }

        // Step 3: no site-local found — log WARN and return documented fallback
        log.warn(
                "[E49S04] No site-local, non-loopback network interface detected. Falling back to"
                    + " '{}'. Set tm.public-host to the correct LAN address when the auto-detected"
                    + " value is wrong (AC-ERROR-NO-LAN-INTERFACE-FALLBACK).",
                LOOPBACK_FALLBACK);
        return LOOPBACK_FALLBACK;
    }

    /**
     * Enumerates all InetAddresses from local NetworkInterfaces (production path).
     *
     * <p>Filters: interface must be up. Skips interfaces that throw {@link
     * java.net.SocketException}. No external call is made (DEC-15 / DEC-16).
     *
     * @return list of all addresses from up local interfaces; never {@code null}
     */
    private static List<InetAddress> enumerateLocalAddresses() {
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
                } catch (java.net.SocketException e) {
                    continue;
                }
                result.addAll(Collections.list(nic.getInetAddresses()));
            }
        } catch (java.net.SocketException e) {
            log.warn("[E49S04] Could not enumerate network interfaces: {}", e.getMessage());
        }
        return result;
    }
}
