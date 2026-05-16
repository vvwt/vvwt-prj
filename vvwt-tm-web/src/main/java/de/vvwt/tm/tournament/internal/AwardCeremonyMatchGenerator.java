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
 * No-op {@link MatchGenerator} for the {@code gameMode=awardCeremony} phase type (E48S02, Path-i
 * per Brief O-15; renamed from {@code siegerehrung} by E58S04 — DEC-73 D-7).
 *
 * <p>An award-ceremony phase does not generate matches — it is a ceremony phase. This bean
 * registers under the key {@code "awardCeremony"} in the {@link
 * de.vvwt.tm.tournament.MatchGeneratorRegistry}, enabling {@link PhasePreparationService} to
 * resolve a generator for award-ceremony phases without throwing {@link IllegalArgumentException}
 * or requiring any modification to the service itself (registry-driven dispatch —
 * minimal-invasive).
 *
 * <h2>Path-i trade-off (from Brief O-15)</h2>
 *
 * <p>This generator returns {@code Collections.emptyList()} silently — a "silent-success" for the
 * caller. This is acceptable because:
 *
 * <ol>
 *   <li>The {@code gameMode} field comes from {@code DraftSection.gameMode}, which is
 *       registry-membership validated at draft save/apply time (DEC-73 D-6). No accidental
 *       mis-dispatch.
 *   <li>The draft-apply flow is deterministic per {@code draft_json}; accidental awardCeremony
 *       invocation is structurally prevented by the registry-membership check.
 * </ol>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-35: lives in {@code de.vvwt.tm.tournament.internal} — implementation package.
 *   <li>DEC-22: RED-first tests in {@code AwardCeremonyMatchGeneratorTest} written before this
 *       class (originally {@code SiegerehrungMatchGeneratorTest}).
 *   <li>AC-IMPL-AWARD-CEREMONY-GENERATOR-BEAN: {@code getKeyId()} returns {@code "awardCeremony"}.
 *   <li>E58S01 AC1: renamed {@code getBeanId()} → {@code getKeyId()} (DEC-73 D-1).
 *   <li>E58S01 AC2: {@code isLastPhaseGenerator()} returns {@code true} (DEC-73 D-2).
 *   <li>E58S04 AC3: renamed {@code "siegerehrung"} → {@code "awardCeremony"} (DEC-73 D-7).
 * </ul>
 *
 * @see MatchGenerator
 * @see de.vvwt.tm.tournament.MatchGeneratorRegistry
 * @see PhasePreparationService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-35">DEC-35 — implementation in tournament.internal</a>
 * @see <a href="DEC-73">DEC-73 — D-7: siegerehrung → awardCeremony rename</a>
 */
@Component("awardCeremonyMatchGenerator")
public class AwardCeremonyMatchGenerator implements MatchGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(AwardCeremonyMatchGenerator.class);

    /** Default no-arg constructor. No collaborators needed for a no-op generator. */
    public AwardCeremonyMatchGenerator() {}

    /**
     * Returns the registry key {@code "awardCeremony"} — the lookup key in {@link
     * de.vvwt.tm.tournament.MatchGeneratorRegistry}.
     *
     * <p>Renamed from {@code getBeanId()} by E58S01 (DEC-73 D-1). Registry key renamed from {@code
     * "siegerehrung"} to {@code "awardCeremony"} by E58S04 (DEC-73 D-7).
     *
     * @return {@code "awardCeremony"}
     */
    @Override
    public String getKeyId() {
        return "awardCeremony";
    }

    /**
     * Returns {@code true} — the award-ceremony phase is the terminal (last) phase generator.
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
     * Returns an empty list — award-ceremony phases have no matches.
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
                "[awardCeremony] generate: phaseId={} — no-op generator, returning empty list",
                phase.getId());
        return Collections.emptyList();
    }
}
