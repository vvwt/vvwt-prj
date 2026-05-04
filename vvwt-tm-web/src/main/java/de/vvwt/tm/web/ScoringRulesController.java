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
 * REST endpoint exposing available scoring rule/strategy options (E21S10 + E21S20 rename,
 * AC-REST-SLICE-ScoringRulesController, AC-REST-IT-HAPPY-ScoringRulesController,
 * AC-REST-IT-SEC-ScoringRulesController, inventory row 412).
 *
 * <p>Renamed from {@code TournamentRulesController} to {@code ScoringRulesController} at E21S20
 * (DEC-40 Clause A DDD-ownership — this controller serves scoring rules, not tournament lifecycle).
 * URL updated from {@code /api/tournament-rules} to {@code /api/scoring/rules} (E22S08 D-2 Option B
 * atomic-cutover; see AC-FRONTEND-URL-UPDATE-ATOMIC for the coordinated Svelte update).
 *
 * <p>Moved from {@code de.vvwt.tm.tournament.TournamentRulesController} to {@code
 * de.vvwt.tm.web.TournamentRulesController} at the E22S11 atomic cutover (DEC-40
 * Primary-Adapter-Isolation — REST controllers MUST reside in {@code web.*}). The previous location
 * in the {@code tournament} module created a bidirectional Spring Modulith dependency cycle: {@code
 * tournament → scoring → tournament} (via {@link ScoringRuleRegistry} and {@link
 * SetValidationRuleRegistry} imports). Placing the controller in {@code web} eliminates the cycle,
 * as the {@code web} module already declares dependencies on both {@code tournament} and {@code
 * scoring}.
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
 * @see MatchGeneratorRegistry
 * @see de.vvwt.tm.scoring.ScoringRuleRegistry
 * @see de.vvwt.tm.scoring.SetValidationRuleRegistry
 * @see <a href="DEC-21">DEC-21 — Spring Modulith boundary rules</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation + DDD-ownership</a>
 * @see <a href="E21S10">E21S10 — inventory row 412</a>
 * @see <a href="E22S11">E22S11 — atomic cutover, controller relocated to web.*</a>
 * @see <a href="E21S20">E21S20 — class+URL rename TournamentRules → ScoringRules</a>
 */
@RestController("tmScoringRulesController")
@RequestMapping("/api/scoring/rules")
public class ScoringRulesController {

    private final ScoringRuleRegistry scoringRuleRegistry;
    private final SetValidationRuleRegistry setValidationRuleRegistry;

    /** S08 public-package {@link MatchGeneratorRegistry} (AC-S08-REGISTRY-IMPORT). */
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
