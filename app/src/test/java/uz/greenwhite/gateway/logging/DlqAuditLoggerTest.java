package uz.greenwhite.gateway.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.model.kafka.DlqMessage;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DlqAuditLoggerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private DlqMessage sample() {
        return DlqMessage.builder()
                .tenantId(100L).requestId(7L).originalTopic("api-request")
                .failureReason("timeout").errorSource("HTTP").httpStatus(504)
                .attemptCount(3).url("POST http://x/y").body("payload")
                .failedAt(LocalDateTime.of(2026, 7, 12, 10, 0)).build();
    }

    @Test
    void toJsonContainsAllFieldsOnOneLine() throws JsonProcessingException {
        String json = new DlqAuditLogger(mapper).toJson(sample());
        assertThat(json).doesNotContain("\n");
        assertThat(json).contains("\"failureReason\":\"timeout\"")
                .contains("\"tenantId\":100").contains("\"httpStatus\":504")
                .contains("\"errorSource\":\"HTTP\"");
    }

    @Test
    void recordSwallowsSerializationFailure() throws JsonProcessingException {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("boom"));
        DlqAuditLogger logger = new DlqAuditLogger(broken);
        assertThatCode(() -> logger.record(sample())).doesNotThrowAnyException();
    }
}
