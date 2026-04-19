package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.generator.MatchGeneratorRegistry;
import de.vvwt.tm.domain.rules.ScoringRuleRegistry;
import de.vvwt.tm.domain.rules.SetValidationRuleRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint that exposes available tournament rule/strategy options (AC8 — E05S04).
 *
 * <p>The Svelte form uses this endpoint to populate dropdowns for:
 *
 * <ul>
 *   <li>Scoring rules (bean IDs registered in {@link ScoringRuleRegistry})
 *   <li>Set validation rules (bean IDs registered in {@link SetValidationRuleRegistry})
 *   <li>Match generators (bean IDs registered in {@link MatchGeneratorRegistry})
 *   <li>Match formats (all {@link MatchFormat} enum constants)
 * </ul>
 *
 * <p>By introspecting the live Spring bean registry, this endpoint automatically includes any
 * future rule/generator implementations without requiring frontend changes (Story notes).
 *
 * <h2>Response shape</h2>
 *
 * <pre>{@code
 * {
 *   "scoringRuleIds":       ["setPoints", "threePointMatch", "twoPointMatch"],
 *   "setValidationRuleIds": ["standardVolleyball", "timeBoundedSet"],
 *   "matchGeneratorIds":    ["roundRobin"],
 *   "matchFormats":         ["BEST_OF_1", "BEST_OF_3", "BEST_OF_5", "BEST_OF_7", "FIXED_2_SETS"]
 * }
 * }</pre>
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S04.story.md">Story
 *     E05S04</a>
 */
@RestController
@RequestMapping("/api/tournament-rules")
public class TournamentRulesController {

    private final ScoringRuleRegistry scoringRuleRegistry;
    private final SetValidationRuleRegistry setValidationRuleRegistry;
    private final MatchGeneratorRegistry matchGeneratorRegistry;

    public TournamentRulesController(
            ScoringRuleRegistry scoringRuleRegistry,
            SetValidationRuleRegistry setValidationRuleRegistry,
            MatchGeneratorRegistry matchGeneratorRegistry) {
        this.scoringRuleRegistry = scoringRuleRegistry;
        this.setValidationRuleRegistry = setValidationRuleRegistry;
        this.matchGeneratorRegistry = matchGeneratorRegistry;
    }

    /**
     * Returns all available rule and format options for tournament creation/editing.
     *
     * @return 200 OK with a JSON object containing four sorted lists
     */
    @GetMapping
    public ResponseEntity<Map<String, List<String>>> getTournamentRules() {
        // Use sorted sets for deterministic ordering — important for frontend dropdowns
        // and test assertions.
        List<String> scoringRuleIds =
                new TreeSet<>(scoringRuleRegistry.knownIds()).stream().toList();
        List<String> setValidationRuleIds =
                new TreeSet<>(setValidationRuleRegistry.getAll().keySet()).stream().toList();
        List<String> matchGeneratorIds =
                new TreeSet<>(matchGeneratorRegistry.knownIds()).stream().toList();
        List<String> matchFormats =
                Arrays.stream(MatchFormat.values()).map(MatchFormat::name).toList();

        return ResponseEntity.ok(
                Map.of(
                        "scoringRuleIds", scoringRuleIds,
                        "setValidationRuleIds", setValidationRuleIds,
                        "matchGeneratorIds", matchGeneratorIds,
                        "matchFormats", matchFormats));
    }
}
