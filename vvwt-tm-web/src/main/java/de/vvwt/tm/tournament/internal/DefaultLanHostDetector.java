package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.LanHostDetector;
import de.vvwt.tm.tournament.LocalAddressSupplier;
import java.net.InetAddress;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link LanHostDetector}.
 *
 * <p>Resolution strategy (E49S04, structurally fixed in E49S05):
 *
 * <ol>
 *   <li>If {@code tm.public-host} is set to a non-blank value, return it immediately
 *       (AC-TEST-CONFIG-OVERRIDE-WINS-RED).
 *   <li>Otherwise obtain the list of local addresses from the injected {@link LocalAddressSupplier}
 *       and select the first site-local, non-loopback IPv4 address (AC-TEST-LAN-HOST-DETECTED-RED /
 *       AC-SECURITY-SITE-LOCAL-PREFERENCE).
 *   <li>If no site-local, non-loopback address is found, fall back to {@code "localhost"} and log a
 *       WARN (AC-ERROR-NO-LAN-INTERFACE-FALLBACK).
 * </ol>
 *
 * <p>No external network call is ever made (DEC-15 / DEC-16 zero-internet constraint). The actual
 * network-interface enumeration is delegated to {@link LocalAddressSupplier} — a genuine production
 * collaborator injected by Spring, not a test-only seam (E49S05 / DEC-69 converse-Iron-Law
 * principle).
 *
 * @see LanHostDetector
 * @see LocalAddressSupplier
 * @see TmPublicHostProperties
 * @see <a href="../../../../../../../../docs/governance/stories/E49S04.story.md">Story E49S04</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E49S05.story.md">Story E49S05</a>
 */
@Service
public class DefaultLanHostDetector implements LanHostDetector {

    private static final Logger log = LoggerFactory.getLogger(DefaultLanHostDetector.class);

    /** Fallback used when no site-local, non-loopback address can be detected. */
    static final String LOOPBACK_FALLBACK = "localhost";

    private final TmPublicHostProperties properties;
    private final LocalAddressSupplier localAddressSupplier;

    /**
     * Production constructor — Spring injects both collaborators.
     *
     * <p>This is the single constructor; Spring Boot 4.x implicit single-constructor injection
     * applies without requiring {@code @Autowired} (AC-FIX-SINGLE-PRODUCTION-CONSTRUCTOR).
     *
     * @param properties the public-host configuration properties
     * @param localAddressSupplier the production collaborator that enumerates local network
     *     addresses
     */
    public DefaultLanHostDetector(
            TmPublicHostProperties properties, LocalAddressSupplier localAddressSupplier) {
        this.properties = properties;
        this.localAddressSupplier = localAddressSupplier;
    }

    /**
     * {@inheritDoc}
     *
     * <p>When {@code tm.public-host} is configured (non-blank) the configured value is returned
     * immediately. Otherwise local network addresses are obtained from the injected {@link
     * LocalAddressSupplier} and the first site-local, non-loopback IPv4 address is selected. Falls
     * back to {@value #LOOPBACK_FALLBACK} when no usable address is found.
     */
    @Override
    public String detectHost() {
        // Step 1: config override wins (AC-TEST-CONFIG-OVERRIDE-WINS-RED)
        if (properties.hasOverride()) {
            return properties.getPublicHost();
        }

        // Step 2: auto-detect — delegate to production collaborator (no external call; DEC-16)
        List<InetAddress> addresses = localAddressSupplier.getLocalAddresses();
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
}
