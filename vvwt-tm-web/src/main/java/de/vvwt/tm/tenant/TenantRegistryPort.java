package de.vvwt.tm.tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Provides tenant existence lookup and registration capabilities for the {@code tenant} context.
 *
 * <h2>Intended consumer</h2>
 * <p>Any bounded context that needs to verify a tenant exists, or that needs to register
 * a new tenant (e.g., administrative flows). Consumers declare
 * {@code @ApplicationModule(allowedDependencies = "tenant")} and inject this interface.
 *
 * <h2>Thread-safety</h2>
 * <p>Implementations MUST be thread-safe. {@code lookup()} may be called concurrently
 * from multiple request threads. Implementations backed by a concurrent data structure
 * (e.g., {@code ConcurrentHashMap} backed by the persistent registry) satisfy this requirement.
 *
 * <h2>Transactional semantics</h2>
 * <p>{@code lookup()} is a read-only operation. Implementations may operate within or outside
 * a transaction boundary — they MUST NOT require a transaction to be active. Callers should
 * not assume any transactional guarantee from this method; if transactional consistency is
 * required, callers must manage the transaction themselves.
 *
 * <h2>Lookup semantics — distinction from {@link TenantDataSourceResolver#resolve(UUID)}</h2>
 * <p>{@code lookup()} returns {@link Optional#empty()} for an unknown tenant. This is intentionally
 * different from {@link TenantDataSourceResolver#resolve(UUID)}, which throws
 * {@link TenantDataSourceResolver.UnknownTenantException} immediately.
 * The contract distinction is:
 * <ul>
 *   <li>{@code lookup()} — existence check. Returning {@code empty()} is a valid, expected outcome
 *       for callers that want to test whether a tenant exists before acting.</li>
 *   <li>{@code resolve()} — routing. An unknown tenant at resolution time is always a programming
 *       error (the tenant should have been registered before any DataSource access) — hence the
 *       fast-fail exception.</li>
 * </ul>
 * This distinction is documented on both interfaces and tested in
 * {@code TenantRegistryPortContractTest} and {@code TenantDataSourceResolverContractTest} (AC3).
 *
 * <h2>Async propagation surfaces (Wave-1 documentation)</h2>
 * <p>If lookups are performed in async contexts (see {@link TenantContext} Javadoc for the
 * full list of surfaces where ThreadLocal-based context does NOT propagate automatically),
 * callers must capture the tenant UUID before crossing the boundary and pass it explicitly.
 * This interface accepts the UUID as a parameter, so it is inherently async-safe on the
 * caller side — provided the UUID itself was captured correctly.
 *
 * @see TenantContext
 * @see TenantDataSourceResolver
 * @see <a href="../../../../../../../../docs/governance/stories/E14S01.story.md">Story E14S01</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E14S02.story.md">Story E14S02 (register/findAll added)</a>
 */
public interface TenantRegistryPort {

    /**
     * Looks up the tenant with the given UUID in the registry.
     *
     * <p>Returns {@link Optional#empty()} if no tenant with that UUID is registered.
     * This is an existence check — returning {@code empty()} is a valid and expected outcome.
     * It does NOT throw for an unknown tenant (contrast with
     * {@link TenantDataSourceResolver#resolve(UUID)}, which throws for unknown tenants — AC3).
     *
     * @param tenantId the tenant UUID to look up; must not be {@code null}
     * @return an {@link Optional} containing the {@link TenantRecord} if found;
     *         {@link Optional#empty()} if no such tenant is registered (AC3)
     */
    Optional<TenantRecord> lookup(UUID tenantId);

    /**
     * Registers a new tenant with the given UUID and display name.
     *
     * <p>This is a write operation that persists the registration durably (per AC3 of E14S02).
     * Implementations are thread-safe: concurrent calls with DIFFERENT {@code tenantId} values
     * must both succeed; concurrent calls with the SAME {@code tenantId} must result in exactly
     * one success — the other must fail with a deterministic exception (AC-CONCURRENT-REGISTER).
     *
     * <h2>Concurrency contract (AC-CONCURRENT-REGISTER)</h2>
     * <p>Two threads simultaneously calling {@code register(tenantId)} for DIFFERENT tenant IDs
     * MUST both succeed without partial-write corruption.
     * Two threads simultaneously calling {@code register(tenantId)} for the SAME tenant ID
     * MUST result in exactly one success; the other receives a typed exception (implementation-defined,
     * e.g. {@code DuplicateTenantException} in {@code tenant.internal}).
     *
     * @param tenantId    the UUID of the new tenant; must not be {@code null}
     * @param displayName a human-readable label for the tenant; must not be {@code null}
     * @throws IllegalArgumentException if {@code tenantId} or {@code displayName} is null
     * @throws RuntimeException         (implementation-defined subtype) if {@code tenantId} is
     *                                  already registered — no silent overwrite (AC6 of E14S02)
     * @see <a href="../../../../../../../../docs/governance/stories/E14S02.story.md">Story E14S02 AC6, AC-CONCURRENT-REGISTER</a>
     */
    void register(UUID tenantId, String displayName);

    /**
     * Returns all currently registered tenants.
     *
     * <p>The returned list is a snapshot of the registry at the time of the call.
     * Concurrent modifications after this method returns are not reflected.
     *
     * @return an unmodifiable list of all {@link TenantRecord}s; never {@code null}; may be empty
     * @see <a href="../../../../../../../../docs/governance/stories/E14S02.story.md">Story E14S02 AC1 enumeration</a>
     */
    List<TenantRecord> findAll();

    /**
     * An immutable record representing a registered tenant entry in the registry.
     *
     * <p>The tenant identifier is UUID-based per DEC-17. The {@code displayName} is a
     * human-readable label for the tenant (e.g., "Default (LAN)").
     *
     * @param tenantId    the tenant's UUID; never {@code null}
     * @param displayName the human-readable tenant label; never {@code null}
     */
    record TenantRecord(UUID tenantId, String displayName) {

        /**
         * Compact canonical constructor — validates invariants.
         *
         * @throws IllegalArgumentException if {@code tenantId} or {@code displayName} is null
         */
        public TenantRecord {
            if (tenantId == null) {
                throw new IllegalArgumentException("TenantRecord.tenantId must not be null");
            }
            if (displayName == null) {
                throw new IllegalArgumentException("TenantRecord.displayName must not be null");
            }
        }
    }
}
