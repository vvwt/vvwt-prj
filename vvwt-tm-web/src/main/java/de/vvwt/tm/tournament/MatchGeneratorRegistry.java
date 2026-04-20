package de.vvwt.tm.tournament;

import de.vvwt.tm.tournament.internal.MatchGenerator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Boundary-API Spring bean registry for {@link MatchGenerator} strategies (E21S08
 * reconstruction-in-place).
 *
 * <p>Reconstruction-in-place counterpart of {@code
 * de.vvwt.tm.domain.generator.MatchGeneratorRegistry} (inventory row 257). Lives at the Modulith
 * ROOT package {@code de.vvwt.tm.tournament} per DEC-21 § Module layout — it is a boundary-API type
 * exposed via the existing {@code TournamentRulesController} (E21S02/S10).
 *
 * <p>Spring collects every {@link MatchGenerator} bean in the application context and passes them
 * to this constructor. The registry indexes them by {@link MatchGenerator#getBeanId()}. Duplicate
 * bean IDs throw {@link IllegalStateException} at startup — a configuration error.
 *
 * <p>Legacy {@code de.vvwt.tm.domain.generator.MatchGeneratorRegistry} remains untouched until
 * E21S13 atomic cutover per DEC-32. During the parallel-development phase, this bean coexists with
 * the legacy registry.
 *
 * @see MatchGenerator
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (reconstruction-in-place)</a>
 * @see <a href="E21S08">E21S08 — inventory row 257</a>
 */
@Component("tmMatchGeneratorRegistry")
public class MatchGeneratorRegistry {

    private final Map<String, MatchGenerator> generatorsByBeanId;

    /**
     * Constructs the registry from all {@link MatchGenerator} beans discovered by Spring.
     *
     * @param generators all beans in the application context that implement {@link MatchGenerator}
     * @throws IllegalStateException if two generators share the same bean ID
     */
    public MatchGeneratorRegistry(List<MatchGenerator> generators) {
        this.generatorsByBeanId =
                generators.stream()
                        .collect(
                                Collectors.toMap(
                                        MatchGenerator::getBeanId,
                                        Function.identity(),
                                        (a, b) -> {
                                            throw new IllegalStateException(
                                                    "Duplicate MatchGenerator bean ID: '"
                                                            + a.getBeanId()
                                                            + "'. Each generator must have a unique"
                                                            + " ID.");
                                        }));
    }

    /**
     * Returns the {@link MatchGenerator} registered under the given bean ID.
     *
     * @param beanId the registry key to look up (e.g., {@code "roundRobinNew"})
     * @return the matching generator (never null)
     * @throws IllegalArgumentException if no generator is registered under {@code beanId}
     */
    public MatchGenerator get(String beanId) {
        MatchGenerator generator = generatorsByBeanId.get(beanId);
        if (generator == null) {
            throw new IllegalArgumentException(
                    "No MatchGenerator with id '"
                            + beanId
                            + "' — known ids: "
                            + String.join(
                                    ", ", new java.util.TreeSet<>(generatorsByBeanId.keySet())));
        }
        return generator;
    }

    /**
     * Returns an unmodifiable snapshot of all registered bean IDs.
     *
     * @return immutable set of known IDs; never null
     */
    public Set<String> knownIds() {
        return Collections.unmodifiableSet(generatorsByBeanId.keySet());
    }

    /**
     * Returns an unmodifiable view of the full registry map.
     *
     * @return immutable map from bean ID to generator instance; never null
     */
    public Map<String, MatchGenerator> getAll() {
        return Collections.unmodifiableMap(generatorsByBeanId);
    }
}
