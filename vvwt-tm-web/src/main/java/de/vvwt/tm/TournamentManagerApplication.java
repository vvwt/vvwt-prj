package de.vvwt.tm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;

/**
 * Tournament Manager V1 application entry point.
 *
 * <p>This class is a scaffold placeholder for E02S01. It provides the minimal
 * {@code @SpringBootApplication} entry point required so that E02S02 can add persistence wiring (H2
 * + Flyway) without structural changes.
 *
 * <p>No beans, no data sources, no Flyway configuration — those belong to E02S02.
 *
 * <h2>Dual-bean-boot exclusion (AC-COMPONENT-SCAN-EXCLUSION / E22S04, extended E22S05, E22S10)</h2>
 *
 * <p>During the reconstruction-in-place transitional state (DEC-21, DEC-22), the legacy {@code
 * de.vvwt.tm.domain.rules.*} classes and the new {@code de.vvwt.tm.scoring.*} / {@code
 * de.vvwt.tm.scoring.internal.*} classes carry conflicting {@code @Component} names. Without
 * exclusion, Spring throws {@code ConflictingBeanDefinitionException} at boot time.
 *
 * <p><b>E22S04 (5 concrete rule classes):</b> {@code SetPointsRule}, {@code ThreePointMatchRule},
 * {@code TwoPointMatchRule}, {@code StandardVolleyballSet}, {@code TimeBoundedSet} — excluded
 * because the new {@code scoring.internal.*} beans carry identical {@code @Component} names.
 *
 * <p><b>E22S05 (no additional exclusions):</b> The new {@code scoring.ScoringRuleRegistry}, {@code
 * scoring.SetValidationRuleRegistry}, and {@code scoring.internal.TournamentRuleResolver} are
 * registered under qualified names ({@code "scoringModuleScoringRuleRegistry"}, {@code
 * "scoringModuleSetValidationRuleRegistry"}, {@code "scoringTournamentRuleResolver"}) to coexist
 * with the legacy {@code domain.rules.*} beans during the reconstruction-in-place window. The
 * legacy beans remain registered and are consumed by {@code TournamentRulesController} and {@code
 * DefaultScoringService} per DEC-32 transitional import contract until E22S11 cutover. No
 * additional exclusions are added by E22S05 — the new beans use type-distinct qualified names.
 *
 * <p><b>E22S10 (legacy ScoreController):</b> The new {@code de.vvwt.tm.web.ScoreController} and the
 * legacy {@code de.vvwt.tm.infrastructure.score.ScoreController} both register
 * {@code @RequestMapping("/score")}, which causes Spring to throw {@code IllegalStateException:
 * Ambiguous mapping} at startup. The legacy class is excluded here so the new class is the sole
 * registered bean. The legacy class remains on the classpath (not deleted) until the E22S11 atomic
 * cutover. The legacy {@code ScoreControllerTest} (unit test) instantiates the class directly and
 * is unaffected by this exclusion. The legacy {@code ScoreControllerIT} (integration test) makes
 * HTTP calls that are now served by the new controller with byte-equivalent URL mappings and
 * Mustache templates — it remains GREEN.
 *
 * <p>The explicit {@code @ComponentScan} annotation on this class overrides the embedded scan in
 * {@code @SpringBootApplication}; {@link TypeExcludeFilter} is therefore re-added explicitly to
 * preserve Spring Boot's test-class exclusion semantics (prevents {@code @SpringBootTest} inner
 * configuration classes from being treated as production beans during test runs). This is a
 * structural exclusion, not a runtime conditional; explicitly permitted by DEC-21.
 *
 * <p>All exclusions MUST be removed at the E22S11 cutover when all legacy classes are deleted.
 *
 * @see <a
 *     href="../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E02S01.story.md">Story
 *     E02S01</a>
 */
@SpringBootApplication
@ComponentScan(
        excludeFilters = {
            @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @Filter(
                    type = FilterType.REGEX,
                    pattern =
                            "de\\.vvwt\\.tm\\.domain\\.rules\\."
                                    + "(SetPointsRule|ThreePointMatchRule|TwoPointMatchRule"
                                    + "|StandardVolleyballSet|TimeBoundedSet)"),
            @Filter(
                    type = FilterType.REGEX,
                    pattern = "de\\.vvwt\\.tm\\.infrastructure\\.score\\.ScoreController")
        })
public class TournamentManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TournamentManagerApplication.class, args);
    }
}
