package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.TenantDataSourceResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Per-tenant Flyway runner: applies module-ordered migrations to a single tenant's H2 database.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Given a tenant identifier, this runner:
 *
 * <ol>
 *   <li>Resolves the tenant's {@link DataSource} via {@link TenantDataSourceResolver} (which
 *       enforces fail-fast for unknown tenants — AC6).
 *   <li>Derives the per-module migration locations from {@link ApplicationModules#of(Class)} in
 *       module-dependency order (using the module comparator — AC3).
 *   <li>Configures and executes Flyway programmatically against the tenant-specific DataSource
 *       using only {@code classpath:db/migration/{moduleName}} locations (AC3, AC8).
 * </ol>
 *
 * <h2>This is NOT SpringModulithFlywayMigrationStrategy (AC3)</h2>
 *
 * <p>{@code SpringModulithFlywayMigrationStrategy} is a Spring Boot boot-time strategy — it hooks
 * into {@code FlywayMigrationStrategy} which runs once at application startup against the
 * auto-configured DataSource. It is fundamentally incompatible with per-tenant runtime invocation
 * because: (a) it runs once per JVM start, not once per tenant; (b) it wires to Spring Boot's
 * shared auto-configured DataSource, not a per-tenant DataSource obtained at runtime; (c) there is
 * no public API to invoke it programmatically for a specific DataSource.
 *
 * <p>This runner preserves the DEC-21 module-dependency-ordered semantics by consulting {@code
 * ApplicationModules.of(applicationClass).getComparator()} — the same ordering logic that Modulith
 * uses internally — but invoked at tenant-creation time, not boot time.
 *
 * <h2>Migration location convention (DEC-21)</h2>
 *
 * <p>Flyway is configured with locations {@code classpath:db/migration/{moduleName}} for each
 * module returned by {@link ApplicationModules}. Only modules that actually have a corresponding
 * migration directory on the classpath are included — modules without migrations are silently
 * skipped (Flyway would throw a {@code FlywayException} for non-existent locations if not
 * filtered). The legacy root {@code classpath:db/migration} is NEVER included (AC8).
 *
 * <h2>Idempotency (AC5)</h2>
 *
 * <p>Flyway's default behaviour is to skip already-applied migrations based on {@code
 * flyway_schema_history} checksums. No custom idempotency logic is needed.
 *
 * <h2>Failure behaviour (AC4)</h2>
 *
 * <p>Any {@code FlywayException} thrown by Flyway propagates unchanged. The runner does NOT catch,
 * wrap, or suppress Flyway exceptions. Tenant cleanup on migration failure is a lifecycle concern
 * (caller responsibility), not a runner concern.
 *
 * <h2>No tenant::api widening (AC7)</h2>
 *
 * <p>This runner consumes only {@link TenantDataSourceResolver} from the tenant public API —
 * exactly as defined in E14S01. No new methods are added to any {@code tenant::api} interface.
 *
 * <h2>Internal placement (DEC-21)</h2>
 *
 * <p>This class lives in {@code de.vvwt.tm.tenant.internal}. It MUST NOT be imported by classes
 * outside the {@code tenant} module. Spring wiring is done via {@link TenantContextConfiguration}.
 *
 * @see TenantDataSourceResolver
 * @see TenantContextConfiguration
 * @see <a href="../../../../../../../../docs/governance/stories/E14S04.story.md">Story E14S04</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22</a>
 */
public class PerTenantFlywayRunner {

    private final TenantDataSourceResolver resolver;
    private final Class<?> applicationClass;

    /**
     * Constructs a {@code PerTenantFlywayRunner}.
     *
     * @param resolver the resolver that provides per-tenant DataSources; must not be {@code null}
     * @param applicationClass the Spring Boot main class, used to bootstrap {@link
     *     ApplicationModules#of(Class)} for module-ordered migration locations; must not be {@code
     *     null}
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public PerTenantFlywayRunner(TenantDataSourceResolver resolver, Class<?> applicationClass) {
        if (resolver == null) {
            throw new IllegalArgumentException("resolver must not be null");
        }
        if (applicationClass == null) {
            throw new IllegalArgumentException("applicationClass must not be null");
        }
        this.resolver = resolver;
        this.applicationClass = applicationClass;
    }

    /**
     * Runs all pending Flyway migrations for the given tenant.
     *
     * <p>Resolves the tenant's DataSource, derives module-ordered migration locations, and invokes
     * Flyway. Subsequent calls with the same {@code tenantId} are idempotent — Flyway skips
     * already-applied migrations based on {@code flyway_schema_history} (AC5).
     *
     * @param tenantId the tenant for which to run migrations; must not be {@code null}
     * @throws TenantDataSourceResolver.UnknownTenantException if the tenant is not registered (AC6)
     * @throws IllegalArgumentException if {@code tenantId} is {@code null}
     * @throws org.flywaydb.core.api.FlywayException if a migration fails (AC4); propagated
     *     unchanged
     */
    public void run(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }

        // AC6: resolve() throws UnknownTenantException for unregistered tenants — propagated
        // unchanged
        DataSource dataSource = resolver.resolve(tenantId);

        runWithDataSource(tenantId, dataSource);
    }

    /**
     * Runs all pending Flyway migrations against the provided DataSource, using the same
     * module-ordered migration locations as {@link #run(UUID)}.
     *
     * <p>Package-private to allow {@link DefaultTenantBootstrapRunner} to run migrations BEFORE
     * registering the tenant in the registry — which is necessary to ensure that a failed migration
     * does NOT leave a registry entry pointing to an incomplete database (E14S05 AC5). This method
     * does NOT perform a registry existence check; callers are responsible for providing a valid
     * DataSource.
     *
     * <p>Subsequent calls with the same DataSource are idempotent — Flyway skips already-applied
     * migrations based on {@code flyway_schema_history}.
     *
     * @param tenantId the tenant UUID (used only for logging context); must not be {@code null}
     * @param dataSource the DataSource to run migrations against; must not be {@code null}
     * @throws IllegalArgumentException if {@code tenantId} or {@code dataSource} is {@code null}
     * @throws org.flywaydb.core.api.FlywayException if a migration fails; propagated unchanged
     * @see DefaultTenantBootstrapRunner
     * @see <a href="../../../../../../../../docs/governance/stories/E14S05.story.md">Story E14S05
     *     AC5</a>
     */
    public void runWithDataSource(UUID tenantId, DataSource dataSource) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }

        List<String> locations = buildLocations();

        if (locations.isEmpty()) {
            // No migrations exist for any module — Flyway is a no-op
            return;
        }

        Flyway flyway =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations(locations.toArray(String[]::new))
                        .load();

        // FlywayException propagates — no try/catch. Flyway skips applied migrations (idempotent).
        flyway.migrate();
    }

    /**
     * Derives the ordered list of Flyway migration locations from the project's {@link
     * ApplicationModules}, filtered to only locations that exist on the classpath.
     *
     * <p>Location format: {@code classpath:db/migration/{moduleName}}. The legacy root {@code
     * classpath:db/migration} is NEVER returned (AC8). Ordering follows {@link
     * ApplicationModules#getComparator()} — dependencies before dependents.
     *
     * <p>This method is package-private for testability (AC3 unit test). It is not part of the
     * public API and MUST NOT be called from outside the {@code tenant.internal} package.
     *
     * @return an ordered, non-null list of classpath migration locations; may be empty if no module
     *     has a migration directory on the classpath
     */
    public List<String> buildLocations() {
        ApplicationModules modules = ApplicationModules.of(applicationClass);

        // Collect modules in dependency-tree order (dependencies first)
        @SuppressWarnings("unchecked")
        java.util.Comparator<ApplicationModule> comparator =
                (java.util.Comparator<ApplicationModule>)
                        (java.util.Comparator<?>) modules.getComparator();

        List<ApplicationModule> orderedModules = modules.stream().sorted(comparator).toList();

        List<String> locations = new ArrayList<>();
        for (ApplicationModule module : orderedModules) {
            String moduleName = module.getName();
            // AC8: Skip any module whose name is empty or blank (guard against edge case)
            if (moduleName == null || moduleName.isBlank()) {
                continue;
            }
            String location = "classpath:db/migration/" + moduleName;
            // Only include the location if a corresponding directory exists on the classpath.
            // Flyway throws FlywayException for non-existent locations; we filter them out here
            // so that modules without migrations are silently skipped (no-op).
            if (migrationLocationExistsOnClasspath(location)) {
                locations.add(location);
            }
        }
        return locations;
    }

    /**
     * Returns {@code true} if the given classpath location has at least one resource accessible on
     * the current classpath.
     *
     * <p>Uses {@link ClassLoader#getResource(String)} to probe the location directory. If the
     * resource is found (non-null), the location is considered valid.
     *
     * @param classpathLocation a Flyway location string of the form {@code
     *     classpath:db/migration/{name}}
     * @return {@code true} if the location directory exists on the classpath
     */
    private static boolean migrationLocationExistsOnClasspath(String classpathLocation) {
        // Strip the "classpath:" prefix to form the resource path
        String resourcePath =
                classpathLocation.startsWith("classpath:")
                        ? classpathLocation.substring("classpath:".length())
                        : classpathLocation;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = PerTenantFlywayRunner.class.getClassLoader();
        }
        return cl.getResource(resourcePath) != null;
    }
}
