package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tournament.LanHostDetector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression-guard integration test — verifies that the {@link TournamentManagerApplication} Spring
 * context loads and the {@link LanHostDetector} / {@link DefaultLanHostDetector} bean is
 * instantiated successfully (E49S05 AC-TEST-REGRESSION-GUARD).
 *
 * <h2>Regression rationale</h2>
 *
 * <p>E49S04 introduced {@link DefaultLanHostDetector} with two constructors and no
 * {@code @Autowired} annotation. Spring Boot 4.0.5 fell back to a non-existent no-arg constructor →
 * {@code BeanCreationException: No default constructor found} → entire application context failed
 * to load → 20 errors across 4 {@code @SpringBootTest} classes. This test was RED before the E49S05
 * fix (the context loaded but the bean could not be created) and GREEN after (single production
 * constructor; Spring implicit injection succeeds).
 *
 * <h2>IT annotation</h2>
 *
 * <p>Uses {@code @SpringBootTest(classes = TournamentManagerApplication.class)} per DEC-44 and the
 * project's established pattern for full-context wiring tests (c.f. {@link
 * de.vvwt.tm.slotopt.RoutingSlotOptimizationClientWiringIT}).
 *
 * @see DefaultLanHostDetector
 * @see LanHostDetector
 * @see DefaultLocalAddressSupplier
 * @see <a href="../../../../../../../../docs/governance/stories/E49S05.story.md">Story E49S05</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22</a>
 */
@SpringBootTest(classes = de.vvwt.tm.TournamentManagerApplication.class)
@ActiveProfiles("test")
class LanHostDetectorContextIT {

    /** The {@link LanHostDetector} bean wired by the full application context. */
    @Autowired private LanHostDetector lanHostDetector;

    /**
     * AC-TEST-REGRESSION-GUARD: the Spring context loads and the {@link LanHostDetector} bean is
     * instantiated (i.e. {@link DefaultLanHostDetector} is wired via its single production
     * constructor with {@link TmPublicHostProperties} and {@link DefaultLocalAddressSupplier}).
     *
     * <p>This test is RED before the E49S05 fix (context fails to load with {@code
     * BeanCreationException: No default constructor found}) and GREEN after the fix.
     */
    @Test
    void contextLoads_lanHostDetectorBeanIsInstantiable() {
        assertThat(lanHostDetector)
                .as("LanHostDetector bean must be instantiated by the Spring context")
                .isNotNull();
    }

    /**
     * AC-FIX-CONTEXT-LOADS / AC-FIX-NO-BEHAVIOUR-CHANGE: the wired bean returns a non-null,
     * non-blank host value (the production resolution logic is intact after the structural fix).
     */
    @Test
    void detectHost_returnsNonBlankValue_afterFix() {
        String host = lanHostDetector.detectHost();
        assertThat(host)
                .as("detectHost() must return a non-blank host string after the E49S05 fix")
                .isNotNull()
                .isNotBlank();
    }
}
