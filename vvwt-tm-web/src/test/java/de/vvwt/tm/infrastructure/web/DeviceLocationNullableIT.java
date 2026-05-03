package de.vvwt.tm.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC1 (E14S08) — Integration test proving that {@code devices.location_id} is nullable.
 *
 * <p>This test is written BEFORE the V16 migration file exists. It must fail with a NOT NULL
 * constraint violation on the current schema, then pass after V16 is applied.
 *
 * <p>TDD RED phase: asserts that a device row with {@code location_id = NULL} can be inserted and
 * read back. The test fails (NOT NULL constraint) until V16 migration lands.
 *
 * @see <a href=".gaai/project/contexts/artefacts/stories/E14S08.story.md">Story E14S08</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            DeviceLocationNullableIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e14s08nullabledb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class DeviceLocationNullableIT {

    static final String TEST_PASSWORD = "E14S08NullableTest01";

    // Primary routing DataSource — routes to the per-tenant DB when tenant is bound.
    @Autowired private DataSource dataSource;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @BeforeEach
    void bindTenant() {
        tenantBinder.bindDefaultTenant();
    }

    @AfterEach
    void unbindTenant() {
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC1 — devices.location_id is nullable after V16 migration
    // =========================================================================

    @Test
    void insertDeviceWithNullLocationIdSucceeds() throws SQLException {
        // AC1: insert a device row with location_id = NULL and verify it round-trips.
        // RED: fails with NOT NULL constraint until V16 migration drops the constraint.
        // GREEN: succeeds after V16 ALTER TABLE devices ALTER COLUMN location_id SET NULL.
        UUID tenantId = resolveDefaultTenantId();
        UUID deviceId = UUID.randomUUID();
        String token = UUID.randomUUID().toString();

        // E45S06: tenant_id removed from devices (DEC-39 D1)
        assertThatCode(
                        () -> {
                            try (Connection conn = dataSource.getConnection();
                                    PreparedStatement ps =
                                            conn.prepareStatement(
                                                    "INSERT INTO devices (id, location_id,"
                                                            + " device_token, pin, device_type,"
                                                            + " status, registered_at)"
                                                            + " VALUES (?, NULL, ?, '9991',"
                                                            + " 'SCORING_TABLET', 'REGISTERED',"
                                                            + " CURRENT_TIMESTAMP)")) {
                                ps.setObject(1, deviceId);
                                ps.setString(2, token);
                                ps.executeUpdate();
                            }
                        })
                .as("AC1 — INSERT device with location_id=NULL must succeed after V16 migration")
                .doesNotThrowAnyException();

        // Verify the row exists with location_id = NULL
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT location_id FROM devices WHERE id = ?")) {
            ps.setObject(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("AC1 — device row must exist after insert").isTrue();
                assertThat(rs.getObject("location_id"))
                        .as(
                                "AC1 — location_id must be NULL in DB after registration without"
                                        + " location")
                        .isNull();
            }
        } finally {
            // Cleanup: remove inserted test row
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement ps =
                            conn.prepareStatement("DELETE FROM devices WHERE id = ?")) {
                ps.setObject(1, deviceId);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void insertDeviceWithLocationIdStillWorks() throws SQLException {
        // AC2 baseline: devices with a location_id still round-trip correctly.
        UUID tenantId = resolveDefaultTenantId();
        UUID locationId = resolveDefaultLocationId(tenantId);
        UUID deviceId = UUID.randomUUID();
        String token = UUID.randomUUID().toString();

        // E45S06: tenant_id removed from devices (DEC-39 D1)
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "INSERT INTO devices (id, location_id, device_token,"
                                        + " pin, device_type, status, registered_at) VALUES (?, ?,"
                                        + " ?, '9992', 'SCORING_TABLET', 'REGISTERED',"
                                        + " CURRENT_TIMESTAMP)")) {
            ps.setObject(1, deviceId);
            ps.setObject(2, locationId);
            ps.setString(3, token);
            ps.executeUpdate();
        }

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT location_id FROM devices WHERE id = ?")) {
            ps.setObject(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("location_id"))
                        .as("AC2 baseline — location_id must be preserved when set")
                        .isNotNull();
            }
        } finally {
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement ps =
                            conn.prepareStatement("DELETE FROM devices WHERE id = ?")) {
                ps.setObject(1, deviceId);
                ps.executeUpdate();
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID resolveDefaultTenantId() throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT id FROM tenants WHERE is_default = TRUE")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("Default tenant must exist after bootstrap").isTrue();
                return UUID.fromString(rs.getString("id"));
            }
        }
    }

    private UUID resolveDefaultLocationId(UUID tenantId) throws SQLException {
        // E45S06: tenant_id removed from locations (DEC-50); select first location row.
        // In Wave-1 single-location model each per-tenant DB has exactly one location row.
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT id FROM locations LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("Default location must exist after bootstrap").isTrue();
                return UUID.fromString(rs.getString("id"));
            }
        }
    }

    // =========================================================================
    // Test configuration — fixed admin credentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hashed = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hashed;
        }
    }
}
