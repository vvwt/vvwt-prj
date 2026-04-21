package de.vvwt.tm.tournament;

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
 * REST endpoint exposing available tournament rule/strategy options (E21S10,
 * AC-REST-SLICE-TournamentRulesController, AC-REST-IT-HAPPY-TournamentRulesController,
 * AC-REST-IT-SEC-TournamentRulesController, inventory row 412).
 *
 * <p>Reconstruction-in-place counterpart of {@link
 * de.vvwt.tm.infrastructure.web.TournamentRulesController}. Placed at {@code
 * de.vvwt.tm.tournament.TournamentRulesController} (public boundary-API per DEC-21 D-8 and
 * conventions.md (e)).
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
 * <h2>DEC-32 — transitional scoring-rule-registry imports</h2>
 *
 * <p>{@link MatchGeneratorRegistry} is imported from {@code de.vvwt.tm.tournament.*} (S08 public
 * package — AC-S08-REGISTRY-IMPORT). The scoring/validation registries ({@link ScoringRuleRegistry}
 * and {@link SetValidationRuleRegistry}) are imported from their LEGACY coordinates ({@code
 * de.vvwt.tm.domain.rules.*}) — this is the DEC-32 permitted transitional import. These will be
 * mechanically rewired to {@code scoring::api} at E22 cutover.
 *
 * <h2>URL mapping (parallel-phase discipline)</h2>
 *
 * <p>Mapped to {@code /api/tournament-rules} during reconstruction-in-place to avoid ambiguous
 * mapping with the legacy {@code /api/tournament-rules} endpoint. At E21S13 atomic cutover, the
 * mapping is normalized to {@code /api/tournament-rules}. Named {@code
 * "tmTournamentRulesController"} to avoid Spring bean name collision with the legacy controller.
 *
 * @see MatchGeneratorRegistry
 * @see de.vvwt.tm.domain.rules.ScoringRuleRegistry legacy import — DEC-32 transitional
 * @see de.vvwt.tm.domain.rules.SetValidationRuleRegistry legacy import — DEC-32 transitional
 * @see de.vvwt.tm.infrastructure.web.TournamentRulesController legacy counterpart (untouched)
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-32">DEC-32 — transitional scoring-rule-registry import</a>
 * @see <a href="E21S10">E21S10 — inventory row 412</a>
 */
@RestController("tmTournamentRulesController")
@RequestMapping("/api/tournament-rules")
public class TournamentRulesController {

    private final ScoringRuleRegistry scoringRuleRegistry;
    private final SetValidationRuleRegistry setValidationRuleRegistry;

    /** S08 public-package {@link MatchGeneratorRegistry} (AC-S08-REGISTRY-IMPORT). */
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
