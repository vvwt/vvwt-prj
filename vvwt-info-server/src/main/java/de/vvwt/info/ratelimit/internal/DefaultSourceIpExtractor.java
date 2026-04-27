package de.vvwt.info.ratelimit.internal;

import de.vvwt.info.ratelimit.SourceIpExtractor;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link SourceIpExtractor} with configurable trusted-proxy support
 * (E38S07 AC9).
 *
 * <p>Behaviour:
 *
 * <ul>
 *   <li><b>Empty trusted-proxies list (default)</b>: {@code X-Forwarded-For} is IGNORED entirely;
 *       {@link HttpServletRequest#getRemoteAddr()} is always the effective IP. An attacker on the
 *       public internet cannot spoof their IP by sending a forged XFF header.
 *   <li><b>Non-empty trusted-proxies</b>: if the request's {@code RemoteAddr} matches a trusted
 *       proxy (exact IP or CIDR range), the LEFTMOST IP in {@code X-Forwarded-For} is used. If the
 *       RemoteAddr does NOT match a trusted proxy, {@code RemoteAddr} is used (XFF ignored).
 * </ul>
 *
 * <p>CIDR notation (e.g. {@code "10.0.0.0/8"}) is supported. IPv4 only in Phase 1.
 *
 * @see <a href="../../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC9</a>
 */
public class DefaultSourceIpExtractor implements SourceIpExtractor {

    private static final Logger log = LoggerFactory.getLogger(DefaultSourceIpExtractor.class);

    private final List<CidrBlock> trustedProxies;

    public DefaultSourceIpExtractor(List<String> trustedProxyCidrs) {
        this.trustedProxies =
                trustedProxyCidrs.stream().map(DefaultSourceIpExtractor::parseCidr).toList();
    }

    @Override
    public String extract(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        if (trustedProxies.isEmpty()) {
            // Default-deny: no trusted proxies configured → never consult XFF
            return remoteAddr;
        }

        if (isFromTrustedProxy(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // Use leftmost (original client) IP from XFF chain
                String leftmost = xff.split(",")[0].trim();
                if (!leftmost.isEmpty()) {
                    return leftmost;
                }
            }
        }

        // Not from a trusted proxy (or XFF absent) — use actual remote addr
        return remoteAddr;
    }

    private boolean isFromTrustedProxy(String remoteAddr) {
        try {
            InetAddress addr = InetAddress.getByName(remoteAddr);
            for (CidrBlock block : trustedProxies) {
                if (block.contains(addr)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn(
                    "Could not parse remote address '{}' for trusted-proxy check: {}",
                    remoteAddr,
                    e.getMessage());
        }
        return false;
    }

    private static CidrBlock parseCidr(String cidr) {
        if (cidr.contains("/")) {
            String[] parts = cidr.split("/", 2);
            return new CidrBlock(parts[0], Integer.parseInt(parts[1].trim()));
        } else {
            // Exact IP — treated as /32 (IPv4) or /128 (IPv6)
            return new CidrBlock(cidr, -1);
        }
    }

    /** Immutable CIDR block used for trusted-proxy matching. */
    private record CidrBlock(String networkAddress, int prefixLength) {

        boolean contains(InetAddress addr) {
            try {
                InetAddress network = InetAddress.getByName(networkAddress);
                if (prefixLength < 0) {
                    // Exact-IP match
                    return network.equals(addr);
                }
                byte[] netBytes = network.getAddress();
                byte[] addrBytes = addr.getAddress();
                if (netBytes.length != addrBytes.length) {
                    return false; // IPv4 vs IPv6 mismatch
                }
                BigInteger netInt = new BigInteger(1, netBytes);
                BigInteger addrInt = new BigInteger(1, addrBytes);
                int bits = netBytes.length * 8;
                BigInteger mask =
                        prefixLength == 0
                                ? BigInteger.ZERO
                                : BigInteger.ONE
                                        .shiftLeft(bits - prefixLength)
                                        .subtract(BigInteger.ONE)
                                        .not()
                                        .and(
                                                BigInteger.ONE
                                                        .shiftLeft(bits)
                                                        .subtract(BigInteger.ONE));
                return netInt.and(mask).equals(addrInt.and(mask));
            } catch (Exception e) {
                return false;
            }
        }
    }
}
