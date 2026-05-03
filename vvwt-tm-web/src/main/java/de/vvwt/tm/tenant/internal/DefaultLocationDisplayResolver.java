package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.LocationDisplayResolver;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Default JdbcTemplate-backed implementation of {@link LocationDisplayResolver}.
 *
 * <p>Executes the following query against the per-tenant DataSource (routed via {@code
 * RoutingTenantDataSource} per DEC-20):
 *
 * <pre>{@code
 * SELECT display_name FROM locations LIMIT 1
 * }</pre>
 *
 * <p>E45S04 — DEC-39/DEC-50 predicate removal: the {@code WHERE tenant_id = ?} clause is dropped.
 * Under DEC-20 DB-per-Tenant, connection-level routing ensures all rows in the per-tenant
 * DataSource belong to the bound tenant — the discriminator predicate is redundant. The {@code
 * tenantId} parameter is removed from the method signature accordingly.
 *
 * <h2>DEC-35 package placement</h2>
 *
 * <p>Public interface {@code LocationDisplayResolver} lives at {@code de.vvwt.tm.tenant} (public
 * Modulith surface). This {@code Default*} implementation lives at {@code
 * de.vvwt.tm.tenant.internal} per DEC-35 convention.
 *
 * @see LocationDisplayResolver
 * @since E24S04; amended E45S04 (DEC-39/DEC-50 predicate removal)
 */
@Service
public class DefaultLocationDisplayResolver implements LocationDisplayResolver {

    private static final String SQL = "SELECT display_name FROM locations LIMIT 1";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor injection — no field injection per project convention.
     *
     * @param jdbcTemplate the JdbcTemplate wired to the per-tenant routing DataSource
     */
    public DefaultLocationDisplayResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the display_name of the first location row in the per-tenant DataSource. Per
     * DEC-20 DB-per-Tenant, connection-level routing guarantees all rows belong to the bound tenant
     * — no tenant_id predicate is required (E45S04 removal, DEC-39/DEC-50).
     */
    @Override
    public String resolveLocationDisplayName() {
        List<String> results =
                jdbcTemplate.query(SQL, (rs, rowNum) -> rs.getString("display_name"));
        return results.isEmpty() ? "" : results.get(0);
    }
}
