package de.vvwt.tm.domain.generator;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spring bean registry that auto-injects all {@link MatchGenerator} beans and exposes
 * a lookup-by-bean-ID API (AC6).
 *
 * <p>Spring collects every bean implementing {@link MatchGenerator} and passes them to this
 * constructor. The registry indexes them by their {@link MatchGenerator#getBeanId()} return value.
 * Duplicate IDs produce an {@link IllegalStateException} at startup — a configuration error.
 *
 * <p>Usage in the phase-preparation service (E03S12):
 * <pre>{@code
 *   MatchGenerator gen = registry.get(tournament.getMatchGeneratorId());
 * }</pre>
 *
 * @see MatchGenerator
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S09.story.md">Story E03S09</a>
 */
@Component
public class MatchGeneratorRegistry {

    private final Map<String, MatchGenerator> generatorsByBeanId;

    /**
     * Constructs the registry from all {@link MatchGenerator} beans discovered by Spring.
     *
     * @param generators all beans in the application context that implement {@link MatchGenerator}
     * @throws IllegalStateException if two generators share the same bean ID
     */
    public MatchGeneratorRegistry(List<MatchGenerator> generators) {
        this.generatorsByBeanId = generators.stream()
                .collect(Collectors.toMap(
                        MatchGenerator::getBeanId,
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate MatchGenerator bean ID: '" + a.getBeanId()
                                    + "'. Each generator must have a unique ID.");
                        }));
    }

    /**
     * Returns the {@link MatchGenerator} registered under the given bean ID.
     *
     * @param beanId the Spring bean ID to look up (e.g., {@code "roundRobin"})
     * @return the matching {@link MatchGenerator} (never null)
     * @throws IllegalArgumentException if no generator is registered under {@code beanId},
     *                                  with the list of known IDs in the message
     */
    public MatchGenerator get(String beanId) {
        MatchGenerator generator = generatorsByBeanId.get(beanId);
        if (generator == null) {
            throw new IllegalArgumentException(
                    "No MatchGenerator bean with id '" + beanId
                    + "' — known ids: " + String.join(", ", generatorsByBeanId.keySet()));
        }
        return generator;
    }

    /**
     * Returns an unmodifiable snapshot of all registered bean IDs.
     *
     * @return immutable set of known IDs
     */
    public Set<String> knownIds() {
        return Collections.unmodifiableSet(generatorsByBeanId.keySet());
    }

    /**
     * Returns an unmodifiable view of the full registry map.
     * Useful for diagnostics.
     *
     * @return immutable map from bean ID to generator instance
     */
    public Map<String, MatchGenerator> getAll() {
        return Collections.unmodifiableMap(generatorsByBeanId);
    }
}
