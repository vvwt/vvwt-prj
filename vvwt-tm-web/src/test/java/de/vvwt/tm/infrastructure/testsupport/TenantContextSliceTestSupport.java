package de.vvwt.tm.infrastructure.testsupport;

import static org.mockito.Mockito.when;

import de.vvwt.tm.tenant.TenantContext;
import java.util.UUID;

/**
 * Poka-Yoke utility for slice-layer TenantContext stubbing in {@code @WebMvcTest} tests (E20S02,
 * AC6).
 *
 * <h2>Purpose</h2>
 *
 * <p>Provides the minimum API needed by REST controller slice tests (using {@code @WebMvcTest})
 * that need to assert TenantContext-related controller behaviour without loading a full Spring
 * context. The utility configures a {@code @MockitoBean TenantContext} mock so that its {@code
 * current()} method returns the desired tenant UUID.
 *
 * <h2>Stub target decision (AC6a)</h2>
 *
 * <p>The stub target is {@link TenantContext} (the public-API bearer). This is the lower-coupling
 * option: it does NOT cross the {@code tenant.internal} boundary, unlike stubbing {@code
 * TenantContextResolver} which lives at {@code de.vvwt.tm.tenant.internal.*}. The stub configures
 * the {@code MockitoBean} mock's {@code current()} return value — controllers that inject {@code
 * TenantContext} and call {@code current()} will see the configured UUID.
 *
 * <h2>DEC-20 invariant (AC6b)</h2>
 *
 * <p>Cross-tenant access is not possible through this utility: each mock configuration is tied to a
 * specific test method (or {@code @BeforeEach}), and the mock is re-created per test class context.
 * A slice-test holding tenant-A identity cannot accidentally see tenant-B resources because the
 * controller's service collaborators are all {@code @MockitoBean}s — no real database is involved
 * in the slice layer.
 *
 * <h2>Design constraints (AC6c, DEC-26 precedent)</h2>
 *
 * <ul>
 *   <li>{@code final} class — no inheritance-based extension.
 *   <li>Private constructor — utility class pattern; static methods only.
 *   <li>Minimum API: only what AC2 and AC3 slice tests need. Growth is story-gated.
 * </ul>
 *
 * <h2>Co-location</h2>
 *
 * <p>Located at {@code de.vvwt.tm.infrastructure.testsupport} alongside {@link TenantDaoTestSupport}
 * per AC6 and DEC-26 test-utility-convention precedent. This is intentionally a different location
 * from {@link de.vvwt.tm.tenant.TenantContextTestSupport}, which is oriented toward full-context
 * {@code @SpringBootTest} ITs.
 *
 * @see TenantDaoTestSupport
 * @see de.vvwt.tm.tenant.TenantContextTestSupport
 */
public final class TenantContextSliceTestSupport {

    private TenantContextSliceTestSupport() {
        // utility class — no instances
    }

    /**
     * Configures the given {@link TenantContext} mock so that {@link TenantContext#current()}
     * returns the specified {@code tenantId}.
     *
     * <p>Intended for use in {@code @WebMvcTest} test classes that declare:
     *
     * <pre>{@code
     * @MockitoBean TenantContext tenantContext;
     *
     * @BeforeEach
     * void setUp() {
     *     TenantContextSliceTestSupport.configureMock(tenantContext, UUID.randomUUID());
     * }
     * }</pre>
     *
     * <p>Does not configure {@link TenantContext#bind(UUID)} — slice tests do not call {@code
     * bind()} directly; the controller accesses {@code current()} only.
     *
     * @param tenantContextMock the Mockito mock for {@link TenantContext} (from {@code
     *     @MockitoBean}); must not be {@code null}
     * @param tenantId the UUID that {@code current()} should return; must not be {@code null}
     */
    public static void configureMock(TenantContext tenantContextMock, UUID tenantId) {
        when(tenantContextMock.current()).thenReturn(tenantId);
    }
}
