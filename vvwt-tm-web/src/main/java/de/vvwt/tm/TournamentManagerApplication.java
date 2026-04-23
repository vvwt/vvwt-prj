package de.vvwt.tm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Tournament Manager V1 application entry point.
 *
 * <p>This class is a scaffold placeholder for E02S01. It provides the minimal
 * {@code @SpringBootApplication} entry point required so that E02S02 can add persistence wiring (H2
 * + Flyway) without structural changes.
 *
 * <p>No beans, no data sources, no Flyway configuration — those belong to E02S02.
 *
 * <h2>Post-E22S11 — @ComponentScan exclusions removed</h2>
 *
 * <p>The transitional {@code @ComponentScan(excludeFilters = ...)} annotation introduced in
 * E22S04/E22S05 (5 domain.rules rule beans) and E22S10 (legacy
 * infrastructure.score.ScoreController) is removed here. With the E22S11 atomic cutover, all legacy
 * {@code domain.rules.*} and {@code infrastructure.score.*} classes are deleted from the classpath.
 * The duplicate-bean and ambiguous-mapping hazards that required the exclusions no longer exist.
 * {@code @SpringBootApplication} provides the default component scan with {@code TypeExcludeFilter}
 * active via its embedded {@code @ComponentScan} — no explicit override is needed.
 *
 * @see <a
 *     href="../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E02S01.story.md">Story
 *     E02S01</a>
 */
@SpringBootApplication
public class TournamentManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TournamentManagerApplication.class, args);
    }
}
