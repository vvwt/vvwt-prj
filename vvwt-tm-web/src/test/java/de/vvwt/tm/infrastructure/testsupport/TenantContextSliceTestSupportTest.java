package de.vvwt.tm.infrastructure.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.vvwt.tm.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TenantContextSliceTestSupport} (E20S02, AC6).
 *
 * <p>Verifies the slice-layer TenantContext stub utility contract:
 *
 * <ul>
 *   <li>configureMock() stubs {@code TenantContext.current()} to return the provided tenant UUID
 *   <li>The utility does NOT cross the {@code tenant.internal} boundary (uses public API only)
 *   <li>{@code final} class, private constructor (DEC-26 precedent)
 * </ul>
 *
 * <p>DEC-22 Iron Law: these tests were written RED before {@link TenantContextSliceTestSupport}
 * existed.
 *
 * @see TenantContextSliceTestSupport
 */
@DisplayName("TenantContextSliceTestSupport unit tests — E20S02 AC6")
class TenantContextSliceTestSupportTest {

    @Test
    @DisplayName("configureMock() stubs TenantContext.current() to return the given tenant UUID")
    void configureMockStubsCurrentToReturnGivenTenantId() {
        TenantContext tenantContext = mock(TenantContext.class);
        UUID tenantId = UUID.randomUUID();

        TenantContextSliceTestSupport.configureMock(tenantContext, tenantId);

        assertThat(tenantContext.current())
                .as("TenantContext.current() should return the configured tenant UUID")
                .isEqualTo(tenantId);
    }

    @Test
    @DisplayName("configureMock() with different UUIDs each produces independent stubs")
    void configureMockWithDifferentUuidsProducesIndependentStubs() {
        TenantContext ctx1 = mock(TenantContext.class);
        TenantContext ctx2 = mock(TenantContext.class);
        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();

        TenantContextSliceTestSupport.configureMock(ctx1, tenant1);
        TenantContextSliceTestSupport.configureMock(ctx2, tenant2);

        assertThat(ctx1.current()).isEqualTo(tenant1);
        assertThat(ctx2.current()).isEqualTo(tenant2);
        assertThat(ctx1.current()).isNotEqualTo(ctx2.current());
    }

    @Test
    @DisplayName("TenantContextSliceTestSupport is not instantiable (utility class contract)")
    void isNotInstantiable() throws Exception {
        var constructors = TenantContextSliceTestSupport.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(java.lang.reflect.Modifier.isPrivate(constructors[0].getModifiers()))
                .as("Constructor should be private")
                .isTrue();
    }
}
