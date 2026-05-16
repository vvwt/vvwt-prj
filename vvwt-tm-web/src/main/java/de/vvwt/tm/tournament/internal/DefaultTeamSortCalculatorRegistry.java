package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.TeamSortCalculator;
import de.vvwt.tm.tournament.TeamSortCalculatorRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link TeamSortCalculatorRegistry}.
 *
 * <p>Spring collects every {@link TeamSortCalculator} bean in the application context and passes
 * them to this constructor. The registry indexes them by {@link TeamSortCalculator#getKeyId()}.
 * Duplicate key IDs throw {@link IllegalStateException} at startup — a configuration error.
 *
 * <p>Known keys at startup: {@code "team_number"} ({@link TeamNumberSortCalculator}), {@code
 * "placement_group"} ({@link PlacementGroupSortCalculator}), {@code "group_placement"} ({@link
 * GroupPlacementSortCalculator}).
 *
 * <p>The E57S04 build-time interface-mandate guard ({@code InterfaceMandateGuardTest}) passes
 * because this class implements the first-party interface {@link TeamSortCalculatorRegistry} in the
 * bounded-context root package (DEC-58 Clause A + DEC-72).
 *
 * @see TeamSortCalculator
 * @see TeamSortCalculatorRegistry
 * @see <a href="DEC-35">DEC-35 — impl in .internal</a>
 * @see <a href="DEC-73">DEC-73 D-3 — TeamSortCalculatorRegistry</a>
 * @see <a href="E58S03">E58S03 — AC2</a>
 */
@Component("tmTeamSortCalculatorRegistry")
public class DefaultTeamSortCalculatorRegistry implements TeamSortCalculatorRegistry {

    private final Map<String, TeamSortCalculator> calculatorsByKey;

    /**
     * Constructs the registry from all {@link TeamSortCalculator} beans discovered by Spring.
     *
     * @param calculators all beans in the application context that implement {@link
     *     TeamSortCalculator}
     * @throws IllegalStateException if two calculators share the same key ID
     */
    public DefaultTeamSortCalculatorRegistry(List<TeamSortCalculator> calculators) {
        this.calculatorsByKey =
                calculators.stream()
                        .collect(
                                Collectors.toMap(
                                        TeamSortCalculator::getKeyId,
                                        Function.identity(),
                                        (a, b) -> {
                                            throw new IllegalStateException(
                                                    "Duplicate TeamSortCalculator key ID: '"
                                                            + a.getKeyId()
                                                            + "'. Each calculator must have a"
                                                            + " unique key.");
                                        }));
    }

    /** {@inheritDoc} */
    @Override
    public TeamSortCalculator get(String key) {
        TeamSortCalculator calculator = calculatorsByKey.get(key);
        if (calculator == null) {
            throw new IllegalArgumentException(
                    "No TeamSortCalculator with key '"
                            + key
                            + "' — known keys: "
                            + String.join(", ", new java.util.TreeSet<>(calculatorsByKey.keySet()))
                            + " (E58S03)");
        }
        return calculator;
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> knownKeys() {
        return Collections.unmodifiableSet(calculatorsByKey.keySet());
    }
}
