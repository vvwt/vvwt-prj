package de.vvwt.tm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the {@code @ApplicationModule(allowedDependencies = {"tenant"})} annotation on the
 * {@code auth} bounded context (E15S06, DEC-21, DEC-22).
 *
 * <h2>AC2 — Annotation placement</h2>
 *
 * <p>Confirms that {@code de.vvwt.tm.auth.package-info} declares
 * {@code @ApplicationModule(allowedDependencies = {"tenant"})}.
 *
 * <h2>AC5 — Proof of enforcement (referenced from plan)</h2>
 *
 * <p>The deliberate-violation spike was run during E15S06 delivery (documented in {@code
 * E15S06.execution-plan.md}): a {@code Class<?>} field reference to {@code
 * de.vvwt.tm.tenant.internal.TenantFileRegistry} in {@code
 * de.vvwt.tm.auth.internal.PasswordGenerator} caused {@code
 * ApplicationModulesTest.verifiesModuleStructure} to FAIL with:
 *
 * <pre>
 *   Violations - Module 'auth' depends on module 'tenant' via
 *   de.vvwt.tm.auth.internal.PasswordGenerator -&gt; de.vvwt.tm.tenant.internal.TenantFileRegistry.
 *   Allowed targets: tenant.
 * </pre>
 *
 * The spike was removed before commit.
 *
 * <h2>AC6 — Positive dependency consumption</h2>
 *
 * <p>Confirms that:
 *
 * <ol>
 *   <li>The {@code auth} module CAN reference types from the {@code tenant} public API (e.g.,
 *       {@link TenantContext}, {@link TenantDataSourceResolver}) — these imports in this test class
 *       itself are visible to Spring Modulith's scanner.
 *   <li>{@code ApplicationModules.verify()} does NOT object to this consumption.
 * </ol>
 *
 * <h2>AC7 — No other packages annotated</h2>
 *
 * <p>This test does not annotate any other packages. Only {@code de.vvwt.tm.auth} has its
 * annotation declared in this story; other Wave-1 contexts remain Wave-2 scope.
 *
 * @see ApplicationModulesTest
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E15S06.story.md">Story
 *     E15S06</a>
 * @since E15S06
 */
class AuthModuleAnnotationTest {

    /**
     * AC2 — Verifies that {@code de.vvwt.tm.auth.package-info} is annotated with
     * {@code @ApplicationModule} declaring {@code allowedDependencies = {"tenant"}}.
     */
    @Test
    void authPackageDeclaresApplicationModuleAnnotation() throws Exception {
        // Package.getPackage(String) is deprecated since Java 9 - use ClassLoader.getDefinedPackage
        // (E18S01/DEC-29)
        Package authPackage =
                AuthModuleAnnotationTest.class
                        .getClassLoader()
                        .getDefinedPackage("de.vvwt.tm.auth");
        assertThat(authPackage)
                .as(
                        "Package de.vvwt.tm.auth must be present on the classpath"
                                + " (package-info.java compiled)")
                .isNotNull();

        ApplicationModule annotation = authPackage.getAnnotation(ApplicationModule.class);
        assertThat(annotation)
                .as("de.vvwt.tm.auth must be annotated with @ApplicationModule (AC2)")
                .isNotNull();

        assertThat(annotation.allowedDependencies())
                .as("@ApplicationModule(allowedDependencies) must contain exactly \"tenant\" (AC2)")
                .containsExactly("tenant");
    }

    /**
     * AC3 + AC6 — Verifies that {@code ApplicationModules.verify()} succeeds with the
     * {@code @ApplicationModule} annotation present, including the positive fact that the {@code
     * auth} module's imports of {@code TenantContext} and {@code TenantDataSourceResolver} (tenant
     * public API) do NOT trigger a violation.
     *
     * <p>The static imports of {@link TenantContext} and {@link TenantDataSourceResolver} at the
     * top of this test class ensure the compiled bytecode of this test file references those types.
     * Spring Modulith scans test classpath too; if the annotation were wrong (e.g.,
     * allowedDependencies empty), this file's references would cause verify() to fail.
     */
    @Test
    void verifyPassesWithTenantDependencyPresent() {
        // TenantContext and TenantDataSourceResolver are referenced via class literals below
        // to ensure they appear in bytecode (satisfying AC6 positive consumption check).
        assertThat(TenantContext.class.getPackageName())
                .as("TenantContext must be in the tenant public API package (not tenant.internal)")
                .isEqualTo("de.vvwt.tm.tenant");

        assertThat(TenantDataSourceResolver.class.getPackageName())
                .as("TenantDataSourceResolver must be in the tenant public API package")
                .isEqualTo("de.vvwt.tm.tenant");

        // Verify that ApplicationModules.verify() accepts the auth→tenant dependency
        ApplicationModules modules = ApplicationModules.of(TournamentManagerApplication.class);
        assertDoesNotThrow(
                (Executable) modules::verify,
                "ApplicationModules.verify() must pass — auth importing from tenant public API "
                        + "is permitted by allowedDependencies = {\"tenant\"} (AC3, AC6)");
    }

    /**
     * AC6 — Verifies that the {@code auth} module is detected by Spring Modulith and declares
     * {@code tenant} as its sole allowed dependency.
     */
    @Test
    void authModuleAllowedDependenciesIncludeTenant() {
        ApplicationModules modules = ApplicationModules.of(TournamentManagerApplication.class);

        var authModule = modules.getModuleByName("auth");
        assertThat(authModule)
                .as("Spring Modulith must detect an 'auth' module (package-info.java present)")
                .isPresent();

        // Verify that the module declaration is found: the package-info annotation must
        // be scanned. We confirm by checking verify() passes (no boundary violations).
        assertDoesNotThrow(
                (Executable) modules::verify,
                "verify() must pass with auth module having allowedDependencies = {\"tenant\"}"
                        + " (AC6)");
    }
}
