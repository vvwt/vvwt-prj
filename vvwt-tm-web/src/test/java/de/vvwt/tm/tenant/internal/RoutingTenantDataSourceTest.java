package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RoutingTenantDataSource}.
 *
 * <p>These tests exercise the routing key determination and unbound-context error handling.
 * They do NOT test full JDBC routing — that is covered by {@link RoutingTenantDataSourceIT}.
 *
 * <p>Uses real {@link ThreadLocalTenantContextImpl} and a hand-rolled
 * {@link TenantDataSourceResolver} stub — no Mockito.
 *
 * <p>Acceptance criteria covered:
 * <ul>
 *   <li>AC1 — test-first (RED verified before implementation)</li>
 *   <li>AC3 — unbound context → typed exception, NOT NullPointerException, NOT fallback DS</li>
 *   <li>AC5 — ApplicationModulesTest remains green (structural)</li>
 *   <li>AC6 — RoutingTenantDataSource lives in tenant.internal (structural)</li>
 * </ul>
 *
 * <p>Story: E14S03 — DEC-10/DEC-14/DEC-20/DEC-21/DEC-22.
 */
class RoutingTenantDataSourceTest {

    // -------------------------------------------------------------------------
    // Test doubles — no Mockito (DEC-22 anti-patterns reference)
    // -------------------------------------------------------------------------

    /** Stub resolver: returns a fixed DataSource for any registered UUID. */
    static class StubDataSourceResolver implements TenantDataSourceResolver {

        private final java.util.Map<UUID, DataSource> registry = new java.util.HashMap<>();

        void register(UUID id, DataSource ds) {
            registry.put(id, ds);
        }

        @Override
        public DataSource resolve(UUID tenantId) {
            DataSource ds = registry.get(tenantId);
            if (ds == null) {
                throw new UnknownTenantException(tenantId);
            }
            return ds;
        }
    }

    static DataSource stubDataSource() {
        return new org.springframework.jdbc.datasource.SingleConnectionDataSource();
    }

    // -------------------------------------------------------------------------
    // AC3 — unbound context → typed exception, not NPE
    // -------------------------------------------------------------------------

    /**
     * AC3: When no tenant is bound, {@code RoutingTenantDataSource} must surface the typed
     * exception from {@code TenantContext.current()} — NOT a NullPointerException and NOT a
     * silent fallback to a "default" DataSource.
     */
    @Test
    void determineCurrentLookupKeyThrowsIllegalStateExceptionWhenNoTenantBound() {
        TenantContext ctx = new ThreadLocalTenantContextImpl();
        StubDataSourceResolver resolver = new StubDataSourceResolver();
        RoutingTenantDataSource routing = new RoutingTenantDataSource(ctx, resolver);

        // Verify that the routing DS (via determineCurrentLookupKey) propagates
        // the IllegalStateException from TenantContext.current()
        assertThatThrownBy(routing::determineCurrentLookupKey)
                .as("No tenant bound — must propagate IllegalStateException from TenantContext.current() (AC3)")
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // Routing key matches bound tenant
    // -------------------------------------------------------------------------

    @Test
    void determineCurrentLookupKeyReturnsBoundTenantId() {
        TenantContext ctx = new ThreadLocalTenantContextImpl();
        StubDataSourceResolver resolver = new StubDataSourceResolver();
        UUID tenantId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        resolver.register(tenantId, stubDataSource());
        RoutingTenantDataSource routing = new RoutingTenantDataSource(ctx, resolver);

        try (TenantContext.Scope ignored = ctx.bind(tenantId)) {
            Object key = routing.determineCurrentLookupKey();
            assertThat(key)
                    .as("Routing key must match the bound tenant UUID")
                    .isEqualTo(tenantId);
        }
    }

    // -------------------------------------------------------------------------
    // AC3 — determineTargetDataSource: unknown tenant → UnknownTenantException
    // -------------------------------------------------------------------------

    @Test
    void unknownTenantAtRoutingTimeThrowsUnknownTenantException() {
        TenantContext ctx = new ThreadLocalTenantContextImpl();
        StubDataSourceResolver resolver = new StubDataSourceResolver();
        // Do NOT register the tenant — so resolver will throw
        UUID unknownId = UUID.fromString("99999999-0000-0000-0000-000000000001");
        RoutingTenantDataSource routing = new RoutingTenantDataSource(ctx, resolver);

        try (TenantContext.Scope ignored = ctx.bind(unknownId)) {
            assertThatThrownBy(routing::determineTargetDataSource)
                    .as("Unknown tenant at routing time must throw UnknownTenantException (AC3)")
                    .isInstanceOf(TenantDataSourceResolver.UnknownTenantException.class);
        }
    }
}
