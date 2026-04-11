package de.vvwt.dispatcher.packet;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PacketDecomposerService} decomposition logic (AC1, AC2).
 *
 * <p>Tests focus on the packet sizing formula, small-N boundary rule, and
 * factorial computation — isolated from the database.
 *
 * <p>See Story E01S07 AC1, AC2.
 */
class PacketDecomposerServiceTest {

    // -------------------------------------------------------------------------
    // factorial() tests
    // -------------------------------------------------------------------------

    @Test
    void factorial_zero_returnsOne() {
        assertThat(PacketDecomposerService.factorial(0)).isEqualTo(BigInteger.ONE);
    }

    @Test
    void factorial_one_returnsOne() {
        assertThat(PacketDecomposerService.factorial(1)).isEqualTo(BigInteger.ONE);
    }

    @Test
    void factorial_five_returns120() {
        assertThat(PacketDecomposerService.factorial(5)).isEqualTo(BigInteger.valueOf(120));
    }

    @Test
    void factorial_fifteen_returnsCorrectValue() {
        // 15! = 1_307_674_368_000
        BigInteger expected = BigInteger.valueOf(1_307_674_368_000L);
        assertThat(PacketDecomposerService.factorial(15)).isEqualTo(expected);
    }

    @Test
    void factorial_negative_throwsIllegalArgument() {
        assertThatThrownBy(() -> PacketDecomposerService.factorial(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    // -------------------------------------------------------------------------
    // computePacketCount() tests — AC2 sizing formula
    // -------------------------------------------------------------------------

    /**
     * AC2 small-N rule: when {@code n! < 4 × permsPerPacket} → exactly 4 packets.
     *
     * <p>Example: N=3 (3!=6), permsPerPacket=100 → 6 < 400 → 4 packets.
     */
    @Test
    void computePacketCount_smallN_returnsFour() {
        PacketDecomposerService svc = serviceWithConfig(30, 3_333_333);
        BigInteger nFactorial = BigInteger.valueOf(6); // 3! = 6
        BigInteger permsPerPacket = BigInteger.valueOf(30L * 3_333_333);
        int count = svc.computePacketCount(nFactorial, permsPerPacket);
        assertThat(count).isEqualTo(PacketDecomposerService.MIN_PACKET_COUNT);
    }

    /**
     * AC2 small-N boundary: N=5 with default config (5! = 120 < 4 × 100M) → 4 packets.
     */
    @Test
    void computePacketCount_n5DefaultConfig_returnsFour() {
        PacketDecomposerService svc = serviceWithConfig(30, 3_333_333);
        BigInteger nFactorial = PacketDecomposerService.factorial(5); // 120
        BigInteger permsPerPacket = BigInteger.valueOf(30L * 3_333_333); // ~100M
        int count = svc.computePacketCount(nFactorial, permsPerPacket);
        assertThat(count).isEqualTo(4);
    }

    /**
     * AC2 large-N: N=14 with default config.
     * 14! = 87_178_291_200 perms / 100M perms-per-packet ≈ 872 packets.
     */
    @Test
    void computePacketCount_n14DefaultConfig_approximatelyCorrect() {
        PacketDecomposerService svc = serviceWithConfig(30, 3_333_333);
        BigInteger nFactorial = PacketDecomposerService.factorial(14);
        BigInteger permsPerPacket = BigInteger.valueOf(30L * 3_333_333);
        int count = svc.computePacketCount(nFactorial, permsPerPacket);
        // 87_178_291_200 / 99_999_990 ≈ 871.8 → ceil = 872
        assertThat(count).isGreaterThan(4);
        assertThat(count).isBetween(860, 900); // allow rounding variation
    }

    /**
     * AC2 exact-divisor: ensure no off-by-one when n! divides permsPerPacket exactly.
     */
    @Test
    void computePacketCount_exactDivision_noRemainder() {
        PacketDecomposerService svc = serviceWithConfig(30, 3_333_333);
        BigInteger permsPerPacket = BigInteger.valueOf(100L);
        BigInteger nFactorial = BigInteger.valueOf(400L); // 400 / 100 = 4 exactly (equals MIN threshold)
        // 400 == 4 × 100 → compare: 400 < 400? No → ceil(400/100) = 4
        int count = svc.computePacketCount(nFactorial, permsPerPacket);
        assertThat(count).isEqualTo(4);
    }

    /**
     * AC2 ceil behaviour: when there is a remainder, rounds up.
     */
    @Test
    void computePacketCount_withRemainder_roundsUp() {
        PacketDecomposerService svc = serviceWithConfig(30, 3_333_333);
        BigInteger permsPerPacket = BigInteger.valueOf(100L);
        BigInteger nFactorial = BigInteger.valueOf(401L); // 401 > 4×100 → ceil(401/100) = 5
        int count = svc.computePacketCount(nFactorial, permsPerPacket);
        assertThat(count).isEqualTo(5);
    }

    // -------------------------------------------------------------------------
    // Helper: build a service with injected config values (bypassing Spring context)
    // -------------------------------------------------------------------------

    private PacketDecomposerService serviceWithConfig(long wallClockSec, long refRateHz) {
        // Minimal stub — we only test the public computation methods which don't
        // require database access
        PacketDecomposerService svc = new PacketDecomposerService(null, null, null);
        // Use reflection-free approach: override via package-private setters
        // Since the @Value fields are private and there are no setters in the production
        // class, we test computePacketCount() by calling it directly with explicit BigIntegers.
        // The service is instantiated just to access the non-static method.
        return svc;
    }
}
