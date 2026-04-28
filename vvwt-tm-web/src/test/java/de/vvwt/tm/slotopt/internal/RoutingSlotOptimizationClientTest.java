package de.vvwt.tm.slotopt.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.vvwt.tm.slotopt.DirectSlotOptimizationClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RoutingSlotOptimizationClient} — Leg-1 delegation contract.
 *
 * <p>TDD RED-first per DEC-22 Iron Law. The RED state is the commit immediately before {@link
 * RoutingSlotOptimizationClient} was authored (the commit that adds only this test file).
 *
 * <h2>Delegation contract</h2>
 *
 * <p>At E27S01 (Leg 1 only), {@link RoutingSlotOptimizationClient#optimize(UUID)} MUST delegate to
 * {@link DirectSlotOptimizationClient#optimize(UUID)} unconditionally and exactly once, regardless
 * of N. No branching logic on N is introduced in this story (Legs 2/3 land in E27S02/S03).
 *
 * <p>Per DEC-36: this test class is in the {@code slotopt.internal} package (same as the subject),
 * so white-box reference to {@link RoutingSlotOptimizationClient} is permitted.
 *
 * @see RoutingSlotOptimizationClient
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S01.story.md">Story
 *     E27S01</a>
 */
class RoutingSlotOptimizationClientTest {

    private DirectSlotOptimizationClient directMock;
    private RoutingSlotOptimizationClient subject;

    @BeforeEach
    void setUp() {
        directMock = mock(DirectSlotOptimizationClient.class);
        subject = new RoutingSlotOptimizationClient(directMock);
    }

    /**
     * AC-LEG-1-DELEGATION-CONTRACT-TESTED (a): optimize() delegates to Direct exactly once and
     * returns (void contract preserved).
     */
    @Test
    void optimize_delegatesToDirectExactlyOnce() {
        UUID phaseId = UUID.randomUUID();

        subject.optimize(phaseId);

        verify(directMock).optimize(phaseId);
    }

    /**
     * AC-LEG-1-DELEGATION-CONTRACT-TESTED: nulls are forwarded to Direct; Direct enforces the null
     * check per its own AC7 contract.
     *
     * <p>RoutingSlotOptimizationClient must NOT add its own null guard — responsibility delegation
     * is total.
     */
    @Test
    void optimize_forwardsNullPhaseIdToDirect() {
        subject.optimize(null);

        verify(directMock).optimize(null);
    }
}
