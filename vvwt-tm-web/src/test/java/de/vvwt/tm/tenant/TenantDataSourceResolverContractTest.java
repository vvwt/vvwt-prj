package de.vvwt.tm.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link TenantDataSourceResolver}.
 *
 * <p>Uses a hand-rolled test double — no Mockito (AC7, DEC-22 anti-patterns ref).
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC1 — test-first discipline: this file exists BEFORE the interface
 *   <li>AC3 — resolution semantics: unknown tenant fast-fails, never silently returns shared DS
 *   <li>AC6 — unknown tenant → typed exception (fast-fail, not lazily-failing DataSource)
 *   <li>AC8 — Javadoc coverage (verified in interface source)
 * </ul>
 *
 * <p>Story: E14S01 — DEC-20/DEC-21/DEC-22.
 */
class TenantDataSourceResolverContractTest {

    // -------------------------------------------------------------------------
    // Test double
    // -------------------------------------------------------------------------

    /**
     * In-memory test double for {@link TenantDataSourceResolver}. Holds a map of known tenant UUIDs
     * → DataSource instances. Unknown tenants throw {@link
     * TenantDataSourceResolver.UnknownTenantException}.
     */
    static class MapTenantDataSourceResolver implements TenantDataSourceResolver {

        private final Map<UUID, DataSource> knownTenants = new HashMap<>();

        void register(UUID tenantId, DataSource dataSource) {
            knownTenants.put(tenantId, dataSource);
        }

        @Override
        public DataSource resolve(UUID tenantId) {
            DataSource ds = knownTenants.get(tenantId);
            if (ds == null) {
                throw new TenantDataSourceResolver.UnknownTenantException(tenantId);
            }
            return ds;
        }
    }

    /** Minimal DataSource stub — enough to satisfy non-null assertion. */
    static DataSource stubDataSource() {
        return new org.springframework.jdbc.datasource.SingleConnectionDataSource();
    }

    // -------------------------------------------------------------------------
    // AC3 / AC6 — resolve known tenant returns non-null DataSource
    // -------------------------------------------------------------------------

    /** AC3: resolving a known tenant MUST return a non-null {@link DataSource}. */
    @Test
    void resolveKnownTenantReturnsNonNullDataSource() {
        MapTenantDataSourceResolver resolver = new MapTenantDataSourceResolver();
        UUID tenantId = UUID.fromString("11111111-0000-0000-0000-000000000001");
        DataSource expected = stubDataSource();
        resolver.register(tenantId, expected);

        DataSource result = resolver.resolve(tenantId);

        assertThat(result)
                .as("resolve() for a known tenant must return a non-null DataSource (AC3)")
                .isNotNull()
                .isSameAs(expected);
    }

    // -------------------------------------------------------------------------
    // AC6 — unknown tenant → typed exception (fast-fail, no lazy failure)
    // -------------------------------------------------------------------------

    /**
     * AC6: resolving an unknown tenant MUST throw {@link
     * TenantDataSourceResolver.UnknownTenantException} immediately — not return a DataSource that
     * fails lazily.
     */
    @Test
    void resolveUnknownTenantThrowsUnknownTenantException() {
        MapTenantDataSourceResolver resolver = new MapTenantDataSourceResolver();
        UUID unknownId = UUID.fromString("99999999-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> resolver.resolve(unknownId))
                .as(
                        "resolve() for an unknown tenant must throw UnknownTenantException "
                                + "immediately — not return a lazily-failing DataSource (AC6)")
                .isInstanceOf(TenantDataSourceResolver.UnknownTenantException.class);
    }

    // -------------------------------------------------------------------------
    // AC3 — distinction: unknown tenant (exception) vs empty lookup (Optional.empty)
    // -------------------------------------------------------------------------

    /**
     * AC3: {@code resolve()} throws on unknown tenant; it is NOT the same as an empty lookup. The
     * exception message must contain the unknown tenant UUID for diagnostics.
     */
    @Test
    void unknownTenantExceptionContainsTenantId() {
        MapTenantDataSourceResolver resolver = new MapTenantDataSourceResolver();
        UUID unknownId = UUID.fromString("deadbeef-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> resolver.resolve(unknownId))
                .isInstanceOf(TenantDataSourceResolver.UnknownTenantException.class)
                .hasMessageContaining(unknownId.toString());
    }
}
