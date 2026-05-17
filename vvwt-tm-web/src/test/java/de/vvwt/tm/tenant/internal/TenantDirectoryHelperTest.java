// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link TenantDirectoryHelper}.
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC2 — file layout: {@code ${tm.data.dir}/tenants/{uuid}/db.mv.db}
 *   <li>AC4 — missing data dir created; non-writable dir fails fast
 *   <li>AC-PATH-LENGTH — path length exceeding 250 chars fails fast with actionable message
 * </ul>
 *
 * <p>Story: E14S02 — DEC-10/DEC-20/DEC-21/DEC-22.
 */
class TenantDirectoryHelperTest {

    // -------------------------------------------------------------------------
    // AC2 — computed path follows layout ${tm.data.dir}/tenants/{uuid}/db.mv.db
    // -------------------------------------------------------------------------

    @Test
    void tenantDbPathFollowsExpectedLayout(@TempDir Path dataDir) {
        UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        Path dbPath = TenantDirectoryHelper.tenantDbPath(dataDir, tenantId);

        assertThat(dbPath)
                .as(
                        "Tenant DB path must follow layout ${tm.data.dir}/tenants/{uuid}/db.mv.db"
                                + " (AC2)")
                .isEqualTo(
                        dataDir.resolve("tenants")
                                .resolve(tenantId.toString())
                                .resolve("db.mv.db"));
    }

    // -------------------------------------------------------------------------
    // AC2 — writing to tenant A's path does not touch tenant B's path
    // -------------------------------------------------------------------------

    @Test
    void differentTenantsHaveDistinctPaths(@TempDir Path dataDir) {
        UUID tenantA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID tenantB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        Path pathA = TenantDirectoryHelper.tenantDbPath(dataDir, tenantA);
        Path pathB = TenantDirectoryHelper.tenantDbPath(dataDir, tenantB);

        assertThat(pathA)
                .as("Tenant A and Tenant B must have distinct DB paths (AC2 isolation)")
                .isNotEqualTo(pathB);
    }

    // -------------------------------------------------------------------------
    // AC4 — createTenantDirectory creates the directory if missing
    // -------------------------------------------------------------------------

    @Test
    void createTenantDirectoryCreatesDirectoryIfMissing(@TempDir Path dataDir) throws IOException {
        UUID tenantId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Path tenantDir = dataDir.resolve("tenants").resolve(tenantId.toString());
        assertThat(tenantDir).doesNotExist();

        TenantDirectoryHelper.createTenantDirectory(dataDir, tenantId);

        assertThat(tenantDir)
                .as(
                        "createTenantDirectory must create the tenant directory if it does not"
                                + " exist (AC4)")
                .isDirectory();
    }

    @Test
    void createTenantDirectoryIsIdempotentIfDirectoryAlreadyExists(@TempDir Path dataDir)
            throws IOException {
        UUID tenantId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Path tenantDir = dataDir.resolve("tenants").resolve(tenantId.toString());
        Files.createDirectories(tenantDir);

        // Should not throw if directory already exists
        TenantDirectoryHelper.createTenantDirectory(dataDir, tenantId);

        assertThat(tenantDir).as("createTenantDirectory must be idempotent (AC4)").isDirectory();
    }

    @Test
    void createTenantDirectoryWithNonWritableParentFailsFast(@TempDir Path dataDir)
            throws IOException {
        // Make the data dir non-writable if supported by this OS
        org.junit.jupiter.api.Assumptions.assumeTrue(
                dataDir.toFile().setWritable(false),
                "OS does not support restricting write permissions — skipping AC4 non-writable"
                        + " test");

        UUID tenantId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        assertThatThrownBy(() -> TenantDirectoryHelper.createTenantDirectory(dataDir, tenantId))
                .as("Non-writable data dir must fail fast with actionable message (AC4)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(dataDir.toString());

        // Restore write permission so @TempDir cleanup works
        dataDir.toFile().setWritable(true);
    }

    // -------------------------------------------------------------------------
    // AC-PATH-LENGTH — path > 250 chars fails fast with actionable message
    // -------------------------------------------------------------------------

    @Test
    void pathLengthExceedingLimitFailsFastWithActionableMessage() {
        // Construct a tm.data.dir that when combined with /tenants/{uuid}/db.mv.db
        // produces a path > 250 characters
        // UUID is 36 chars; "/tenants/" is 9; "/db.mv.db" is 9 → 54 chars suffix
        // So dataDir string length > 250 - 54 = 196 chars triggers the limit
        String longBase = "a".repeat(200);
        Path longDataDir = Path.of("/" + longBase);
        UUID tenantId = UUID.randomUUID();

        assertThatThrownBy(() -> TenantDirectoryHelper.tenantDbPath(longDataDir, tenantId))
                .as(
                        "Path length exceeding 250 chars must throw with actionable message"
                                + " (AC-PATH-LENGTH)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("250")
                .hasMessageContaining(longDataDir.toString());
    }

    @Test
    void tenantDbPathRejectsNullTenantId(@TempDir Path dataDir) {
        assertThatThrownBy(() -> TenantDirectoryHelper.tenantDbPath(dataDir, null))
                .as("tenantDbPath must reject null tenantId")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenantDbPathRejectsNullDataDir() {
        UUID tenantId = UUID.randomUUID();
        assertThatThrownBy(() -> TenantDirectoryHelper.tenantDbPath(null, tenantId))
                .as("tenantDbPath must reject null dataDir")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
