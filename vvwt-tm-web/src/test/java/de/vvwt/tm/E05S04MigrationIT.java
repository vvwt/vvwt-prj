package de.vvwt.tm;

import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.domain.repo.TenantContextTestHelper;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.tenant.TenantRegistryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that V7__e05s04_tournament_fields.sql is applied correctly
 * and that the new {@code appointment}, {@code field_count}, and {@code team_count} columns
 * are readable and writable through the {@link Tournament} entity.
 *
 * @see <a href="../../../.gaai/project/contexts/artefacts/stories/E05S04.story.md">Story E05S04</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e05s04migdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class E05S04MigrationIT {

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private TenantRegistryPort tenantRegistryPort;

    @Autowired
    private TournamentRepository tournamentRepository;

    private UUID defaultTenantId;

    @BeforeEach
    void setUpTenantContext() {
        defaultTenantId = tenantRegistryPort.findAll().get(0).tenantId();
        TenantContextTestHelper.set(tenantContext, defaultTenantId);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextTestHelper.clear(tenantContext);
    }

    @Test
    @Transactional
    void appointmentFieldCountTeamCountColumnsExistAndAreReadable() {
        // Given: a tournament with the new E05S04 fields
        LocalDateTime appointment = LocalDateTime.of(2026, 6, 15, 10, 0, 0);
        Tournament t = new Tournament(
                UUID.randomUUID(),
                defaultTenantId,
                "Hallenturnier 2026",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                appointment,
                4,
                8);

        // When: saved and retrieved
        tournamentRepository.save(t);
        Tournament loaded = tournamentRepository.findById(t.getId()).orElseThrow();

        // Then: all new fields round-trip correctly
        assertThat(loaded.getAppointment())
                .as("appointment should persist and load correctly")
                .isEqualTo(appointment);
        assertThat(loaded.getFieldCount())
                .as("fieldCount should persist and load correctly")
                .isEqualTo(4);
        assertThat(loaded.getTeamCount())
                .as("teamCount should persist and load correctly")
                .isEqualTo(8);
    }

    @Test
    @Transactional
    void appointmentIsNullableAndDefaultsToNull() {
        // Given: a tournament without appointment (legacy constructor)
        Tournament t = new Tournament(
                UUID.randomUUID(),
                defaultTenantId,
                "No Date Tournament",
                "BEST_OF_1",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now());

        // When: saved and retrieved
        tournamentRepository.save(t);
        Tournament loaded = tournamentRepository.findById(t.getId()).orElseThrow();

        // Then: appointment is null, fieldCount/teamCount default to 1/2
        assertThat(loaded.getAppointment())
                .as("appointment should be null when not set")
                .isNull();
        assertThat(loaded.getFieldCount())
                .as("fieldCount should default to 1")
                .isEqualTo(1);
        assertThat(loaded.getTeamCount())
                .as("teamCount should default to 2")
                .isEqualTo(2);
    }
}
