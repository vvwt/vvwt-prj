package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.tenant.DiagnosticProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Diagnostic TX-boundary support for E55S10 (AC-DIAG-INSTRUMENT-SPRING-TX-BOUNDARY).
 *
 * <h2>Purpose</h2>
 *
 * <p>Registers a {@link TransactionSynchronization} for the current TX when {@code
 * tm.diagnostics.spring-tx-trace=true}. On TX completion, logs:
 *
 * <ul>
 *   <li>Thread name
 *   <li>TX name ({@link TransactionSynchronizationManager#getCurrentTransactionName()})
 *   <li>TX nesting depth ({@link TransactionSynchronizationManager#isCurrentTransactionReadOnly()})
 *   <li>TX isolation level
 *   <li>Completion status (COMMITTED / ROLLED_BACK / UNKNOWN)
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <p>Call {@link #registerIfEnabled(DiagnosticProperties)} from within a {@code @Transactional}
 * method (or any context where {@link TransactionSynchronizationManager#isSynchronizationActive()}
 * is true). The registration is a no-op outside of a TX context or when the feature flag is off.
 *
 * <p>This approach (explicit call-site registration) is simpler than AOP advice and avoids
 * introducing new Spring proxy boundaries.
 *
 * @since E55S10
 */
final class DiagnosticTransactionSupport {

    private static final Logger LOG = LoggerFactory.getLogger(DiagnosticTransactionSupport.class);

    private DiagnosticTransactionSupport() {}

    /**
     * Registers a diagnostic {@link TransactionSynchronization} for the current TX if {@code
     * tm.diagnostics.spring-tx-trace=true} and synchronization is active.
     *
     * @param diagnosticProperties E55S10 feature-flag holder; must not be {@code null}
     */
    static void registerIfEnabled(DiagnosticProperties diagnosticProperties) {
        if (!diagnosticProperties.isSpringTxTrace()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        Integer isolationLevel =
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        String threadName = Thread.currentThread().getName();

        LOG.info(
                "[diag-tx] REGISTER txName={} thread={} readOnly={} isolationLevel={}",
                txName != null ? txName : "<unnamed>",
                threadName,
                readOnly,
                isolationLevel != null ? isolationLevel : "<default>");

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        String statusLabel =
                                switch (status) {
                                    case STATUS_COMMITTED -> "COMMITTED";
                                    case STATUS_ROLLED_BACK -> "ROLLED_BACK";
                                    default -> "UNKNOWN";
                                };
                        LOG.info(
                                "[diag-tx] COMPLETE txName={} thread={} status={}"
                                        + " readOnly={} isolationLevel={}",
                                txName != null ? txName : "<unnamed>",
                                threadName,
                                statusLabel,
                                readOnly,
                                isolationLevel != null ? isolationLevel : "<default>");
                    }
                });
    }
}
