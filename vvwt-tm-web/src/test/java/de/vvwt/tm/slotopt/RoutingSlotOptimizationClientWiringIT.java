package de.vvwt.tm.slotopt;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test verifying that {@link RoutingSlotOptimizationClient} is wired as the
 * {@code @Primary} {@link SlotOptimizationClient} after E27S01.
 *
 * <h2>Wiring contract (AC-ROUTING-CLIENT-PRIMARY-WIRING-VERIFIED)</h2>
 *
 * <ol>
 *   <li>{@code @Autowired SlotOptimizationClient} resolves to a {@link
 *       RoutingSlotOptimizationClient} instance — NOT {@link DirectSlotOptimizationClient} or
 *       {@link FallbackSlotOptimizationClient}.
 *   <li>{@link FallbackSlotOptimizationClient}'s {@code @ConditionalOnMissingBean} evaluates to
 *       {@code false} (two {@link SlotOptimizationClient} beans now exist: Direct + Routing) — the
 *       fallback bean is NOT registered.
 *   <li>Exactly one {@link SlotOptimizationClient} bean resolves as the primary injection candidate
 *       (the routing client).
 * </ol>
 *
 * <h2>IT annotation (DEC-38 Clause A)</h2>
 *
 * <p>This IT uses {@code @SpringBootTest(classes = TournamentManagerApplication.class)} — the full
 * application context — because {@code slotopt} is not yet a DEC-38 reconstructed bounded-context
 * module with a dedicated {@code @ApplicationModuleTest} scope. The {@code slotopt} bounded context
 * is registered by E27S01 (adding {@code package-info.java}), but the existing classes ({@link
 * DirectSlotOptimizationClient}, {@link FallbackSlotOptimizationClient}) are pre-DEC-22 legacy
 * code; they are not in the DEC-38 reconstruction scope for this story. Full-context boot is
 * therefore the correct and safe annotation.
 *
 * @see RoutingSlotOptimizationClient
 * @see SlotOptimizationClient
 * @see <a href="../../../../../../../../docs/governance/stories/E27S01.story.md">Story E27S01</a>
 */
@SpringBootTest(classes = de.vvwt.tm.TournamentManagerApplication.class)
@ActiveProfiles("test")
class RoutingSlotOptimizationClientWiringIT {

    @Autowired private SlotOptimizationClient slotOptimizationClient;

    @Autowired private ApplicationContext applicationContext;

    /**
     * AC-ROUTING-CLIENT-PRIMARY-WIRING-VERIFIED: the injected {@link SlotOptimizationClient} bean
     * is an instance of {@link RoutingSlotOptimizationClient}.
     */
    @Test
    void primarySlotOptimizationClient_isRoutingSlotOptimizationClient() {
        assertThat(slotOptimizationClient)
                .as(
                        "@Autowired SlotOptimizationClient should resolve to"
                                + " RoutingSlotOptimizationClient (E27S01 @Primary)")
                .isInstanceOf(RoutingSlotOptimizationClient.class);
    }

    /**
     * AC-ROUTING-CLIENT-PRIMARY-WIRING-VERIFIED: {@link FallbackSlotOptimizationClient} is NOT
     * registered in the ApplicationContext because its {@code @ConditionalOnMissingBean} evaluates
     * to {@code false} (two {@link SlotOptimizationClient} beans coexist post-E27S01).
     */
    @Test
    void fallbackSlotOptimizationClient_isNotRegistered() {
        assertThat(applicationContext.getBeansOfType(FallbackSlotOptimizationClient.class))
                .as("FallbackSlotOptimizationClient must NOT be registered post-E27S01")
                .isEmpty();
    }
}
