package uz.greenwhite.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import uz.greenwhite.gateway.api.model.StatusResponse;
import uz.greenwhite.gateway.config.ProxyProperties;
import uz.greenwhite.gateway.model.RequestState;
import uz.greenwhite.gateway.model.enums.ErrorSource;
import uz.greenwhite.gateway.model.enums.RequestStatus;
import uz.greenwhite.gateway.state.RequestStateService;
import uz.greenwhite.gateway.storage.ResponseMetadata;
import uz.greenwhite.gateway.storage.ResponseStorageService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The upstream HTTP status was reachable only through {@code /result}, which returns it as the
 * response's own status code — so a poller that wanted "did the upstream answer 200 or 502?"
 * had to fetch the whole body, and for a large payload {@code /status} handed back a
 * {@code resultUrl} with no status at all. It is one integer already sitting in the RocksDB
 * metadata next to the body.
 *
 * <p>Absent rather than {@code 0} when unknown: a request still in flight, or one whose upstream
 * never produced a body, has no HTTP status, and reporting {@code 0} would read as a real value.
 */
class StatusControllerHttpStatusTest {

    private static final String ID = "a1b2c3d4";

    private final RequestStateService state = mock(RequestStateService.class);
    private final ResponseStorageService storage = mock(ResponseStorageService.class);

    private StatusController controller() {
        StatusController controller =
                new StatusController(state, storage, new ObjectMapper(), mock(ProxyProperties.class));
        ReflectionTestUtils.setField(controller, "responseThresholdSize", 1024);
        return controller;
    }

    private void restRequest(RequestStatus status) {
        when(state.getState(ID)).thenReturn(Optional.of(RequestState.builder()
                .compositeId(ID).status(status).source("API").hasResult(true).build()));
    }

    private void storedResponse(int httpStatus, String contentType, byte[] body) {
        when(storage.getResponseMetadata(ID)).thenReturn(
                ResponseMetadata.builder().httpStatus(httpStatus).contentType(contentType).build());
        when(storage.getResponseBody(ID)).thenReturn(body);
    }

    @Test
    void inlinedResultCarriesTheUpstreamHttpStatus() {
        restRequest(RequestStatus.COMPLETED);
        storedResponse(200, "application/json", "{\"ok\":true}".getBytes());

        StatusResponse body = controller().getStatus(ID).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getHttpStatus()).isEqualTo(200);
        assertThat(body.getResult()).isNotNull();
    }

    /** The case that motivated this: too big to inline, so the status was the only thing missing. */
    @Test
    void resultUrlResponseAlsoCarriesTheUpstreamHttpStatus() {
        restRequest(RequestStatus.COMPLETED);
        storedResponse(201, "application/json", new byte[4096]);

        StatusResponse body = controller().getStatus(ID).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getResultUrl()).isNotNull();
        assertThat(body.getHttpStatus()).isEqualTo(201);
    }

    /** A failed upstream body is stored too, so the status is available on FAILED as well. */
    @Test
    void failedRequestReportsTheUpstreamStatusAlongsideTheError() {
        when(state.getState(ID)).thenReturn(Optional.of(RequestState.builder()
                .compositeId(ID).status(RequestStatus.FAILED).source("API")
                .lastError("HTTP 502: Bad Gateway").errorSource(ErrorSource.HTTP).build()));
        storedResponse(502, "text/html", "<html>nope</html>".getBytes());

        StatusResponse body = controller().getStatus(ID).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getHttpStatus()).isEqualTo(502);
        assertThat(body.getErrorMessage()).isEqualTo("HTTP 502: Bad Gateway");
        assertThat(body.getErrorSource()).isEqualTo("HTTP");
    }

    @Test
    void inFlightRequestReportsNoHttpStatusAtAll() {
        restRequest(RequestStatus.PROCESSING);
        when(storage.getResponseMetadata(ID)).thenReturn(null);

        StatusResponse body = controller().getStatus(ID).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo("PROCESSING");
        assertThat(body.getHttpStatus())
                .as("no upstream answer yet — absent beats a misleading 0")
                .isNull();
    }

    /** The gate from SAFETY-RULES 34 still wins: nothing at all for source-owned requests. */
    @Test
    void oracleSourcedRequestStillGets404() {
        when(state.getState(ID)).thenReturn(Optional.of(RequestState.builder()
                .compositeId(ID).status(RequestStatus.COMPLETED).source("ORACLE").hasResult(true).build()));

        assertThat(controller().getStatus(ID).getStatusCode().value()).isEqualTo(404);
    }
}
