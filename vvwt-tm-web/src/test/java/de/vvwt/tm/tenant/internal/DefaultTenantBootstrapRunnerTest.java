package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tenant.TenantRegistryPort.TenantRecord;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultTenantBootstrapRunner}.
 *
 * <p>Covers AC1 (TDD test-first), AC2 (first-start behaviour), AC3 (subsequent-start no-op),
 * AC4 (UUID generated+persisted), AC5 (Flyway failure → no registry entry), AC6
 * (concurrent first-start), AC7 (ApplicationModulesTest — verified in final mvn verify step),
 * AC8 (no auth coupling — structural), AC-ORPHAN-RECOVERY, AC-PARALLEL-PHASE (structural),
 * AC11 (JdbcTemplate-based idempotency guard — read-before-generate for UUID reconciliation),
 * AC12 (transactional consistency of the guard).
 *
 * <p>The test class uses {@code @TempDir} for filesystem isolation. Mocks are used for
 * {@link TenantRegistryPort} and {@link PerTenantFlywayRunner} where real behavior is not
 * the focus. Real file operations use {@link TempDir}.
 *
 * @see DefaultTenantBootstrapRunner
 * @see <a href="../../../../../../../../docs/governance/stories/E14S05.story.md">Story E14S05</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E14S12.story.md">Story E14S12 (AC11/AC12)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22</a>
 */
@ExtendWith(MockitoExtension.class)
class DefaultTenantBootstrapRunnerTest {

    @Mock
    private TenantRegistryPort registry;

    @Mock
    private PerTenantFlywayRunner flywayRunner;

    @Mock
    private JdbcTemplate sharedJdbcTemplate;

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // AC2 — first-start: registers default tenant + runs Flyway
    // -------------------------------------------------------------------------

    /**
     * AC2 / AC1: First start with an empty registry — bootstrap registers the default tenant
     * and runs Flyway for that tenant's database.
     */
    @Test
    void firstStart_registersDefaultTenantAndRunsFlyway() throws Exception {
        when(registry.findAll()).thenReturn(List.of());
        // AC11: JPA table also empty on fresh start → runner generates new UUID
        when(sharedJdbcTemplate.queryForObject(any(String.class), eq(String.class)))
                .thenReturn(null);

        DefaultTenantBootstrapRunner runner = new DefaultTenantBootstrapRunner(
                registry, flywayRunner, tempDir, sharedJdbcTemplate);

        runner.run(null);

        ArgumentCaptor<UUID> uuidCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(registry).register(uuidCaptor.capture(), eq("Default (LAN)"));

        UUID registeredId = uuidCaptor.getValue();
        assertThat(registeredId)
                .as("Registered UUID must not be null")
                .isNotNull();
        assertThat(registeredId)
                .as("Registered UUID must not be the nil UUID")
                .isNotEqualTo(new UUID(0L, 0L));

        // Verify Flyway was run after registration (AC2)
        verify(flywayRunner, times(1)).runWithDataSource(eq(registeredId), any());
    }

    // -------------------------------------------------------------------------
    // AC3 — subsequent-start: skips registration and Flyway
    // -------------------------------------------------------------------------

    /**
     * AC3: If the registry already contains a "Default (LAN)" tenant, bootstrap is a no-op —
     * no new registration, no Flyway run, no directory creation.
     */
    @Test
    void subsequentStart_skipsRegistrationAndFlyway() throws Exception {
        UUID existingId = UUID.randomUUID();
        when(registry.findAll()).thenReturn(
                List.of(new TenantRecord(existingId, "Default (LAN)")));

        DefaultTenantBootstrapRunner runner = new DefaultTenantBootstrapRunner(
                registry, flywayRunner, tempDir, sharedJdbcTemplate);

        runner.run(null);

        verify(registry, never()).register(any(), anyString());
        verify(flywayRunner, never()).runWithDataSource(any(), any());
    }

    // -------------------------------------------------------------------------
    // AC4 — UUID generated+persisted via UUID.randomUUID()
    // -------------------------------------------------------------------------

    /**
     * AC4: Two separate bootstrap instances operating on fresh registries generate different UUIDs.
     * This is the regression test for DEC-17 "no hardcoded UUID" requirement.
     * With AC11: only applies when JPA table is empty (orElseGet path).
     */
    @Test
    void firstStart_generatesDifferentUUIDsForSeparateInstances(@TempDir Path tempDirB) throws Exception {
        // Instance A
        TenantRegistryPort registryA = org.mockito.Mockito.mock(TenantRegistryPort.class);
        when(registryA.findAll()).thenReturn(List.of());
        PerTenantFlywayRunner flywayA = org.mockito.Mockito.mock(PerTenantFlywayRunner.class);
        JdbcTemplate jdbcA = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbcA.queryForObject(any(String.class), eq(String.class))).thenReturn(null);
        DefaultTenantBootstrapRunner runnerA = new DefaultTenantBootstrapRunner(
                registryA, flywayA, tempDir, jdbcA);

        // Instance B
        TenantRegistryPort registryB = org.mockito.Mockito.mock(TenantRegistryPort.class);
        when(registryB.findAll()).thenReturn(List.of());
        PerTenantFlywayRunner flywayB = org.mockito.Mockito.mock(PerTenantFlywayRunner.class);
        JdbcTemplate jdbcB = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbcB.queryForObject(any(String.class), eq(String.class))).thenReturn(null);
        DefaultTenantBootstrapRunner runnerB = new DefaultTenantBootstrapRunner(
                registryB, flywayB, tempDirB, jdbcB);

        runnerA.run(null);
        runnerB.run(null);

        ArgumentCaptor<UUID> captorA = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> captorB = ArgumentCaptor.forClass(UUID.class);
        verify(registryA).register(captorA.capture(), any());
        verify(registryB).register(captorB.capture(), any());

        assertThat(captorA.getValue())
                .as("Two independently bootstrapped instances must generate different UUIDs (DEC-17 amendment)")
                .isNotEqualTo(captorB.getValue());
    }

    // -------------------------------------------------------------------------
    // AC5 — Flyway failure does NOT leave registry entry
    // -------------------------------------------------------------------------

    /**
     * AC5: If Flyway migration fails during default-tenant creation, the registry entry is NOT
     * created. The application startup must fail fast with an actionable message.
     */
    @Test
    void flywayFailure_doesNotLeaveRegistryEntry() throws Exception {
        when(registry.findAll()).thenReturn(List.of());
        when(sharedJdbcTemplate.queryForObject(any(String.class), eq(String.class))).thenReturn(null);
        doThrow(new FlywayException("simulated migration failure"))
                .when(flywayRunner).runWithDataSource(any(), any());

        DefaultTenantBootstrapRunner runner = new DefaultTenantBootstrapRunner(
                registry, flywayRunner, tempDir, sharedJdbcTemplate);

        assertThatThrownBy(() -> runner.run(null))
                .as("Bootstrap must propagate the Flyway exception (fail-fast AC5)")
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("simulated migration failure");

        // Registry must NOT have been updated (AC5: no incomplete DB entry)
        verify(registry, never()).register(any(), anyString());
    }

    // -------------------------------------------------------------------------
    // AC-ORPHAN-RECOVERY — orphan directory detected and cleaned
    // -------------------------------------------------------------------------

    /**
     * AC-ORPHAN-RECOVERY: If a prior start created the tenant directory but the registry entry
     * was never persisted (crash between directory creation and registry write), the bootstrap
     * on the next start detects the orphan, cleans it, and retries the full flow.
     */
    @Test
    void orphanDirectory_isCleanedAndBootstrapRetries() throws Exception {
        // Arrange: create an orphan directory (simulates a crash after dir creation, before registration)
        UUID orphanId = UUID.randomUUID();
        Path orphanDir = tempDir.resolve("tenants").resolve(orphanId.toString());
        Files.createDirectories(orphanDir);

        // Registry is empty (registration never completed)
        when(registry.findAll()).thenReturn(List.of());
        when(sharedJdbcTemplate.queryForObject(any(String.class), eq(String.class))).thenReturn(null);

        DefaultTenantBootstrapRunner runner = new DefaultTenantBootstrapRunner(
                registry, flywayRunner, tempDir, sharedJdbcTemplate);

        // Act: should not throw
        runner.run(null);

        // Assert: orphan directory is gone
        assertThat(orphanDir)
                .as("Orphan directory must be cleaned up before fresh bootstrap")
                .doesNotExist();

        // Assert: registry was populated (bootstrap completed)
        verify(registry, times(1)).register(any(UUID.class), eq("Default (LAN)"));

        // Assert: Flyway was run for the NEW tenant (not the orphan)
        verify(flywayRunner, times(1)).runWithDataSource(any(UUID.class), any());
    }

    /**
     * AC-ORPHAN-RECOVERY: Orphan directory is removed even when Flyway subsequently fails.
     * The orphan cleanup is unconditional; the subsequent failure is surfaced normally.
     */
    @Test
    void orphanDirectory_isCleanedEvenWhenFlywayFails() throws Exception {
        UUID orphanId = UUID.randomUUID();
        Path orphanDir = tempDir.resolve("tenants").resolve(orphanId.toString());
        Files.createDirectories(orphanDir);

        when(registry.findAll()).thenReturn(List.of());
        when(sharedJdbcTemplate.queryForObject(any(String.class), eq(String.class))).thenReturn(null);
        doThrow(new FlywayException("migration failure after orphan cleanup"))
                .when(flywayRunner).runWithDataSource(any(), any());

        DefaultTenantBootstrapRunner runner = new DefaultTenantBootstrapRunner(
                registry, flywayRunner, tempDir, sharedJdbcTemplate);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(FlywayException.class);

        // Orphan was still cleaned before the retry
        assertThat(orphanDir)
                .as("Orphan directory must be cleaned even when retry Flyway fails")
                .doesNotExist();
    }

    // -------------------------------------------------------------------------
    // AC8 — no auth coupling (structural)
    // -------------------------------------------------------------------------

    /**
     * AC8 (structural): The {@link DefaultTenantBootstrapRunner} class must not import any
     * {@code de.vvwt.tm.auth.*} type. This test uses reflection to verify the absence of
     * any auth-package dependency by checking the class's declared fields and method signatures.
     *
     * <p>The primary enforcement is structural (compilation + package isolation). This test
     * serves as an automated documentation check.
     */
    @Test
    void noAuthPackageImport_structural() {
        // Check the declaring package of DefaultTenantBootstrapRunner
        String runnerPackage = DefaultTenantBootstrapRunner.class.getPackageName();
        assertThat(runnerPackage)
                .as("DefaultTenantBootstrapRunner must live in tenant.internal")
                .isEqualTo("de.vvwt.tm.tenant.internal");

        // Verify no field references an auth type
        for (var field : DefaultTenantBootstrapRunner.class.getDeclaredFields()) {
            assertThat(field.getType().getName())
                    .as("Field %s must not reference an auth package type", field.getName())
                    .doesNotStartWith("de.vvwt.tm.auth.");
        }

        // Verify no constructor or method parameter type references auth
        for (var ctor : DefaultTenantBootstrapRunner.class.getDeclaredConstructors()) {
            for (var paramType : ctor.getParameterTypes()) {
                assertThat(paramType.getName())
                        .as("Constructor parameter type must not reference auth package")
                        .doesNotStartWith("de.vvwt.tm.auth.");
            }
        }
    }

    // -------------------------------------------------------------------------
    // AC-PARALLEL-PHASE — legacy DefaultTenantBootstrap is not touched (structural)
    // -------------------------------------------------------------------------

    /**
     * AC-PARALLEL-PHASE (structural): The legacy {@code de.vvwt.tm.tenant.DefaultTenantBootstrap}
     * class is NOT modified by this story. During the parallel phase both ApplicationRunner beans
     * coexist on staging. This is documented and accepted per DEC-21 + Brief T-2.
     *
     * <p>This test verifies the legacy class is still loadable — it was not deleted or renamed.
     */
    @Test
    void legacyDefaultTenantBootstrap_stillExists() throws Exception {
        Class<?> legacyClass = Class.forName("de.vvwt.tm.tenant.DefaultTenantBootstrap");
        assertThat(legacyClass)
                .as("Legacy DefaultTenantBootstrap must still exist (AC-PARALLEL-PHASE: not removed until E14S07)")
                .isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC6 — concurrent first-start with REAL registry (integration-style unit test)
    // -------------------------------------------------------------------------

    /**
     * AC6: Two threads concurrently running the bootstrap against the same real
     * {@link TenantFileRegistry} must result in exactly one registered tenant.
     *
     * <p>Uses a real {@link TenantFileRegistry} (not a mock) to exercise the synchronized
     * register() + DuplicateTenantException handling in {@link DefaultTenantBootstrapRunner}.
     * AC11: both runners see an empty JPA table (null from SELECT) → each generates own UUID.
     */
    @Test
    void concurrentFirstStart_exactlyOneRegistration() throws Exception {
        TenantRegistryPort realRegistry = new TenantFileRegistry(tempDir);
        // No tempDir prefix needed — TenantFileRegistry creates its own structure

        // Two separate data dirs to avoid directory conflicts between the two runners
        Path dataDirA = Files.createTempDirectory(tempDir, "concurrent-A-");
        Path dataDirB = Files.createTempDirectory(tempDir, "concurrent-B-");

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        // AC11: JPA empty for concurrent test (both see null → each picks own UUID; registry race decides winner)
        JdbcTemplate jdbcConcA = org.mockito.Mockito.mock(JdbcTemplate.class);
        JdbcTemplate jdbcConcB = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(jdbcConcA.queryForObject(any(String.class), eq(String.class))).thenReturn(null);
        org.mockito.Mockito.when(jdbcConcB.queryForObject(any(String.class), eq(String.class))).thenReturn(null);

        DefaultTenantBootstrapRunner runnerA = new DefaultTenantBootstrapRunner(realRegistry, flywayRunner, dataDirA, jdbcConcA);
        DefaultTenantBootstrapRunner runnerB = new DefaultTenantBootstrapRunner(realRegistry, flywayRunner, dataDirB, jdbcConcB);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> futureA = executor.submit(() -> {
            try {
                startGate.await();
                runnerA.run(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            }
        });

        Future<?> futureB = executor.submit(() -> {
            try {
                startGate.await();
                runnerB.run(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            }
        });

        startGate.countDown();
        futureA.get(10, TimeUnit.SECONDS);
        futureB.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(exceptionCount.get())
                .as("No unexpected exceptions during concurrent bootstrap (AC6)")
                .isZero();

        List<TenantRecord> allTenants = realRegistry.findAll();
        assertThat(allTenants)
                .as("Exactly one default tenant must be registered after concurrent race (AC6)")
                .hasSize(1);

        assertThat(allTenants.get(0).displayName())
                .as("The registered tenant must be the default tenant")
                .isEqualTo("Default (LAN)");
    }

    // =========================================================================
    // AC11 — JdbcTemplate idempotency guard (read-before-generate)
    // =========================================================================

    /**
     * AC11 path (a): JPA TENANTS table already has a row → runner uses THAT UUID
     * (UUID reconciliation: legacy DefaultTenantBootstrap wrote UUID-A to JPA first;
     * this runner picks it up instead of generating UUID-B).
     *
     * <p>Story: E14S12 AC11.
     */
    @Test
    void ac11_jpaTenantExists_runnerUsesExistingUuid() throws Exception {
        UUID existingJpaUuid = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        when(registry.findAll()).thenReturn(List.of());
        // AC11: JPA has an existing tenant row → return its UUID as String
        when(sharedJdbcTemplate.queryForObject(any(String.class), eq(String.class)))
                .thenReturn(existingJpaUuid.toString());

        DefaultTenantBootstrapRunner runner = new DefaultTenantBootstrapRunner(
                registry, flywayRunner, tempDir, sharedJdbcTemplate);

        runner.run(null);

        // The runner must register the UUID-from-JPA, not a randomly generated one
        ArgumentCaptor<UUID> uuidCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(registry).register(uuidCaptor.capture(), eq("Default (LAN)"));
        assertThat(uuidCaptor.getValue())
                .as("AC11: runner must use the UUID already in JPA TENANTS table (not generate a new one)")
                .isEqualTo(existingJpaUuid);
    }

    /**
     * AC11 path (b): JPA TENANTS table is empty (true fresh-start) →
     * runner generates a new UUID via UUID.randomUUID().
     *
     * <p>Story: E14S12 AC11.
     */
    @Test
    void ac11_jpaEmpty_runnerGeneratesNewUuid() throws Exception {
        when(registry.findAll()).thenReturn(List.of());
        // AC11: JPA empty → queryForObject returns null (no row)
        when(sharedJdbcTemplate.queryForObject(any(String.class), eq(String.class)))
                .thenReturn(null);

        DefaultTenantBootstrapRunner runner = new DefaultTenantBootstrapRunner(
                registry, flywayRunner, tempDir, sharedJdbcTemplate);

        runner.run(null);

        // Runner must have registered some non-null UUID (generated by UUID.randomUUID())
        ArgumentCaptor<UUID> uuidCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(registry).register(uuidCaptor.capture(), eq("Default (LAN)"));
        assertThat(uuidCaptor.getValue())
                .as("AC11: runner must generate a non-null UUID when JPA table is empty")
                .isNotNull();
    }

    /**
     * AC11 path (c): JPA SELECT throws DataAccessException (connectivity issue) →
     * runner must surface a typed error and must NOT write the file registry with a guessed UUID.
     *
     * <p>Story: E14S12 AC11.
     */
    @Test
    void ac11_jpaQueryThrows_runnerSurfacesErrorAndDoesNotRegister() throws Exception {
        when(registry.findAll()).thenReturn(List.of());
        // AC11: JPA unavailable → DataAccessException
        when(sharedJdbcTemplate.queryForObject(any(String.class), eq(String.class)))
                .thenThrow(new org.springframework.dao.TransientDataAccessException("connection failed") {});

        DefaultTenantBootstrapRunner runner = new DefaultTenantBootstrapRunner(
                registry, flywayRunner, tempDir, sharedJdbcTemplate);

        assertThatThrownBy(() -> runner.run(null))
                .as("AC11: JPA query failure must propagate as a typed error — no registry write with guessed UUID")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency guard");

        // Registry must NOT have been updated
        verify(registry, never()).register(any(), anyString());
    }

    /**
     * AC12: null JdbcTemplate constructor guard — must throw IllegalArgumentException.
     */
    @Test
    void ac12_nullJdbcTemplate_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new DefaultTenantBootstrapRunner(registry, flywayRunner, tempDir, null))
                .as("AC12: null JdbcTemplate must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sharedJdbcTemplate must not be null");
    }
}
