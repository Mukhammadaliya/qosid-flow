package uz.greenwhite.gateway.oracle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.source.SourceAuthService;
import uz.greenwhite.gateway.tenant.TenantMappingService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * A misconfigured pull URI makes the source answer 404 on every poll, and the poll runs every
 * few seconds. On the demo box the origin's 404 page was ~2 KB of Tomcat HTML with a Cloudflare
 * script tag, and the client wrote all of it: once in the caught log line, again inside the
 * message of the wrapped rethrow that {@code RequestPuller} then logged — plus two stack traces.
 * The pair cost roughly 4 KB of log per failed poll, all of it the same page.
 *
 * <p>The upstream status is the diagnostic here; the body is not. This pins the log line to a
 * bounded, single-line rendering and keeps the body out of the propagated message.
 */
@ExtendWith(MockitoExtension.class)
class SourceClientErrorLoggingTest {

    private static final String HUGE_404_BODY =
            "<!doctype html><html><head><title>HTTP Status 404</title></head><body>"
            + "<h1>HTTP Status 404 - Not Found</h1>" + "padding ".repeat(500)
            + "</body></html>";

    @Mock SettingsService settings;
    @Mock GatewayProperties props;
    @Mock TenantMappingService tenantMappingService;
    @Mock SourceAuthService sourceAuthService;

    private ListAppender<ILoggingEvent> appender;
    private Logger clientLogger;
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        clientLogger = (Logger) LoggerFactory.getLogger(SourceClient.class);
        // Pinned, not inherited: the app runs uz.greenwhite.gateway at DEBUG, so the detail line
        // this class deliberately pushes to DEBUG would otherwise be counted as operator-visible
        // output and the assertions below would measure the ambient level instead of the contract.
        originalLevel = clientLogger.getLevel();
        clientLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        clientLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        clientLogger.detachAppender(appender);
        clientLogger.setLevel(originalLevel);
    }

    private SourceClient clientAnswering404() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://source.example.com/api/pull"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body(HUGE_404_BODY).contentType(MediaType.TEXT_HTML));

        when(settings.get(eq("gateway.source.base-url"), any())).thenReturn("https://source.example.com");
        when(settings.get(eq("gateway.source.request-pull-uri"), any())).thenReturn("/api/pull");
        when(sourceAuthService.authHeaders()).thenReturn(Map.of("Authorization", "Basic xxx"));

        return new SourceClient(builder.build(), props, tenantMappingService, sourceAuthService, settings);
    }

    private SourceClient clientAnswering404Repeatedly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                        requestTo("https://source.example.com/api/pull"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body(HUGE_404_BODY).contentType(MediaType.TEXT_HTML));

        when(settings.get(eq("gateway.source.base-url"), any())).thenReturn("https://source.example.com");
        when(settings.get(eq("gateway.source.request-pull-uri"), any())).thenReturn("/api/pull");
        when(sourceAuthService.authHeaders()).thenReturn(Map.of("Authorization", "Basic xxx"));

        return new SourceClient(builder.build(), props, tenantMappingService, sourceAuthService, settings);
    }

    @Test
    void aPullUriThatAlwaysFailsDoesNotLogOncePerPoll() {
        SourceClient client = clientAnswering404Repeatedly();

        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(client::pullRequests).isInstanceOf(RuntimeException.class);
        }

        // The poll runs every 5 s, so "one line per failure" is ~17 000 lines a day for a single
        // typo in a URI. One fact deserves one line, restated occasionally.
        assertThat(appender.list).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void doesNotWriteTheUpstreamErrorPageIntoTheLog() {
        SourceClient client = clientAnswering404();

        assertThatThrownBy(client::pullRequests).isInstanceOf(RuntimeException.class);

        assertThat(appender.list).hasSize(1);
        String logged = appender.list.get(0).getFormattedMessage();
        // The status must survive; the page must not. A short head of the body is deliberately
        // kept — an upstream's first sentence is often the whole explanation — so the contract
        // is a bounded line, not a body-free one. The 4 KB page is ~10x this cap.
        assertThat(logged).contains("404");
        assertThat(logged.length()).isLessThan(HUGE_404_BODY.length() / 5);
        assertThat(logged).doesNotContain("</html>");
    }

    @Test
    void keepsTheFailureOnOneLine() {
        SourceClient client = clientAnswering404();

        assertThatThrownBy(client::pullRequests).isInstanceOf(RuntimeException.class);

        assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("\n");
    }

    @Test
    void leavesTheStackTraceToDebug() {
        SourceClient client = clientAnswering404();

        assertThatThrownBy(client::pullRequests).isInstanceOf(RuntimeException.class);

        // RequestPuller logs the rethrow as well; two identical scheduled-poll traces per
        // failure is what turned a config typo into megabytes an hour.
        assertThat(appender.list.get(0).getThrowableProxy()).isNull();
    }

    @Test
    void propagatedMessageIsBoundedSoTheCallerCannotReLogThePage() {
        SourceClient client = clientAnswering404();

        assertThatThrownBy(client::pullRequests)
                .isInstanceOf(RuntimeException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage().length()).isLessThan(HUGE_404_BODY.length() / 5);
                    assertThat(e.getMessage()).doesNotContain("</html>");
                });
    }

    @Test
    void keepsTheOriginalExceptionAsTheCauseForDebugging() {
        SourceClient client = clientAnswering404();

        // Truncating the log message must not throw away the real exception — DEBUG and any
        // upstream handler still need the untouched cause.
        assertThatThrownBy(client::pullRequests).hasCauseInstanceOf(
                org.springframework.web.client.HttpClientErrorException.NotFound.class);
    }
}
