package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.TenantRegistryPort.TenantRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit and integration tests for {@link TenantFileRegistry}.
 *
 * <p>All tests use JUnit 5 {@code @TempDir} for real filesystem I/O. Tests MUST NOT write into the
 * real {@code ${tm.data.dir}} during testing.
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC1 — test-first: this file was committed before the implementation
 *   <li>AC2 — tenant directories are distinct per tenant
 *   <li>AC3 — registry persists across instantiations (survives restart)
 *   <li>AC4 — missing data dir created; non-writable fails fast
 *   <li>AC5 — corrupt registry fails fast without silent recreation
 *   <li>AC6 — duplicate registration throws {@link TenantFileRegistry.DuplicateTenantException}
 *   <li>AC-CONCURRENT-REGISTER — concurrent different IDs: both succeed; concurrent same ID:
 *       exactly one succeeds
 * </ul>
 *
 * <p>Story: E14S02 — DEC-10/DEC-17/DEC-20/DEC-21/DEC-22.
 */
class TenantFileRegistryTest {

    // -------------------------------------------------------------------------
    // AC1 / happy path — register and lookup a known tenant
    // -------------------------------------------------------------------------

    @Test
    void registerAndLookupKnownTenant(@TempDir Path dataDir) {
        TenantFileRegistry registry = new TenantFileRegistry(dataDir);
        UUID tenantId = UUID.fromString("aaaaaaaa-0001-0001-0001-000000000001");

        registry.register(tenantId, "Test Tenant");
        Optional<TenantRecord> result = registry.lookup(tenantId);

        assertThat(result)
                .as("lookup() after register() must return a non-empty Optional (AC1/AC3)")
                .isPresent();
        assertThat(result.get().tenantId())
                .as("TenantRecord must contain the registered UUID (DEC-17)")
                .isEqualTo(tenantId);
        assertThat(result.get().displayName())
                .as("TenantRecord must contain the registered display name")
                .isEqualTo("Test Tenant");
    }

    // -------------------------------------------------------------------------
    // AC3 — lookup returns empty for unknown tenant
    // -------------------------------------------------------------------------

    @Test
    void lookupUnknownTenantReturnsEmpty(@TempDir Path dataDir) {
        TenantFileRegistry registry = new TenantFileRegistry(dataDir);
        UUID unknown = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000000");

        Optional<TenantRecord> result = registry.lookup(unknown);

        assertThat(result)
                .as("lookup() for unknown tenant must return Optional.empty() (AC3)")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC3 — registry survives restart (data persisted to file)
    // -------------------------------------------------------------------------

    @Test
    void registryPersistedAcrossInstances(@TempDir Path dataDir) {
        UUID tenantId = UUID.fromString("cccccccc-0001-0001-0001-000000000001");

        // First instance registers a tenant
        TenantFileRegistry first = new TenantFileRegistry(dataDir);
        first.register(tenantId, "Persistent Tenant");

        // Second instance (simulating restart) reads the same data dir
        TenantFileRegistry second = new TenantFileRegistry(dataDir);
        Optional<TenantRecord> result = second.lookup(tenantId);

        assertThat(result)
                .as(
                        "Registry must persist across instantiations — simulating application"
                                + " restart (AC3)")
                .isPresent();
        assertThat(result.get().displayName())
                .as("Display name must survive persistence round-trip")
                .isEqualTo("Persistent Tenant");
    }

    // -------------------------------------------------------------------------
    // AC5 — corrupt registry fails fast (no silent recreation)
    // -------------------------------------------------------------------------

    @Test
    void corruptRegistryFailsFastWithActionableMessage(@TempDir Path dataDir) throws IOException {
        // Pre-create the registry file with invalid content (truncated/corrupt JSON)
        Path registryFile = dataDir.resolve("tenant-registry.json");
        Files.writeString(registryFile, "NOT_VALID_JSON{{{", StandardCharsets.UTF_8);

        // Constructing the registry should fail fast (or fail on first access)
        // The important thing: it must NOT silently recreate the file (data loss risk)
        assertThatThrownBy(() -> new TenantFileRegistry(dataDir))
                .as("Corrupt registry must fail fast with actionable message naming the file (AC5)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(registryFile.toString());
    }

    // -------------------------------------------------------------------------
    // AC4 — missing data dir is created on init
    // -------------------------------------------------------------------------

    @Test
    void missingDataDirIsCreatedOnInit(@TempDir Path parentDir) {
        Path dataDir = parentDir.resolve("new-data-dir");
        assertThat(dataDir).doesNotExist();

        // Registry creation must create the directory
        new TenantFileRegistry(dataDir);

        assertThat(dataDir)
                .as("Missing data dir must be created on TenantFileRegistry init (AC4)")
                .isDirectory();
    }

    // -------------------------------------------------------------------------
    // AC4 — non-writable data dir fails fast
    // -------------------------------------------------------------------------

    @Test
    void nonWritableDataDirFailsFast(@TempDir Path dataDir) throws IOException {
        Path nonWritable = dataDir.resolve("protected");
        Files.createDirectories(nonWritable);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                nonWritable.toFile().setWritable(false),
                "OS does not support restricting write permissions — skipping AC4 non-writable"
                        + " test");

        assertThatThrownBy(
                        () -> {
                            TenantFileRegistry registry = new TenantFileRegistry(nonWritable);
                            // Force a write by registering a tenant
                            registry.register(UUID.randomUUID(), "Tenant");
                        })
                .as("Non-writable data dir must fail fast with actionable message (AC4)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(nonWritable.toString());

        nonWritable.toFile().setWritable(true);
    }

    // -------------------------------------------------------------------------
    // AC6 — duplicate registration throws typed exception
    // -------------------------------------------------------------------------

    @Test
    void duplicateRegistrationThrowsDuplicateTenantException(@TempDir Path dataDir) {
        TenantFileRegistry registry = new TenantFileRegistry(dataDir);
        UUID tenantId = UUID.fromString("dddddddd-0001-0001-0001-000000000001");
        registry.register(tenantId, "First");

        assertThatThrownBy(() -> registry.register(tenantId, "Second"))
                .as("Duplicate registration must throw DuplicateTenantException (AC6)")
                .isInstanceOf(TenantFileRegistry.DuplicateTenantException.class)
                .hasMessageContaining(tenantId.toString());
    }

    @Test
    void duplicateRegistrationAcrossInstancesThrows(@TempDir Path dataDir) {
        UUID tenantId = UUID.fromString("eeeeeeee-0001-0001-0001-000000000001");

        // Register via first instance
        TenantFileRegistry first = new TenantFileRegistry(dataDir);
        first.register(tenantId, "Original");

        // Second instance (simulating restart) must also reject duplicate
        TenantFileRegistry second = new TenantFileRegistry(dataDir);
        assertThatThrownBy(() -> second.register(tenantId, "Duplicate"))
                .as(
                        "Duplicate registration across restarts must throw DuplicateTenantException"
                                + " (AC6)")
                .isInstanceOf(TenantFileRegistry.DuplicateTenantException.class);
    }

    // -------------------------------------------------------------------------
    // AC-CONCURRENT-REGISTER — two threads, different IDs → both succeed
    // -------------------------------------------------------------------------

    @Test
    void concurrentDifferentTenantsAllSucceed(@TempDir Path dataDir) throws Exception {
        TenantFileRegistry registry = new TenantFileRegistry(dataDir);
        UUID tenantA = UUID.fromString("ffffffff-0001-0001-0001-000000000001");
        UUID tenantB = UUID.fromString("ffffffff-0001-0001-0001-000000000002");
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Exception> errors = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> futureA =
                executor.submit(
                        () -> {
                            try {
                                startLatch.await();
                                registry.register(tenantA, "Tenant A");
                            } catch (Exception e) {
                                synchronized (errors) {
                                    errors.add(e);
                                }
                            }
                        });
        Future<?> futureB =
                executor.submit(
                        () -> {
                            try {
                                startLatch.await();
                                registry.register(tenantB, "Tenant B");
                            } catch (Exception e) {
                                synchronized (errors) {
                                    errors.add(e);
                                }
                            }
                        });

        startLatch.countDown();
        futureA.get();
        futureB.get();
        executor.shutdown();

        assertThat(errors)
                .as(
                        "Concurrent registration of DIFFERENT tenants must both succeed"
                                + " (AC-CONCURRENT-REGISTER)")
                .isEmpty();
        assertThat(registry.lookup(tenantA)).isPresent();
        assertThat(registry.lookup(tenantB)).isPresent();
    }

    // -------------------------------------------------------------------------
    // AC-CONCURRENT-REGISTER — two threads, same ID → exactly one success
    // -------------------------------------------------------------------------

    @Test
    void concurrentSameTenantIdExactlyOneSucceeds(@TempDir Path dataDir) throws Exception {
        TenantFileRegistry registry = new TenantFileRegistry(dataDir);
        UUID tenantId = UUID.fromString("11111111-0001-0001-0001-000000000001");
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Runnable task =
                () -> {
                    try {
                        startLatch.await();
                        registry.register(tenantId, "Shared Tenant");
                        successCount.incrementAndGet();
                    } catch (TenantFileRegistry.DuplicateTenantException e) {
                        duplicateCount.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };

        Future<?> f1 = executor.submit(task);
        Future<?> f2 = executor.submit(task);
        startLatch.countDown();
        f1.get();
        f2.get();
        executor.shutdown();

        assertThat(successCount.get())
                .as(
                        "Exactly one thread must succeed for concurrent same-ID registration"
                                + " (AC-CONCURRENT-REGISTER)")
                .isEqualTo(1);
        assertThat(duplicateCount.get())
                .as(
                        "The other thread must receive DuplicateTenantException"
                                + " (AC-CONCURRENT-REGISTER)")
                .isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // findAll — enumerates all registered tenants (AC1 enumeration contract)
    // -------------------------------------------------------------------------

    @Test
    void findAllReturnsAllRegisteredTenants(@TempDir Path dataDir) {
        TenantFileRegistry registry = new TenantFileRegistry(dataDir);
        UUID id1 = UUID.fromString("11111111-1111-1111-1111-000000000001");
        UUID id2 = UUID.fromString("22222222-2222-2222-2222-000000000002");
        registry.register(id1, "Tenant One");
        registry.register(id2, "Tenant Two");

        List<TenantRecord> all = registry.findAll();

        assertThat(all)
                .as("findAll() must return all registered tenants (AC1 enumeration)")
                .hasSize(2)
                .extracting(TenantRecord::tenantId)
                .containsExactlyInAnyOrder(id1, id2);
    }

    @Test
    void findAllReturnsEmptyListWhenNoTenantsRegistered(@TempDir Path dataDir) {
        TenantFileRegistry registry = new TenantFileRegistry(dataDir);

        List<TenantRecord> all = registry.findAll();

        assertThat(all).as("findAll() must return empty list when no tenants registered").isEmpty();
    }
}
