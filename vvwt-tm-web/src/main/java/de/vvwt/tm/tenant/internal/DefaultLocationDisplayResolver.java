package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.LocationDisplayResolver;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Default JdbcTemplate-backed implementation of {@link LocationDisplayResolver}.
 *
 * <p>Executes the following query against the per-tenant DataSource (routed via {@code
 * RoutingTenantDataSource} per DEC-20):
 *
 * <pre>{@code
 * SELECT display_name FROM locations WHERE tenant_id = ? LIMIT 1
 * }</pre>
 *
 * <p>Byte-equivalent to the legacy SQL at {@code
 * de.vvwt.tm.infrastructure.print.PrintController:1186}. Returns empty string {@code ""} when the
 * query returns no rows.
 *
 * <h2>DEC-39 interim-state note</h2>
 *
 * <p>The {@code tenant_id = ?} predicate is PRESERVED byte-equivalent to the legacy method. Under
 * DEC-39, {@code tenant_id} columns are removed from tenant-scoped tables at the Wave-2
 * Big-Bang-Reset (DEC-25). Until that commit lands, {@code V1__initial_schema.sql} retains the
 * {@code locations.tenant_id} column, and this predicate remains the correct isolation mechanism
 * within a single DataSource. After the Big-Bang-Reset this class will require updating to drop the
 * predicate.
 *
 * <h2>DEC-35 package placement</h2>
 *
 * <p>Public interface {@code LocationDisplayResolver} lives at {@code de.vvwt.tm.tenant} (public
 * Modulith surface). This {@code Default*} implementation lives at {@code
 * de.vvwt.tm.tenant.internal} per DEC-35 convention.
 *
 * @see LocationDisplayResolver
 * @since E24S04 — predicate preserved until DEC-25 Big-Bang-Reset
 */
@Service
public class DefaultLocationDisplayResolver implements LocationDisplayResolver {

    private static final String SQL =
            "SELECT display_name FROM locations WHERE tenant_id = ? LIMIT 1";

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
     * <p>Uses a parameterised {@code JdbcTemplate} query — no string concatenation of the {@code
     * tenantId} value. SQL injection is not possible through the {@code tenantId} parameter
     * (AC-SECURITY-NO-SQL-INJECTION).
     */
    @Override
    public String resolveLocationDisplayName(UUID tenantId) {
        List<String> results =
                jdbcTemplate.query(SQL, (rs, rowNum) -> rs.getString("display_name"), tenantId);
        return results.isEmpty() ? "" : results.get(0);
    }
}
