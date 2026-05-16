// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.TenantDataSourceResolver;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Unit test for {@link RoutingTenantDataSource} unbound-context fail-fast path (AC6 of E14S11).
 *
 * <p>Verifies that {@code RoutingTenantDataSource.getConnection()} throws {@link
 * IllegalStateException} with the message "No tenant is bound" when no {@link
 * de.vvwt.tm.tenant.TenantContext} is bound on the current thread.
 *
 * <p>This is a unit test — no Spring context, no {@code @SpringBootTest}. The routing bean is
 * constructed directly with a real {@link ThreadLocalTenantContextImpl} and a stub resolver.
 *
 * <h2>Why this test is always GREEN</h2>
 *
 * <p>The fail-fast path was implemented in E14S03 and is not gated on the {@code @Primary}
 * annotation. This test documents the path and ensures it survives refactoring. It does NOT
 * participate in the AC1 RED-then-GREEN cycle — it is always expected to pass.
 *
 * @see RoutingTenantDataSource
 * @see ThreadLocalTenantContextImpl
 * @see <a href="../../../../../../../../../docs/governance/stories/E14S11.story.md">Story E14S11
 *     AC6</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20
 *     (DB-per-Tenant)</a>
 * @since E14S11
 */
class RoutingDataSourceUnboundContextTest {

    // -------------------------------------------------------------------------
    // AC6 — No tenant bound → IllegalStateException containing "No tenant is bound"
    // -------------------------------------------------------------------------

    /**
     * AC6: When no tenant is bound, {@code RoutingTenantDataSource.getConnection()} MUST throw
     * {@link IllegalStateException} with a message containing "No tenant is bound to the current
     * thread".
     *
     * <p>This is the unit-level proof of the fast-fail contract. The Spring-level proof is in
     * {@link
     * de.vvwt.tm.tenant.RoutingDataSourceActivationIT#routingDataSource_withoutTenantContext_throwsIllegalStateException()}.
     */
    @Test
    void getConnection_withNoTenantBound_throwsIllegalStateException() {
        ThreadLocalTenantContextImpl tenantContext = new ThreadLocalTenantContextImpl();
        StubResolver resolver = new StubResolver();
        RoutingTenantDataSource routing = new RoutingTenantDataSource(tenantContext, resolver);

        // No tenant bound on this thread → IllegalStateException from TenantContext.current()
        assertThatThrownBy(routing::getConnection)
                .as(
                        "RoutingTenantDataSource.getConnection() must throw IllegalStateException "
                                + "when no TenantContext is bound (AC6 / E14S11)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant is bound");
    }

    // -------------------------------------------------------------------------
    // Stub resolver (no-op — never called because TenantContext throws first)
    // -------------------------------------------------------------------------

    private static class StubResolver implements TenantDataSourceResolver {

        @Override
        public DataSource resolve(UUID tenantId) {
            // Stub — returns a dummy DataSource if ever called
            SingleConnectionDataSource ds = new SingleConnectionDataSource();
            ds.setSuppressClose(true);
            return ds;
        }
    }
}
