// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.MatchGenerator;
import de.vvwt.tm.tournament.MatchGeneratorInfo;
import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link MatchGeneratorRegistry}.
 *
 * <p>Boundary-API Spring bean registry for {@link MatchGenerator} strategies (E21S08
 * reconstruction-in-place). Lives at the Modulith ROOT package {@code de.vvwt.tm.tournament} per
 * DEC-21 § Module layout — it is a boundary-API type exposed via the existing {@code
 * TournamentRulesController} (E21S02/S10).
 *
 * <p>Spring collects every {@link MatchGenerator} bean in the application context and passes them
 * to this constructor. The registry indexes them by {@link MatchGenerator#getKeyId()}. Duplicate
 * key IDs throw {@link IllegalStateException} at startup — a configuration error.
 *
 * <p>Legacy {@code de.vvwt.tm.domain.generator.MatchGeneratorRegistry} remains untouched until
 * E21S13 atomic cutover per DEC-32.
 *
 * @see MatchGenerator
 * @since E57S01 (DEC-58/DEC-72 interface extraction: renamed from MatchGeneratorRegistry, moved to
 *     tournament.internal, implements {@link MatchGeneratorRegistry})
 */
@Component("tmMatchGeneratorRegistry")
class DefaultMatchGeneratorRegistry implements MatchGeneratorRegistry {

    private final Map<String, MatchGenerator> generatorsByKeyId;
    private final List<MatchGeneratorInfo> generatorInfoList;

    /**
     * Constructs the registry from all {@link MatchGenerator} beans discovered by Spring.
     *
     * <p>Updated by E58S01: indexes by {@link MatchGenerator#getKeyId()} (renamed from {@code
     * getBeanId()}); builds the immutable {@link MatchGeneratorInfo} list eagerly.
     *
     * @param generators all beans in the application context that implement {@link MatchGenerator}
     * @throws IllegalStateException if two generators share the same key ID
     */
    DefaultMatchGeneratorRegistry(List<MatchGenerator> generators) {
        this.generatorsByKeyId =
                generators.stream()
                        .collect(
                                Collectors.toMap(
                                        MatchGenerator::getKeyId,
                                        Function.identity(),
                                        (a, b) -> {
                                            throw new IllegalStateException(
                                                    "Duplicate MatchGenerator key ID: '"
                                                            + a.getKeyId()
                                                            + "'. Each generator must have a unique"
                                                            + " ID.");
                                        }));
        this.generatorInfoList =
                generators.stream()
                        .map(g -> new MatchGeneratorInfo(g.getKeyId(), g.isLastPhaseGenerator()))
                        .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
    }

    /** {@inheritDoc} */
    @Override
    public MatchGenerator get(String beanId) {
        MatchGenerator generator = generatorsByKeyId.get(beanId);
        if (generator == null) {
            throw new IllegalArgumentException(
                    "No MatchGenerator with id '"
                            + beanId
                            + "' — known ids: "
                            + String.join(
                                    ", ", new java.util.TreeSet<>(generatorsByKeyId.keySet())));
        }
        return generator;
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> knownIds() {
        return Collections.unmodifiableSet(generatorsByKeyId.keySet());
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, MatchGenerator> getAll() {
        return Collections.unmodifiableMap(generatorsByKeyId);
    }

    /** {@inheritDoc} */
    @Override
    public List<MatchGeneratorInfo> getGeneratorInfoList() {
        return generatorInfoList;
    }
}
