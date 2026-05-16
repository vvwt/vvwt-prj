package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchGenerator;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.TeamAvatar;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * No-op {@link MatchGenerator} for the {@code gameMode=siegerehrung} phase type (E48S02, Path-i per
 * Brief O-15).
 *
 * <p>A Siegerehrung phase does not generate matches — it is a ceremony phase. This bean registers
 * under the key {@code "siegerehrung"} in the {@link de.vvwt.tm.tournament.MatchGeneratorRegistry},
 * enabling {@link PhasePreparationService} to resolve a generator for Siegerehrung phases without
 * throwing {@link IllegalArgumentException} or requiring any modification to the service itself
 * (registry-driven dispatch — minimal-invasive).
 *
 * <h2>Path-i trade-off (from Brief O-15)</h2>
 *
 * <p>This generator returns {@code Collections.emptyList()} silently — a "silent-success" for the
 * caller. This is acceptable because:
 *
 * <ol>
 *   <li>The {@code gameMode} field comes from {@code DraftSection.gameMode}, which is whitelist-
 *       validated to {@code {roundrobin, siegerehrung}} by E48S01. No accidental mis-dispatch.
 *   <li>The draft-apply flow is deterministic per {@code draft_json}; accidental siegerehrung
 *       invocation is structurally prevented by the whitelist.
 * </ol>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-35: lives in {@code de.vvwt.tm.tournament.internal} — implementation package.
 *   <li>DEC-22: RED-first tests in {@code SiegerehrungMatchGeneratorTest} written before this
 *       class.
 *   <li>AC-IMPL-SIEGEREHRUNG-GENERATOR-BEAN: {@code getKeyId()} returns {@code "siegerehrung"}.
 *   <li>E58S01 AC1: renamed {@code getBeanId()} → {@code getKeyId()} (DEC-73 D-1).
 *   <li>E58S01 AC2: {@code isLastPhaseGenerator()} returns {@code true} (DEC-73 D-2).
 * </ul>
 *
 * @see MatchGenerator
 * @see de.vvwt.tm.tournament.MatchGeneratorRegistry
 * @see PhasePreparationService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-35">DEC-35 — implementation in tournament.internal</a>
 * @see <a href="E48S02">E48S02 — AC-IMPL-SIEGEREHRUNG-GENERATOR-BEAN</a>
 */
@Component("siegerehrungMatchGenerator")
public class SiegerehrungMatchGenerator implements MatchGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(SiegerehrungMatchGenerator.class);

    /** Default no-arg constructor. No collaborators needed for a no-op generator. */
    public SiegerehrungMatchGenerator() {}

    /**
     * Returns the registry key {@code "siegerehrung"} — the lookup key in {@link
     * de.vvwt.tm.tournament.MatchGeneratorRegistry}.
     *
     * <p>Renamed from {@code getBeanId()} by E58S01 (DEC-73 D-1).
     *
     * @return {@code "siegerehrung"}
     */
    @Override
    public String getKeyId() {
        return "siegerehrung";
    }

    /**
     * Returns {@code true} — Siegerehrung is the terminal (last) phase generator.
     *
     * <p>Added by E58S01 (DEC-73 D-2).
     *
     * @return {@code true}
     */
    @Override
    public boolean isLastPhaseGenerator() {
        return true;
    }

    /**
     * Returns an empty list — Siegerehrung phases have no matches.
     *
     * <p>Input validation is performed per the {@link MatchGenerator} contract: {@code null} phase
     * or {@code null} avatars list throw {@link IllegalArgumentException}.
     *
     * @param phase the phase for which matches would be generated; must not be {@code null}
     * @param avatars the avatars participating; must not be {@code null}
     * @return an unmodifiable empty list; never {@code null}
     * @throws IllegalArgumentException if {@code phase} or {@code avatars} is {@code null}
     */
    @Override
    public List<Match> generate(Phase phase, List<TeamAvatar> avatars) {
        if (phase == null) {
            throw new IllegalArgumentException("phase must not be null");
        }
        if (avatars == null) {
            throw new IllegalArgumentException("avatars must not be null");
        }
        LOG.info(
                "[siegerehrung] generate: phaseId={} — no-op generator, returning empty list",
                phase.getId());
        return Collections.emptyList();
    }
}
