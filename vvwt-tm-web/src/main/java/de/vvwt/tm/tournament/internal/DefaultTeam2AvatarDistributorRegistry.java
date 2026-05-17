// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Team2AvatarDistributor;
import de.vvwt.tm.tournament.Team2AvatarDistributorRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link Team2AvatarDistributorRegistry}.
 *
 * <p>Spring collects every {@link Team2AvatarDistributor} bean in the application context and
 * passes them to this constructor. The registry indexes them by {@link
 * Team2AvatarDistributor#getKeyId()}. Duplicate key IDs throw {@link IllegalStateException} at
 * startup — a configuration error.
 *
 * @see Team2AvatarDistributor
 * @see Team2AvatarDistributorRegistry
 * @see <a href="DEC-35">DEC-35 — impl in .internal</a>
 * @see <a href="DEC-73">DEC-73 D-2 — Team2AvatarDistributorRegistry</a>
 * @see <a href="E58S02">E58S02 — AC2</a>
 */
@Component("tmTeam2AvatarDistributorRegistry")
public class DefaultTeam2AvatarDistributorRegistry implements Team2AvatarDistributorRegistry {

    private final Map<String, Team2AvatarDistributor> distributorsByKey;

    /**
     * Constructs the registry from all {@link Team2AvatarDistributor} beans discovered by Spring.
     *
     * @param distributors all beans in the application context that implement {@link
     *     Team2AvatarDistributor}
     * @throws IllegalStateException if two distributors share the same key ID
     */
    public DefaultTeam2AvatarDistributorRegistry(List<Team2AvatarDistributor> distributors) {
        this.distributorsByKey =
                distributors.stream()
                        .collect(
                                Collectors.toMap(
                                        Team2AvatarDistributor::getKeyId,
                                        Function.identity(),
                                        (a, b) -> {
                                            throw new IllegalStateException(
                                                    "Duplicate Team2AvatarDistributor key ID: '"
                                                            + a.getKeyId()
                                                            + "'. Each distributor must have a"
                                                            + " unique key.");
                                        }));
    }

    /** {@inheritDoc} */
    @Override
    public Team2AvatarDistributor get(String key) {
        Team2AvatarDistributor distributor = distributorsByKey.get(key);
        if (distributor == null) {
            throw new IllegalArgumentException(
                    "No Team2AvatarDistributor with key '"
                            + key
                            + "' — known keys: "
                            + String.join(", ", new java.util.TreeSet<>(distributorsByKey.keySet()))
                            + " (E58S02)");
        }
        return distributor;
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> knownKeys() {
        return Collections.unmodifiableSet(distributorsByKey.keySet());
    }
}
