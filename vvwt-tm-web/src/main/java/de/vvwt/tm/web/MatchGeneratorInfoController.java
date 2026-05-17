// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import de.vvwt.tm.tournament.MatchGeneratorInfo;
import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint exposing the registered match generators as {@link MatchGeneratorInfo} records (key
 * ID + {@code isLastPhaseGenerator} capability flag) for the frontend phase-plan UI.
 *
 * <h2>Response shape (Pattern A — DEC-40 Clause B)</h2>
 *
 * <p>The projection the frontend needs is exactly the two components of the {@link
 * MatchGeneratorInfo} record ({@code keyId}, {@code isLastPhaseGenerator}) — no field omission, no
 * aliasing, no cross-context aggregation. Per DEC-40 Clause B Pattern A, the endpoint serializes
 * the bounded-context-owned record directly; no web-tier DTO is introduced.
 *
 * <pre>{@code
 * GET /api/match-generators
 * [
 *   { "keyId": "roundRobin",    "isLastPhaseGenerator": false },
 *   { "keyId": "awardCeremony", "isLastPhaseGenerator": true  }
 * ]
 * }</pre>
 *
 * @see MatchGeneratorRegistry
 * @see MatchGeneratorInfo
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation + Clause B Pattern A</a>
 * @see <a href="DEC-73">DEC-73 — D-5 isLastPhaseGenerator capability flag</a>
 * @see <a href="E58S05">E58S05 — AC1 governance + AC8 TDD</a>
 */
@RestController("tmMatchGeneratorInfoController")
@RequestMapping("/api/match-generators")
public class MatchGeneratorInfoController {

    private final MatchGeneratorRegistry matchGeneratorRegistry;

    public MatchGeneratorInfoController(MatchGeneratorRegistry matchGeneratorRegistry) {
        this.matchGeneratorRegistry = matchGeneratorRegistry;
    }

    /**
     * Returns all registered match generators as {@link MatchGeneratorInfo} records.
     *
     * @return 200 OK with the list of generator info records; never null; may be empty
     */
    @GetMapping
    public ResponseEntity<List<MatchGeneratorInfo>> getMatchGenerators() {
        return ResponseEntity.ok(matchGeneratorRegistry.getGeneratorInfoList());
    }
}
