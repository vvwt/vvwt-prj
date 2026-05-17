// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.score;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for instantiating the configured variety scorer (E54S07 / DEC-63 Clause C).
 *
 * <p>Resolves the Spring property {@code tm.slotopt.scorer} to a {@link Scorer} instance:
 *
 * <ul>
 *   <li>{@code mean} (default): {@link VarietyScorer} wrapped as {@link Scorer} — MEAN-of-products,
 *       legacy port, backward-compatible.
 *   <li>{@code balanced}: {@link BalancedVarietyScorer} — VARIANCE-of-products, variance-aware,
 *       theoretically aligned with the user's balance-objective.
 *   <li>Any other value: WARN log + fall back to {@code mean} (no exception thrown — DEC-63 Clause
 *       C operational-deployment-compatibility requirement).
 * </ul>
 *
 * <h2>VarietyScorer textual invariance (DEC-63 Clause A)</h2>
 *
 * <p>{@link VarietyScorer} does NOT implement {@link Scorer} — it is textually unchanged per DEC-63
 * Clause A / AC-GOVERNANCE-NO-EXISTING-VARIETYSCORER-MUTATION. This factory wraps it in a thin
 * anonymous-class adapter, keeping the original class unmodified.
 *
 * <h2>Usage</h2>
 *
 * <p>Call sites inject {@code @Value("${tm.slotopt.scorer:mean}")} and pass the resolved string to
 * {@link #createScorer(String)}.
 *
 * <h2>DEC-58 interface mandate N/A</h2>
 *
 * <p>This class is not annotated with any Spring stereotype. Per DEC-58 Clause A, no interface
 * mandate applies to non-bean utility classes.
 */
public final class ScorerFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ScorerFactory.class);

    /** Private constructor — static utility class. */
    private ScorerFactory() {
        throw new UnsupportedOperationException("ScorerFactory is a static utility class");
    }

    /**
     * Creates a scorer instance for the given configuration value.
     *
     * <p>Returns the concrete scorer object. For the {@code "mean"} case returns a new {@link
     * VarietyScorer}; for {@code "balanced"} returns a new {@link BalancedVarietyScorer}.
     *
     * <p>Call sites that need a unified {@link Scorer} interface should use {@link
     * #createScorerUnified(String)}.
     *
     * @param scorerConfig the value of {@code tm.slotopt.scorer}; {@code null} is treated as {@code
     *     "mean"}
     * @return a {@link VarietyScorer} for {@code "mean"}, a {@link BalancedVarietyScorer} for
     *     {@code "balanced"}, or a {@link VarietyScorer} as fallback for unrecognized values
     */
    public static Object createScorer(String scorerConfig) {
        if ("balanced".equals(scorerConfig)) {
            return new BalancedVarietyScorer();
        } else if (scorerConfig == null || "mean".equals(scorerConfig)) {
            return new VarietyScorer();
        } else {
            LOG.warn(
                    "Unknown tm.slotopt.scorer value '{}'; falling back to default 'mean'",
                    scorerConfig);
            return new VarietyScorer();
        }
    }

    /**
     * Creates a {@link Scorer}-interface-typed scorer for the given configuration value.
     *
     * <p>For the {@code "mean"} case, returns a thin adapter wrapping {@link VarietyScorer}
     * (preserving VarietyScorer textually unchanged per DEC-63 Clause A — VarietyScorer itself does
     * not implement {@link Scorer}). For {@code "balanced"}, returns a {@link
     * BalancedVarietyScorer} directly.
     *
     * @param scorerConfig the value of {@code tm.slotopt.scorer}
     * @return a {@link Scorer} unified instance
     */
    public static Scorer createScorerUnified(String scorerConfig) {
        if ("balanced".equals(scorerConfig)) {
            return new BalancedVarietyScorer();
        } else if (scorerConfig == null || "mean".equals(scorerConfig)) {
            return varietyScorerAdapter(new VarietyScorer());
        } else {
            LOG.warn(
                    "Unknown tm.slotopt.scorer value '{}'; falling back to default 'mean'",
                    scorerConfig);
            return varietyScorerAdapter(new VarietyScorer());
        }
    }

    /**
     * Returns a thin {@link Scorer} adapter wrapping the given {@link VarietyScorer} instance.
     *
     * <p>Preserves {@link VarietyScorer} textual invariance per DEC-63 Clause A.
     */
    private static Scorer varietyScorerAdapter(VarietyScorer delegate) {
        return new Scorer() {
            @Override
            public double score(int[] rowSequence, CanonicalPhaseDef phaseDef) {
                return delegate.score(rowSequence, phaseDef);
            }

            @Override
            public double scoreWithMatrix(
                    int[] rowSequence, int rowCount, int avatarCount, boolean[][] activeMatrix) {
                return delegate.scoreWithMatrix(rowSequence, rowCount, avatarCount, activeMatrix);
            }

            @Override
            public boolean[][] buildActiveMatrix(
                    List<List<Integer>> rows, int rowCount, int avatarCount) {
                return delegate.buildActiveMatrix(rows, rowCount, avatarCount);
            }
        };
    }
}
