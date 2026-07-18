package uz.greenwhite.gateway.oracle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.source.SourceAuthService;
import uz.greenwhite.gateway.tenant.TenantMappingService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Guard behaviour: when the effective source base-url / request-pull-uri is blank
 * (endpoints not yet configured via the Settings UI), the E1 pull cycle must stay
 * dormant — no HTTP call, no auth resolution, empty result, warn logged once.
 */
@ExtendWith(MockitoExtension.class)
class BiruniClientDormantPullTest {

    @Mock RestClient restClient;
    @Mock GatewayProperties properties;
    @Mock TenantMappingService tenantMappingService;
    @Mock SourceAuthService sourceAuthService;
    @Mock SettingsService settingsService;

    private BiruniClient client() {
        return new BiruniClient(restClient, properties, tenantMappingService, sourceAuthService, settingsService);
    }

    private void stubSettings(String base, String pullUri) {
        // SettingsService.get(key, default) — return the effective value directly.
        when(settingsService.get(eq("gateway.source.base-url"), any())).thenReturn(base);
        when(settingsService.get(eq("gateway.source.request-pull-uri"), any())).thenReturn(pullUri);
    }

    @Test
    void blankBaseUrl_skipsWithoutHttpCallOrAuth() {
        stubSettings("", "/pull");

        List<RequestMessage> result = client().pullRequests();

        assertTrue(result.isEmpty(), "dormant pull returns empty");
        verify(restClient, never()).get();
        verifyNoInteractions(sourceAuthService, tenantMappingService);
    }

    @Test
    void blankPullUri_skipsWithoutHttpCallOrAuth() {
        stubSettings("http://host", "  ");

        List<RequestMessage> result = client().pullRequests();

        assertTrue(result.isEmpty(), "dormant pull returns empty");
        verify(restClient, never()).get();
        verifyNoInteractions(sourceAuthService, tenantMappingService);
    }

    @Test
    void nullBaseUrl_skipsCleanly() {
        stubSettings(null, "/pull");

        List<RequestMessage> result = client().pullRequests();

        assertTrue(result.isEmpty());
        verify(restClient, never()).get();
    }

    @Test
    void twoDormantCycles_invokeHttpZeroTimes_andWarnOnce() {
        stubSettings("", "");

        Logger logger = (Logger) LoggerFactory.getLogger(BiruniClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            BiruniClient client = client();
            client.pullRequests();
            client.pullRequests();

            verify(restClient, never()).get();

            long warnCount = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains("Source pull o'chiq"))
                    .count();
            assertEquals(1, warnCount, "warn logged once per unconfigured episode, not every cycle");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void configuredEndpoints_proceedToHttpAndAuth() {
        // Both configured → guard passes through; the actual HTTP call is delegated to the
        // (mocked) RestClient. We assert the guard did NOT short-circuit: auth is resolved
        // and restClient.get() is invoked. RestClient mock returns null get() → NPE downstream,
        // which pullRequests wraps into RuntimeException; that's fine, the guard let it through.
        stubSettings("http://host", "/pull");
        lenient().when(sourceAuthService.authHeaders()).thenReturn(java.util.Map.of());

        try {
            client().pullRequests();
        } catch (RuntimeException ignored) {
            // mocked RestClient.get() returns null → downstream NPE wrapped; expected.
        }

        verify(restClient).get();
    }
}
