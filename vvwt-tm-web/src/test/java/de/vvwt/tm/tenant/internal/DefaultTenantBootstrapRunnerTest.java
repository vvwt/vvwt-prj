package de.vvwt.tm.tenant.internal;

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

import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tenant.TenantRegistryPort.TenantRecord;
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
import javax.sql.DataSource;
import org.flywaydb.core.api.FlywayException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit tests for {@link DefaultTenantBootstrapRunner}.
 *
 * <p>Covers AC1 (TDD test-first), AC2 (first-start behaviour), AC3 (subsequent-start no-op), AC4
 * (UUID generated+persisted), AC5 (Flyway failure → no registry entry), AC6 (concurrent
 * first-start), AC7 (ApplicationModulesTest — verified in final mvn verify step), AC8 (no auth
 * coupling — structural), AC-ORPHAN-RECOVERY, AC11 (JdbcTemplate-based idempotency guard —
 * read-before-generate for UUID reconciliation), AC12 (transactional consistency of the guard).
 *
 * <p>The test class uses {@code @TempDir} for filesystem isolation. Mocks are used for {@link
 * TenantRegistryPort} and {@link PerTenantFlywayRunner} where real behavior is not the focus. Real
 * file operations use {@link TempDir}.
 *
 * @see DefaultTenantBootstrapRunner
 * @see <a href="../../../../../../../../docs/governance/stories/E14S05.story.md">Story E14S05</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E14S07.story.md">Story E14S07
 *     (atomic cutover)</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E14S12.story.md">Story E14S12
 *     (AC11/AC12)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22</a>
 */
@ExtendWith(MockitoExtension.class)
class DefaultTenantBootstrapRunnerTest {

    @Mock private TenantRegistryPort registry;

    @Mock private PerTenantFlywayRunner flywayRunner;

    @Mock private TenantDataSourceResolver tenantDataSourceResolver;

    @Mock private JdbcTemplate sharedJdbcTemplate;

    @Mock private TransactionTemplate transactionTemplate;

    @TempDir Path tempDir;

    /**
     * Default stub: the class-level {@code tenantDataSourceResolver} mock returns a fresh no-schema
     * in-memory H2 DataSource for any tenant UUID. When {@link
     * DefaultTenantBootstrapRunner#upsertDbTenantRow} runs against this DataSource it catches
     * {@link org.springframework.jdbc.BadSqlGrammarException} (table not found) and silently skips
     * — the no-op path. This is the correct unit-test behaviour: the per-tenant DB row upsert is an
     * integration concern exercised in {@code @SpringBootTest} tests.
     */
    @BeforeEach
    void stubTenantDataSourceResolver() {
        org.mockito.Mockito.lenient()
                .when(tenantDataSourceResolver.resolve(any()))
                .thenAnswer(inv -> newNoSchemaH2());
    }

    /**
     * Creates a fresh in-memory H2 DataSource (no schema). Used as the return value of {@code
     * tenantDataSourceResolver.resolve()} in unit tests. When {@link
     * DefaultTenantBootstrapRunner#upsertDbTenantRow} runs against this DataSource it will catch
     * {@link org.springframework.jdbc.BadSqlGrammarException} (table not found) and silently skip —
     * the no-op path in the catch block.
     */
    private static DataSource newNoSchemaH2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:unit-test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    // -------------------------------------------------------------------------
    // AC2 — first-start: registers default tenant + runs Flyway
    // -------------------------------------------------------------------------

    /**
     * AC2 / AC1: First start with an empty registry — bootstrap registers the default tenant and
     * runs Flyway for that tenant's database.
     */
    @Test
    void firstStart_registersDefaultTenantAndRunsFlyway() throws Exception {
        when(registry.findAll()).thenReturn(List.of());
        // AC11: JPA table empty on fresh start → queryForList returns empty list → runner generates
        // new UUID
        when(sharedJdbcTemplate.queryForList(any(String.class), eq(String.class)))
                .thenReturn(List.of());
        // upsertMainDbTenantRow: row not present yet (COUNT=0)
        when(sharedJdbcTemplate.queryForObject(
                        any(String.class), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        DefaultTenantBootstrapRunner runner =
                new DefaultTenantBootstrapRunner(
                        registry,
                        flywayRunner,
                        tenantDataSourceResolver,
                        tempDir,
                        sharedJdbcTemplate,
                        transactionTemplate);

        runner.run(null);

        ArgumentCaptor<UUID> uuidCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(registry).register(uuidCaptor.capture(), eq("Default (LAN)"));

        UUID registeredId = uuidCaptor.getValue();
        assertThat(registeredId).as("Registered UUID must not be null").isNotNull();
        assertThat(registeredId)
                .as("Registered UUID must not be the nil UUID")
                .isNotEqualTo(new UUID(0L, 0L));

        // Verify Flyway was run: once on fileDataSource (step 6, pre-registration) and once on
        // the resolver's DataSource (step 7b, post-registration) — total 2 invocations (AC2).
        verify(flywayRunner, times(2)).runWithDataSource(eq(registeredId), any());
    }

    // -------------------------------------------------------------------------
    // AC3 — subsequent-start: skips registration and Flyway
    // -------------------------------------------------------------------------

    /**
     * AC3 (E14S11 amendment): If the registry already contains a "Default (LAN)" tenant, bootstrap
     * skips registration and directory creation, but DOES run Flyway idempotently via {@link
     * PerTenantFlywayRunner#run(UUID)}.
     *
     * <p>The idempotent Flyway re-run on subsequent starts (E14S11) ensures that per-tenant DBs
     * created before wave-1 migration bootstrap (empty Flyway locations) receive all pending
     * migrations on the next application start. Flyway's own checksum guard makes this a no-op for
     * already-fully-migrated databases.
     */
    @Test
    void subsequentStart_skipsRegistrationButRunsFlyway() throws Exception {
        UUID existingId = UUID.randomUUID();
        when(registry.findAll()).thenReturn(List.of(new TenantRecord(existingId, "Default (LAN)")));
        // upsertMainDbTenantRow called even on subsequent start (idempotent)
        when(sharedJdbcTemplate.queryForObject(
                        any(String.class), eq(Integer.class), any(Object[].class)))
                .thenReturn(1); // row already present

        DefaultTenantBootstrapRunner runner =
                new DefaultTenantBootstrapRunner(
                        registry,
                        flywayRunner,
                        tenantDataSourceResolver,
                        tempDir,
                        sharedJdbcTemplate,
                        transactionTemplate);

        runner.run(null);

        // No new registration — tenant was already registered
        verify(registry, never()).register(any(), anyString());
        // No direct runWithDataSource — the existing-tenant path uses run(UUID) via the resolver
        verify(flywayRunner, never()).runWithDataSource(any(), any());
        // E14S11: Flyway IS run idempotently via run(UUID) to apply pending migrations
        verify(flywayRunner, times(1)).run(eq(existingId));
    }

    // -------------------------------------------------------------------------
    // AC4 — UUID generated+persisted via UUID.randomUUID()
    // -------------------------------------------------------------------------

    /**
     * AC4: Two separate bootstrap instances operating on fresh registries generate different UUIDs.
     * This is the regression test for DEC-17 "no hardcoded UUID" requirement. With AC11: only
     * applies when JPA table is empty (orElseGet path).
     */
    @Test
    void firstStart_generatesDifferentUUIDsForSeparateInstances(@TempDir Path tempDirB)
            throws Exception {
        // Instance A
        TenantRegistryPort registryA = org.mockito.Mockito.mock(TenantRegistryPort.class);
        when(registryA.findAll()).thenReturn(List.of());
        PerTenantFlywayRunner flywayA = org.mockito.Mockito.mock(PerTenantFlywayRunner.class);
        TenantDataSourceResolver resolverA =
                org.mockito.Mockito.mock(TenantDataSourceResolver.class);
        org.mockito.Mockito.when(resolverA.resolve(any())).thenAnswer(inv -> newNoSchemaH2());
        JdbcTemplate jdbcA = org.mockito.Mockito.mock(JdbcTemplate.class);
        TransactionTemplate txA = org.mockito.Mockito.mock(TransactionTemplate.class);
        when(jdbcA.queryForList(any(String.class), eq(String.class))).thenReturn(List.of());
        when(jdbcA.queryForObject(any(String.class), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        DefaultTenantBootstrapRunner runnerA =
                new DefaultTenantBootstrapRunner(
                        registryA, flywayA, resolverA, tempDir, jdbcA, txA);

        // Instance B
        TenantRegistryPort registryB = org.mockito.Mockito.mock(TenantRegistryPort.class);
        when(registryB.findAll()).thenReturn(List.of());
        PerTenantFlywayRunner flywayB = org.mockito.Mockito.mock(PerTenantFlywayRunner.class);
        TenantDataSourceResolver resolverB =
                org.mockito.Mockito.mock(TenantDataSourceResolver.class);
        org.mockito.Mockito.when(resolverB.resolve(any())).thenAnswer(inv -> newNoSchemaH2());
        JdbcTemplate jdbcB = org.mockito.Mockito.mock(JdbcTemplate.class);
        TransactionTemplate txB = org.mockito.Mockito.mock(TransactionTemplate.class);
        when(jdbcB.queryForList(any(String.class), eq(String.class))).thenReturn(List.of());
        when(jdbcB.queryForObject(any(String.class), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        DefaultTenantBootstrapRunner runnerB =
                new DefaultTenantBootstrapRunner(
                        registryB, flywayB, resolverB, tempDirB, jdbcB, txB);

        runnerA.run(null);
        runnerB.run(null);

        ArgumentCaptor<UUID> captorA = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> captorB = ArgumentCaptor.forClass(UUID.class);
        verify(registryA).register(captorA.capture(), any());
        verify(registryB).register(captorB.capture(), any());

        assertThat(captorA.getValue())
                .as(
                        "Two independently bootstrapped instances must generate different UUIDs"
                                + " (DEC-17 amendment)")
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
        when(sharedJdbcTemplate.queryForList(any(String.class), eq(String.class)))
                .thenReturn(List.of());
        doThrow(new FlywayException("simulated migration failure"))
                .when(flywayRunner)
                .runWithDataSource(any(), any());

        DefaultTenantBootstrapRunner runner =
                new DefaultTenantBootstrapRunner(
                        registry,
                        flywayRunner,
                        tenantDataSourceResolver,
                        tempDir,
                        sharedJdbcTemplate,
                        transactionTemplate);

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
     * AC-ORPHAN-RECOVERY: If a prior start created the tenant directory but the registry entry was
     * never persisted (crash between directory creation and registry write), the bootstrap on the
     * next start detects the orphan, cleans it, and retries the full flow.
     */
    @Test
    void orphanDirectory_isCleanedAndBootstrapRetries() throws Exception {
        // Arrange: create an orphan directory (simulates a crash after dir creation, before
        // registration)
        UUID orphanId = UUID.randomUUID();
        Path orphanDir = tempDir.resolve("tenants").resolve(orphanId.toString());
        Files.createDirectories(orphanDir);

        // Registry is empty (registration never completed)
        when(registry.findAll()).thenReturn(List.of());
        when(sharedJdbcTemplate.queryForList(any(String.class), eq(String.class)))
                .thenReturn(List.of());
        when(sharedJdbcTemplate.queryForObject(
                        any(String.class), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        DefaultTenantBootstrapRunner runner =
                new DefaultTenantBootstrapRunner(
                        registry,
                        flywayRunner,
                        tenantDataSourceResolver,
                        tempDir,
                        sharedJdbcTemplate,
                        transactionTemplate);

        // Act: should not throw
        runner.run(null);

        // Assert: orphan directory is gone
        assertThat(orphanDir)
                .as("Orphan directory must be cleaned up before fresh bootstrap")
                .doesNotExist();

        // Assert: registry was populated (bootstrap completed)
        verify(registry, times(1)).register(any(UUID.class), eq("Default (LAN)"));

        // Assert: Flyway was run for the NEW tenant (not the orphan): once pre-registration
        // (step 6) and once post-registration (step 7b) — total 2 invocations.
        verify(flywayRunner, times(2)).runWithDataSource(any(UUID.class), any());
    }

    /**
     * AC-ORPHAN-RECOVERY: Orphan directory is removed even when Flyway subsequently fails. The
     * orphan cleanup is unconditional; the subsequent failure is surfaced normally.
     */
    @Test
    void orphanDirectory_isCleanedEvenWhenFlywayFails() throws Exception {
        UUID orphanId = UUID.randomUUID();
        Path orphanDir = tempDir.resolve("tenants").resolve(orphanId.toString());
        Files.createDirectories(orphanDir);

        when(registry.findAll()).thenReturn(List.of());
        when(sharedJdbcTemplate.queryForList(any(String.class), eq(String.class)))
                .thenReturn(List.of());
        doThrow(new FlywayException("migration failure after orphan cleanup"))
                .when(flywayRunner)
                .runWithDataSource(any(), any());

        DefaultTenantBootstrapRunner runner =
                new DefaultTenantBootstrapRunner(
                        registry,
                        flywayRunner,
                        tenantDataSourceResolver,
                        tempDir,
                        sharedJdbcTemplate,
                        transactionTemplate);

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(FlywayException.class);

        // Orphan was still cleaned before the retry
        assertThat(orphanDir)
                .as("Orphan directory must be cleaned even when retry Flyway fails")
                .doesNotExist();
    }

    // -------------------------------------------------------------------------
    // AC8 — no auth coupling (structural)
    // -------------------------------------------------------------------------

    /**
     * AC8 (structural): The {@link DefaultTenantBootstrapRunner} class must not import any {@code
     * de.vvwt.tm.auth.*} type. This test uses reflection to verify the absence of any auth-package
     * dependency by checking the class's declared fields and method signatures.
     *
     * <p>The primary enforcement is structural (compilation + package isolation). This test serves
     * as an automated documentation check.
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
    // AC6 — concurrent first-start with REAL registry (integration-style unit test)
    // -------------------------------------------------------------------------

    /**
     * AC6: Two threads concurrently running the bootstrap against the same real {@link
     * TenantFileRegistry} must result in exactly one registered tenant.
     *
     * <p>Uses a real {@link TenantFileRegistry} (not a mock) to exercise the synchronized
     * register() + DuplicateTenantException handling in {@link DefaultTenantBootstrapRunner}. AC11:
     * both runners see an empty JPA table (null from SELECT) → each generates own UUID.
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

        // AC11: JPA empty for concurrent test (both see empty list → each picks own UUID; registry
        // race decides winner)
        JdbcTemplate jdbcConcA = org.mockito.Mockito.mock(JdbcTemplate.class);
        JdbcTemplate jdbcConcB = org.mockito.Mockito.mock(JdbcTemplate.class);
        TransactionTemplate txConcA = org.mockito.Mockito.mock(TransactionTemplate.class);
        TransactionTemplate txConcB = org.mockito.Mockito.mock(TransactionTemplate.class);
        TenantDataSourceResolver resolverConcA =
                org.mockito.Mockito.mock(TenantDataSourceResolver.class);
        TenantDataSourceResolver resolverConcB =
                org.mockito.Mockito.mock(TenantDataSourceResolver.class);
        org.mockito.Mockito.lenient()
                .when(resolverConcA.resolve(any()))
                .thenAnswer(inv -> newNoSchemaH2());
        org.mockito.Mockito.lenient()
                .when(resolverConcB.resolve(any()))
                .thenAnswer(inv -> newNoSchemaH2());
        org.mockito.Mockito.when(jdbcConcA.queryForList(any(String.class), eq(String.class)))
                .thenReturn(List.of());
        org.mockito.Mockito.when(jdbcConcB.queryForList(any(String.class), eq(String.class)))
                .thenReturn(List.of());
        org.mockito.Mockito.when(
                        jdbcConcA.queryForObject(
                                any(String.class), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        org.mockito.Mockito.when(
                        jdbcConcB.queryForObject(
                                any(String.class), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        DefaultTenantBootstrapRunner runnerA =
                new DefaultTenantBootstrapRunner(
                        realRegistry, flywayRunner, resolverConcA, dataDirA, jdbcConcA, txConcA);
        DefaultTenantBootstrapRunner runnerB =
                new DefaultTenantBootstrapRunner(
                        realRegistry, flywayRunner, resolverConcB, dataDirB, jdbcConcB, txConcB);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> futureA =
                executor.submit(
                        () -> {
                            try {
                                startGate.await();
                                runnerA.run(null);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (Exception e) {
                                exceptionCount.incrementAndGet();
                            }
                        });

        Future<?> futureB =
                executor.submit(
                        () -> {
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
     * AC11 path (a): JPA TENANTS table already has a row → runner uses THAT UUID (UUID
     * reconciliation: legacy DefaultTenantBootstrap wrote UUID-A to JPA first; this runner picks it
     * up instead of generating UUID-B).
     *
     * <p>Story: E14S12 AC11.
     */
    @Test
    void ac11_jpaTenantExists_runnerUsesExistingUuid() throws Exception {
        UUID existingJpaUuid = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        when(registry.findAll()).thenReturn(List.of());
        // AC11: JPA has an existing tenant row → return its UUID as String
        when(sharedJdbcTemplate.queryForList(any(String.class), eq(String.class)))
                .thenReturn(List.of(existingJpaUuid.toString()));
        // upsertMainDbTenantRow: row already present (consistent with AC11 read)
        when(sharedJdbcTemplate.queryForObject(
                        any(String.class), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        DefaultTenantBootstrapRunner runner =
                new DefaultTenantBootstrapRunner(
                        registry,
                        flywayRunner,
                        tenantDataSourceResolver,
                        tempDir,
                        sharedJdbcTemplate,
                        transactionTemplate);

        runner.run(null);

        // The runner must register the UUID-from-JPA, not a randomly generated one
        ArgumentCaptor<UUID> uuidCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(registry).register(uuidCaptor.capture(), eq("Default (LAN)"));
        assertThat(uuidCaptor.getValue())
                .as(
                        "AC11: runner must use the UUID already in JPA TENANTS table (not generate"
                                + " a new one)")
                .isEqualTo(existingJpaUuid);
    }

    /**
     * AC11 path (b): JPA TENANTS table is empty (true fresh-start) → runner generates a new UUID
     * via UUID.randomUUID().
     *
     * <p>Story: E14S12 AC11.
     */
    @Test
    void ac11_jpaEmpty_runnerGeneratesNewUuid() throws Exception {
        when(registry.findAll()).thenReturn(List.of());
        // AC11: JPA empty → queryForList returns empty list (no row)
        when(sharedJdbcTemplate.queryForList(any(String.class), eq(String.class)))
                .thenReturn(List.of());
        // upsertMainDbTenantRow: row not yet present
        when(sharedJdbcTemplate.queryForObject(
                        any(String.class), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        DefaultTenantBootstrapRunner runner =
                new DefaultTenantBootstrapRunner(
                        registry,
                        flywayRunner,
                        tenantDataSourceResolver,
                        tempDir,
                        sharedJdbcTemplate,
                        transactionTemplate);

        runner.run(null);

        // Runner must have registered some non-null UUID (generated by UUID.randomUUID())
        ArgumentCaptor<UUID> uuidCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(registry).register(uuidCaptor.capture(), eq("Default (LAN)"));
        assertThat(uuidCaptor.getValue())
                .as("AC11: runner must generate a non-null UUID when JPA table is empty")
                .isNotNull();
    }

    /**
     * AC11 path (c): JPA SELECT throws DataAccessException (connectivity issue) → runner must
     * surface a typed error and must NOT write the file registry with a guessed UUID.
     *
     * <p>Story: E14S12 AC11.
     */
    @Test
    void ac11_jpaQueryThrows_runnerSurfacesErrorAndDoesNotRegister() throws Exception {
        when(registry.findAll()).thenReturn(List.of());
        // AC11: JPA unavailable → DataAccessException
        when(sharedJdbcTemplate.queryForList(any(String.class), eq(String.class)))
                .thenThrow(
                        new org.springframework.dao.TransientDataAccessException(
                                "connection failed") {});

        DefaultTenantBootstrapRunner runner =
                new DefaultTenantBootstrapRunner(
                        registry,
                        flywayRunner,
                        tenantDataSourceResolver,
                        tempDir,
                        sharedJdbcTemplate,
                        transactionTemplate);

        assertThatThrownBy(() -> runner.run(null))
                .as(
                        "AC11: JPA query failure must propagate as a typed error — no registry"
                                + " write with guessed UUID")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency guard");

        // Registry must NOT have been updated
        verify(registry, never()).register(any(), anyString());
    }

    /** AC12: null JdbcTemplate constructor guard — must throw IllegalArgumentException. */
    @Test
    void ac12_nullJdbcTemplate_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new DefaultTenantBootstrapRunner(
                                        registry,
                                        flywayRunner,
                                        tenantDataSourceResolver,
                                        tempDir,
                                        null,
                                        transactionTemplate))
                .as("AC12: null JdbcTemplate must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sharedJdbcTemplate must not be null");
    }

    /** AC12: null TransactionTemplate constructor guard — must throw IllegalArgumentException. */
    @Test
    void ac12_nullTransactionTemplate_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new DefaultTenantBootstrapRunner(
                                        registry,
                                        flywayRunner,
                                        tenantDataSourceResolver,
                                        tempDir,
                                        sharedJdbcTemplate,
                                        null))
                .as("AC12: null TransactionTemplate must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionTemplate must not be null");
    }
}
