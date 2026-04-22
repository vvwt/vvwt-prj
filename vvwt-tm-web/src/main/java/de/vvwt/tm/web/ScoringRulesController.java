package de.vvwt.tm.web;

import de.vvwt.tm.scoring.ScoringRuleRegistry;
import de.vvwt.tm.scoring.SetValidationRuleRegistry;
import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint exposing available tournament rule/strategy options.
 *
 * <p>Relocated whole-class from {@code de.vvwt.tm.tournament.TournamentRulesController} into the
 * {@code de.vvwt.tm.web} Primary-Adapter-Isolation module per DEC-40 Clause A (Q-1b refactor,
 * DEC-22 §refactor-clause). Class renamed to {@code ScoringRulesController}; URL path renamed from
 * {@code /api/tournament-rules} to {@code /api/scoring/rules} per AC-S08-PATH-DECISION-B (D-2 DDD
 * alignment — rules are a scoring concern).
 *
 * <p>Registry imports re-pointed from legacy {@code de.vvwt.tm.domain.rules.*} to {@code
 * de.vvwt.tm.scoring.*} (scoring public surface, delivered by E22S05) per AC-S08-REGISTRY-REPOINT
 * (D-13). This closes the last {@code tournament→domain.rules} import surface that E22S11 will
 * delete.
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
 * @see ScoringRuleRegistry
 * @see SetValidationRuleRegistry
 * @see MatchGeneratorRegistry
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law, Q-1b refactor-clause</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-38">DEC-38 — @ApplicationModuleTest canon</a>
 * @see <a href="E22S08">E22S08 — relocate + rename + re-point scoring registries</a>
 */
@RestController("tmScoringRulesController")
@RequestMapping("/api/scoring/rules")
public class ScoringRulesController {

    private final ScoringRuleRegistry scoringRuleRegistry;
    private final SetValidationRuleRegistry setValidationRuleRegistry;
    private final MatchGeneratorRegistry matchGeneratorRegistry;

    public ScoringRulesController(
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
    public ResponseEntity<Map<String, List<String>>> getScoringRules() {
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
