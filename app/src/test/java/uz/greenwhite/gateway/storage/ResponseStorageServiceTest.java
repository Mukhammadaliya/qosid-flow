package uz.greenwhite.gateway.storage;

import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.greenwhite.gateway.metrics.GatewayMetrics;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResponseStorageServiceTest {

    @Mock RocksDBClient rocksDBClient;
    @Mock RocksDBProperties rocksDBProperties;
    @Mock GatewayMetrics metrics;
    @Mock Counter successCounter;
    @Mock Counter errorCounter;

    @Test
    void writeFailurePropagatesAndCountsError() {
        when(metrics.getRocksdbSaveError()).thenReturn(errorCounter);
        doThrow(new StorageWriteException("disk full", null))
                .when(rocksDBClient).putResponse(any(), any());

        ResponseStorageService service =
                new ResponseStorageService(rocksDBClient, rocksDBProperties, metrics);

        assertThrows(StorageWriteException.class,
                () -> service.saveResponse("1:2", new byte[]{1}, 200, "application/json"));
        verify(errorCounter).increment();
    }

    @Test
    void successfulWriteCountsSuccess() {
        when(metrics.getRocksdbSaveSuccess()).thenReturn(successCounter);

        ResponseStorageService service =
                new ResponseStorageService(rocksDBClient, rocksDBProperties, metrics);
        service.saveResponse("1:2", new byte[]{1}, 200, "application/json");

        verify(successCounter).increment();
    }
}
