package de.vvwt.tm.tournament;

/**
 * Value record describing a registered {@link MatchGenerator} — its key ID and capability
 * predicate.
 *
 * <p>Produced by {@link MatchGeneratorRegistry#getGeneratorInfoList()} for use by consumers that
 * need to enumerate available generators without holding a reference to the generator instances
 * themselves.
 *
 * <p>This is a plain Java record (not a Spring {@code @Component}) — exempt from the DEC-72
 * interface mandate.
 *
 * <ul>
 *   <li>DEC-35: lives in {@code de.vvwt.tm.tournament} — the bounded-context root (public) package.
 *   <li>DEC-73 D-3: record with two fields: {@code keyId} and {@code isLastPhaseGenerator}.
 *   <li>E58S01 AC3: added by this story.
 * </ul>
 *
 * @param keyId the registry key identifying the generator (e.g., {@code "roundRobin"}, {@code
 *     "siegerehrung"}); non-null, non-empty
 * @param isLastPhaseGenerator {@code true} if this generator is intended for the terminal (last)
 *     phase of a tournament; {@code false} otherwise
 * @see MatchGenerator#getKeyId()
 * @see MatchGenerator#isLastPhaseGenerator()
 * @see MatchGeneratorRegistry#getGeneratorInfoList()
 * @see <a href="DEC-35">DEC-35 — public package for bounded-context API types</a>
 * @see <a href="DEC-73">DEC-73 D-3 — MatchGeneratorInfo record</a>
 * @see <a href="E58S01">E58S01 — AC3</a>
 */
public record MatchGeneratorInfo(String keyId, boolean isLastPhaseGenerator) {}
