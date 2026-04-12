package de.vvwt.tm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Tournament Manager V1 application entry point.
 *
 * <p>This class is a scaffold placeholder for E02S01. It provides the minimal
 * {@code @SpringBootApplication} entry point required so that E02S02 can add
 * persistence wiring (H2 + Flyway) without structural changes.
 *
 * <p>No beans, no data sources, no Flyway configuration — those belong to E02S02.
 *
 * @see <a href="../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E02S01.story.md">Story E02S01</a>
 */
@SpringBootApplication
public class TournamentManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TournamentManagerApplication.class, args);
    }
}
