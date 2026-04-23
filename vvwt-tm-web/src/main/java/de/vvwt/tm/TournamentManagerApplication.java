package de.vvwt.tm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
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
 * <h2>E23S01 — Parallel-phase bean coexistence (AC-PARALLEL-COEXISTENCE)</h2>
 *
 * <p>During the parallel phase (until E23S05 Cutover-1), both the legacy {@code
 * de.vvwt.tm.domain.photo.*} package and the new {@code de.vvwt.tm.photo.*} module coexist on the
 * classpath. The potential {@code ConflictingBeanDefinitionException} for the shared {@code
 * photoStorageConfig} bean name is resolved by qualifying the new module's config bean as {@code
 * photoModuleStorageConfig} (see {@link de.vvwt.tm.photo.PhotoStorageConfig}). No explicit
 * {@code @ComponentScan} exclusion is needed — the new {@code DefaultPhotoStorageService} and the
 * legacy {@code PhotoStorageServiceImpl} have distinct bean names and implement DIFFERENT Java
 * types ({@code de.vvwt.tm.photo.PhotoStorageService} vs. {@code
 * de.vvwt.tm.domain.photo.PhotoStorageService}), so they do not conflict.
 *
 * <h2>Post-E22S11 — @ComponentScan exclusions removed</h2>
 *
 * <p>The transitional {@code @ComponentScan(excludeFilters = ...)} annotation introduced in
 * E22S04/E22S05 (5 domain.rules rule beans) and E22S10 (legacy
 * infrastructure.score.ScoreController) is removed here. With the E22S11 atomic cutover, all legacy
 * {@code domain.rules.*} and {@code infrastructure.score.*} classes are deleted from the classpath.
 * The duplicate-bean and ambiguous-mapping hazards that required the exclusions no longer exist.
 *
 * <h2>E23S04 — Parallel-phase URL-mapping coexistence (AC-URL-PRESERVATION-GREPVERIFY)</h2>
 *
 * <p>After the E23S04 relocation, both the legacy {@code
 * de.vvwt.tm.infrastructure.web.photo.TeamPhotoController} and the new {@code
 * de.vvwt.tm.web.photo.TeamPhotoController} are {@code @RestController} beans that register
 * identical URL mappings ({@code /api/tournaments/{tid}/teams/{teamId}/photo}). Spring Boot would
 * throw at startup due to ambiguous URL mapping. Resolution: exclude the legacy controller from the
 * component scan during the parallel phase via explicit {@code @ComponentScan(excludeFilters)}. The
 * legacy class REMAINS on disk (AC-LEGACY-DELETION-DEFERRED); it is excluded here, not deleted. The
 * exclude filter is REMOVED atomically at E23S05 Cutover-1 when the legacy class is deleted.
 *
 * <p>Note: The explicit {@code @ComponentScan} here includes {@link TypeExcludeFilter} in its
 * {@code excludeFilters} to preserve Spring Boot's test-class exclusion mechanism. Without {@code
 * TypeExcludeFilter}, duplicate {@code @TestConfiguration} inner-class bean definitions from
 * different test packages would conflict during {@code @SpringBootTest} context loading
 * (E22S04/E22S05 precedent).
 *
 * @see de.vvwt.tm.web.photo.TeamPhotoController
 * @see <a
 *     href="../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E02S01.story.md">Story
 *     E02S01</a>
 */
@SpringBootApplication
@ComponentScan(
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = "de\\.vvwt\\.tm\\.infrastructure\\.web\\.photo\\..*")
        })
public class TournamentManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TournamentManagerApplication.class, args);
    }
}
