package de.vvwt.slotopt.dispatcher.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.vvwt.slotopt.dispatcher.audit.AuditEntry;
import de.vvwt.slotopt.dispatcher.audit.AuditRepository;
import de.vvwt.slotopt.dispatcher.audit.AuditService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;

/**
 * Unit tests for {@link DefaultAuditService}.
 *
 * <p>This test is in the SAME package as its subject ({@code audit.internal}) — white-box access is
 * permitted per DEC-36: same-package tests MAY reference the implementation class directly.
 *
 * <p>RED-first per DEC-22 / AC-AUDIT-FAILURE-MODE.
 *
 * <p>Story: E37S06
 */
@ExtendWith(MockitoExtension.class)
class DefaultAuditServiceTest {

    @Mock private AuditRepository repository;

    // DEC-36: test is in audit.internal (same package as subject) — white-box allowed.
    // The service field is typed as the public interface (AuditService) per DEC-35 / DEC-36
    // cross-package-consumers pattern — but here we also need to verify internal behaviour,
    // so we accept both interface and implementation references within this package.
    private AuditService service;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new DefaultAuditService(repository);

        // Capture log output for WARN-log assertion
        serviceLogger = (Logger) LoggerFactory.getLogger(DefaultAuditService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    // -------------------------------------------------------------------------
    // AC-AUDIT-FAILURE-MODE (1): null/empty eventType → IllegalArgumentException
    // -------------------------------------------------------------------------

    @Test
    void nullEventTypeThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> service.recordEvent(null, UUID.randomUUID(), "127.0.0.1", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    void emptyEventTypeThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> service.recordEvent("", UUID.randomUUID(), "127.0.0.1", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    void blankEventTypeThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> service.recordEvent("   ", UUID.randomUUID(), "127.0.0.1", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    // -------------------------------------------------------------------------
    // AC-AUDIT-FAILURE-MODE (2): DB persist failure → WARN log + does NOT rethrow
    // -------------------------------------------------------------------------

    @Test
    void dbPersistFailureLogsWarnAndDoesNotRethrow() {
        doThrow(new TransientDataAccessException("simulated DB failure") {})
                .when(repository)
                .save(any(AuditEntry.class));

        // Must NOT throw — audit failures must never block originating operation
        service.recordEvent("KEY_REGISTERED", UUID.randomUUID(), "10.0.0.1", "{}");

        // Assert WARN log was captured
        List<ILoggingEvent> warnLogs =
                logAppender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warnLogs).as("Expected at least one WARN log when persist fails").isNotEmpty();
        // The WARN log must contain meaningful context
        String warnMessage = warnLogs.get(0).getFormattedMessage();
        assertThat(warnMessage)
                .as("WARN log should contain event type for forensic diagnosis")
                .containsIgnoringCase("KEY_REGISTERED");
    }

    // -------------------------------------------------------------------------
    // AC-AUDIT-FAILURE-MODE (3): oversize detailJson → truncate-with-marker
    // -------------------------------------------------------------------------

    @Test
    void oversizeDetailJsonIsTruncatedWithMarker() {
        // Build a detailJson that exceeds the 64KB (65536 char) bound
        String oversizeJson = "x".repeat(70_000);

        // Must not throw — truncation is applied instead
        service.recordEvent("KEY_REGISTERED", UUID.randomUUID(), "127.0.0.1", oversizeJson);

        // Capture the AuditEntry that was persisted
        var captor = org.mockito.ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).save(captor.capture());

        String persistedDetail = captor.getValue().getDetailJson();
        assertThat(persistedDetail)
                .as("truncated detailJson must not exceed configured bound")
                .hasSizeLessThanOrEqualTo(65536 + "[TRUNCATED]".length());
        assertThat(persistedDetail)
                .as("truncated detailJson must end with truncation marker")
                .endsWith("[TRUNCATED]");
    }

    // -------------------------------------------------------------------------
    // Happy path: valid event persisted successfully
    // -------------------------------------------------------------------------

    @Test
    void validEventIsPersisted() {
        UUID workerId = UUID.randomUUID();
        service.recordEvent("KEY_REGISTERED", workerId, "192.168.1.1", "{\"test\":true}");

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).save(captor.capture());

        AuditEntry saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("KEY_REGISTERED");
        assertThat(saved.getWorkerId()).isEqualTo(workerId);
        assertThat(saved.getSourceIp()).isEqualTo("192.168.1.1");
        assertThat(saved.getDetailJson()).isEqualTo("{\"test\":true}");
        assertThat(saved.getOccurredAt()).isNotNull();
    }

    @Test
    void nullWorkerIdIsAllowed() {
        // workerId is nullable per AC-AUDIT-ENTRY-ENTITY
        service.recordEvent("KEY_REGISTERED", null, "10.0.0.1", "{}");

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getWorkerId()).isNull();
    }
}
