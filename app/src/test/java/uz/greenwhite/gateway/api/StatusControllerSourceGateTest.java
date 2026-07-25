package uz.greenwhite.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.config.ProxyProperties;
import uz.greenwhite.gateway.model.RequestState;
import uz.greenwhite.gateway.model.enums.RequestStatus;
import uz.greenwhite.gateway.state.RequestStateService;
import uz.greenwhite.gateway.storage.ResponseMetadata;
import uz.greenwhite.gateway.storage.ResponseStorageService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ORACLE-sourced responses must not be readable through the public polling
 * endpoints. Their request_id is a source-side sequential integer and tenantId is a
 * small integer, so the composite id is enumerable; the result also travels back to
 * the source over the save leg, so no external reader needs it. 404 (not 403) so the
 * response does not confirm that an id exists.
 */
class StatusControllerSourceGateTest {

    private final RequestStateService state = mock(RequestStateService.class);
    private final ResponseStorageService storage = mock(ResponseStorageService.class);

    private StatusController controller() {
        return new StatusController(state, storage, new ObjectMapper(), mock(ProxyProperties.class));
    }

    private RequestState stateWithSource(String source) {
        return RequestState.builder()
                .compositeId("100:900123")
                .status(RequestStatus.COMPLETED)
                .source(source)
                .hasResult(true)
                .build();
    }

    @Test
    void statusIs404ForOracleSourcedRequest() {
        when(state.getState("100:900123")).thenReturn(Optional.of(stateWithSource("ORACLE")));
        assertThat(controller().getStatus("100:900123").getStatusCode().value()).isEqualTo(404);
    }

    /**
     * A body IS present in RocksDB — otherwise the endpoint would 404 for the wrong
     * reason and the refusal tests below would pass without any gate.
     */
    private void storedBodyExists(String id) {
        when(storage.getResponseMetadata(id)).thenReturn(
                ResponseMetadata.builder().httpStatus(200).contentType("application/json").build());
        when(storage.getResponseBody(id)).thenReturn("{\"secret\":true}".getBytes());
    }

    @Test
    void resultIs404ForOracleSourcedRequest() {
        when(state.getState("100:900123")).thenReturn(Optional.of(stateWithSource("ORACLE")));
        storedBodyExists("100:900123");
        assertThat(controller().getResult("100:900123").getStatusCode().value()).isEqualTo(404);
        // The gate must run BEFORE RocksDB is touched — no body may be read at all.
        verify(storage, never()).getResponseBody(anyString());
    }

    @Test
    void resultIs404WhenNoStateExists() {
        // Defence in depth: an id whose state has expired must not fall through to RocksDB.
        when(state.getState("100:900123")).thenReturn(Optional.empty());
        storedBodyExists("100:900123");
        assertThat(controller().getResult("100:900123").getStatusCode().value()).isEqualTo(404);
        verify(storage, never()).getResponseBody(anyString());
    }

    @Test
    void apiSourcedRequestIsStillReadable() {
        when(state.getState("0:123")).thenReturn(Optional.of(
                RequestState.builder().compositeId("0:123").status(RequestStatus.COMPLETED)
                        .source("API").hasResult(true).build()));
        ResponseMetadata meta = mock(ResponseMetadata.class);
        when(meta.getHttpStatus()).thenReturn(200);
        when(meta.getContentType()).thenReturn("application/json");
        when(storage.getResponseMetadata("0:123")).thenReturn(meta);
        when(storage.getResponseBody("0:123")).thenReturn("{\"ok\":true}".getBytes());

        assertThat(controller().getResult("0:123").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void nullSourceIsTreatedAsReadable() {
        // Legacy rows written before source was persisted must not start 404-ing.
        when(state.getState("0:999")).thenReturn(Optional.of(
                RequestState.builder().compositeId("0:999").status(RequestStatus.PROCESSING)
                        .source(null).build()));
        assertThat(controller().getStatus("0:999").getStatusCode().value()).isEqualTo(200);
    }
}
