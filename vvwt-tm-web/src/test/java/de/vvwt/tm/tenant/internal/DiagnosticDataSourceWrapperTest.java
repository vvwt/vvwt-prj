package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link DiagnosticDataSourceWrapper} (E55S10
 * AC-DIAG-INSTRUMENT-HIKARICP-LIFECYCLE).
 *
 * <p>Verifies that the wrapper emits structured INFO-level {@code [diag-conn] ACQUIRE} and {@code
 * [diag-conn] RELEASE} log lines at connection acquire and close time, as required by
 * AC-DIAG-VERIFY-INSTRUMENTATION-VIA-STRUCTURAL-IT (connection-lifecycle part, tested here as a
 * unit test because {@code TenantContextTestSupport} bypasses the production {@code
 * TenantFileRegistryDataSourceResolver} wrapping in {@code @SpringBootTest} contexts).
 *
 * @since E55S10
 */
class DiagnosticDataSourceWrapperTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(DiagnosticDataSourceWrapper.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("getConnection() emits [diag-conn] ACQUIRE at INFO level")
    void getConnectionEmitsAcquireLog() throws SQLException {
        Connection mockConn = mock(Connection.class);
        when(mockConn.getAutoCommit()).thenReturn(true);

        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenReturn(mockConn);

        DiagnosticDataSourceWrapper wrapper = new DiagnosticDataSourceWrapper(mockDs, TENANT_ID);
        wrapper.getConnection();

        List<String> messages =
                appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages)
                .as("[diag-conn] ACQUIRE must be logged on getConnection()")
                .anyMatch(msg -> msg.contains("[diag-conn] ACQUIRE"));

        assertThat(appender.list)
                .as("[diag-conn] ACQUIRE must be at INFO level")
                .allMatch(e -> e.getLevel() == Level.INFO);
    }

    @Test
    @DisplayName("Connection.close() emits [diag-conn] RELEASE at INFO level")
    void connectionCloseEmitsReleaseLog() throws SQLException {
        Connection mockConn = mock(Connection.class);
        when(mockConn.getAutoCommit()).thenReturn(true);

        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenReturn(mockConn);

        DiagnosticDataSourceWrapper wrapper = new DiagnosticDataSourceWrapper(mockDs, TENANT_ID);
        Connection conn = wrapper.getConnection();

        // Clear the ACQUIRE log so we can isolate the RELEASE log
        appender.list.clear();

        conn.close();

        List<String> messages =
                appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages)
                .as("[diag-conn] RELEASE must be logged on Connection.close()")
                .anyMatch(msg -> msg.contains("[diag-conn] RELEASE"));

        assertThat(appender.list)
                .as("[diag-conn] RELEASE must be at INFO level")
                .allMatch(e -> e.getLevel() == Level.INFO);

        // Verify the underlying connection is actually closed
        verify(mockConn).close();
    }

    @Test
    @DisplayName("ACQUIRE log includes tenant UUID, thread name, autoCommit state")
    void acquireLogContainsTenantAndThreadInfo() throws SQLException {
        Connection mockConn = mock(Connection.class);
        when(mockConn.getAutoCommit()).thenReturn(true);

        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenReturn(mockConn);

        DiagnosticDataSourceWrapper wrapper = new DiagnosticDataSourceWrapper(mockDs, TENANT_ID);
        wrapper.getConnection();

        String acquireMsg =
                appender.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(msg -> msg.contains("[diag-conn] ACQUIRE"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("[diag-conn] ACQUIRE not found"));

        assertThat(acquireMsg)
                .as("ACQUIRE log must include tenant UUID")
                .contains(TENANT_ID.toString());
        assertThat(acquireMsg)
                .as("ACQUIRE log must include current thread name")
                .contains(Thread.currentThread().getName());
        assertThat(acquireMsg)
                .as("ACQUIRE log must include autoCommit state")
                .contains("autoCommit=true");
    }

    @Test
    @DisplayName("RELEASE log includes tenant UUID and autoCommit state from close time")
    void releaseLogContainsTenantInfo() throws SQLException {
        Connection mockConn = mock(Connection.class);
        when(mockConn.getAutoCommit()).thenReturn(true);

        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenReturn(mockConn);

        DiagnosticDataSourceWrapper wrapper = new DiagnosticDataSourceWrapper(mockDs, TENANT_ID);
        Connection conn = wrapper.getConnection();
        appender.list.clear();

        conn.close();

        String releaseMsg =
                appender.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(msg -> msg.contains("[diag-conn] RELEASE"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("[diag-conn] RELEASE not found"));

        assertThat(releaseMsg)
                .as("RELEASE log must include tenant UUID")
                .contains(TENANT_ID.toString());
        assertThat(releaseMsg)
                .as("RELEASE log must include autoCommit state at close time")
                .contains("autoCommit=true");
    }
}
