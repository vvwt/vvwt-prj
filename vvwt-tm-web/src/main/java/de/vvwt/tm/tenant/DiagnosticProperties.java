// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Feature-flag configuration properties for E55S10 diagnostic instrumentation.
 *
 * <h2>Purpose</h2>
 *
 * <p>Provides opt-in diagnostic instrumentation for HikariCP/H2 connection lifecycle and Spring TX
 * boundary logging — both default OFF for production. Operator enables via properties for
 * instrumented reproduction of the M-2/M-3/M-5/Display-token-loss race class (E55S10).
 *
 * <h2>Module placement</h2>
 *
 * <p>Placed in the {@code tenant} public package (not {@code tenant.internal}) so that other Spring
 * Modulith modules (e.g., {@code phaselifecycle}) can inject it for Spring TX-boundary logging
 * without violating module encapsulation.
 *
 * <h2>Security</h2>
 *
 * <p>Per AC-SEC-NO-DEBUG-LEAK-IN-PROD and AC-GOVERNANCE-INSTRUMENTATION-REVERT-AT-CLOSE: all
 * instrumentation is default {@code false}. Operator must explicitly opt in.
 *
 * @see de.vvwt.tm.tenant.internal.DiagnosticDataSourceWrapper
 * @see <a href="../../../../../../../../docs/governance/stories/E55S10.story.md">Story E55S10</a>
 * @since E55S10
 */
@Component
@ConfigurationProperties(prefix = "tm.diagnostics")
public class DiagnosticProperties {

    /**
     * Enable per-connection lifecycle logging (acquire/release, autoCommit state, TX-active). Wraps
     * per-tenant H2 DataSource in {@link de.vvwt.tm.tenant.internal.DiagnosticDataSourceWrapper}
     * when {@code true}.
     *
     * <p>Default: {@code false} (production-safe; no overhead).
     */
    private boolean hikariTrace = false;

    /**
     * Enable Spring TX-boundary logging (thread-name, TX-name, nesting, COMMITTED/ROLLED_BACK).
     * Activates {@code DiagnosticTransactionSynchronization} registration when {@code true}.
     *
     * <p>Default: {@code false} (production-safe; no overhead).
     */
    private boolean springTxTrace = false;

    /** Returns whether per-connection lifecycle tracing is enabled. */
    public boolean isHikariTrace() {
        return hikariTrace;
    }

    /** Sets per-connection lifecycle tracing. */
    public void setHikariTrace(boolean hikariTrace) {
        this.hikariTrace = hikariTrace;
    }

    /** Returns whether Spring TX-boundary tracing is enabled. */
    public boolean isSpringTxTrace() {
        return springTxTrace;
    }

    /** Sets Spring TX-boundary tracing. */
    public void setSpringTxTrace(boolean springTxTrace) {
        this.springTxTrace = springTxTrace;
    }
}
