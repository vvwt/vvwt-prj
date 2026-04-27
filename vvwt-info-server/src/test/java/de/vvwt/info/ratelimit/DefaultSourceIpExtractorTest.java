package de.vvwt.info.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.vvwt.info.ratelimit.internal.DefaultSourceIpExtractor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultSourceIpExtractor}.
 *
 * <p>DEC-22 RED-first. Covers AC9: default-deny X-Forwarded-For; CIDR trusted proxy; leftmost XFF
 * when trusted proxy.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC9</a>
 */
@ExtendWith(MockitoExtension.class)
class DefaultSourceIpExtractorTest {

    @Mock private HttpServletRequest request;

    @Test
    void emptyTrustedProxies_alwaysUsesRemoteAddr_ignoresXff() {
        DefaultSourceIpExtractor extractor = new DefaultSourceIpExtractor(List.of());
        when(request.getRemoteAddr()).thenReturn("192.0.2.1");
        // No XFF stub: empty trusted-proxies means XFF is never consulted (AC9 default-deny)

        String ip = extractor.extract(request);

        assertThat(ip).isEqualTo("192.0.2.1");
    }

    @Test
    void emptyTrustedProxies_noXff_usesRemoteAddr() {
        DefaultSourceIpExtractor extractor = new DefaultSourceIpExtractor(List.of());
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");
        // No XFF stub: empty trusted-proxies means XFF is never consulted

        String ip = extractor.extract(request);

        assertThat(ip).isEqualTo("10.0.0.5");
    }

    @Test
    void trustedProxy_requestFromTrustedIp_usesLeftmostXff() {
        DefaultSourceIpExtractor extractor =
                new DefaultSourceIpExtractor(List.of("10.0.0.1")); // exact IP trust
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");

        String ip = extractor.extract(request);

        assertThat(ip).isEqualTo("203.0.113.5");
    }

    @Test
    void trustedProxy_requestFromUntrustedIp_usesRemoteAddr() {
        DefaultSourceIpExtractor extractor =
                new DefaultSourceIpExtractor(List.of("10.0.0.1")); // only 10.0.0.1 trusted
        when(request.getRemoteAddr()).thenReturn("192.0.2.99"); // NOT trusted
        // No XFF stub: untrusted remote addr → XFF never read

        String ip = extractor.extract(request);

        assertThat(ip).isEqualTo("192.0.2.99"); // actual remote addr, not spoofed XFF
    }

    @Test
    void trustedProxyCidr_requestFromCidrRange_usesLeftmostXff() {
        DefaultSourceIpExtractor extractor =
                new DefaultSourceIpExtractor(List.of("10.0.0.0/8")); // CIDR
        when(request.getRemoteAddr()).thenReturn("10.5.6.7"); // inside 10.0.0.0/8
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10");

        String ip = extractor.extract(request);

        assertThat(ip).isEqualTo("203.0.113.10");
    }

    @Test
    void trustedProxyCidr_requestOutsideCidr_usesRemoteAddr() {
        DefaultSourceIpExtractor extractor =
                new DefaultSourceIpExtractor(List.of("10.0.0.0/8")); // CIDR
        when(request.getRemoteAddr()).thenReturn("192.168.1.5"); // outside 10.0.0.0/8
        // No XFF stub: remote addr outside CIDR range → XFF never read

        String ip = extractor.extract(request);

        assertThat(ip).isEqualTo("192.168.1.5"); // not 8.8.8.8
    }
}
