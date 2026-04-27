package de.vvwt.info.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.info.ratelimit.internal.TournamentConcurrencyLimiter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TournamentConcurrencyLimiter}.
 *
 * <p>DEC-22 RED-first. Verifies slot acquisition/release and concurrency safety.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC3,
 *     AC13</a>
 */
class TournamentConcurrencyLimiterTest {

    private static final String TOKEN = "tournament-token-1";
    private static final String TOKEN2 = "tournament-token-2";

    @Test
    void mSlots_mAcquisitionsSucceed() {
        TournamentConcurrencyLimiter limiter = new TournamentConcurrencyLimiter(3);

        assertThat(limiter.tryAcquireSlot(TOKEN)).isTrue();
        assertThat(limiter.tryAcquireSlot(TOKEN)).isTrue();
        assertThat(limiter.tryAcquireSlot(TOKEN)).isTrue();
    }

    @Test
    void mPlusOneSlot_rejected() {
        TournamentConcurrencyLimiter limiter = new TournamentConcurrencyLimiter(2);

        limiter.tryAcquireSlot(TOKEN);
        limiter.tryAcquireSlot(TOKEN);

        assertThat(limiter.tryAcquireSlot(TOKEN)).isFalse(); // M+1 rejected
    }

    @Test
    void releaseSlot_allowsNextAcquisition() {
        TournamentConcurrencyLimiter limiter = new TournamentConcurrencyLimiter(1);

        assertThat(limiter.tryAcquireSlot(TOKEN)).isTrue();
        assertThat(limiter.tryAcquireSlot(TOKEN)).isFalse(); // exhausted

        limiter.releaseSlot(TOKEN);

        assertThat(limiter.tryAcquireSlot(TOKEN)).isTrue(); // slot freed
    }

    @Test
    void slotCountNeverNegative() {
        TournamentConcurrencyLimiter limiter = new TournamentConcurrencyLimiter(1);

        limiter.releaseSlot(TOKEN); // spurious release on empty — should not go negative
        limiter.releaseSlot(TOKEN);

        assertThat(limiter.currentCount(TOKEN)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void differentTokens_independentCounters() {
        TournamentConcurrencyLimiter limiter = new TournamentConcurrencyLimiter(1);

        limiter.tryAcquireSlot(TOKEN);
        assertThat(limiter.tryAcquireSlot(TOKEN)).isFalse(); // TOKEN exhausted

        assertThat(limiter.tryAcquireSlot(TOKEN2)).isTrue(); // TOKEN2 independent
    }

    @Test
    void concurrentAcquisitions_doNotExceedMax() throws InterruptedException {
        int maxSlots = 5;
        int threads = 20;
        TournamentConcurrencyLimiter limiter = new TournamentConcurrencyLimiter(maxSlots);
        AtomicInteger acquired = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(
                    () -> {
                        ready.countDown();
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (limiter.tryAcquireSlot(TOKEN)) {
                            acquired.incrementAndGet();
                        }
                    });
        }
        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(acquired.get()).isLessThanOrEqualTo(maxSlots);
    }
}
