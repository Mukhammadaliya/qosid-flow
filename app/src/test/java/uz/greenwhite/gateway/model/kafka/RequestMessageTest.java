package uz.greenwhite.gateway.model.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestMessageTest {

    /**
     * Back-compat: messages produced by pre-rename (v1.x) code that are still
     * sitting in Kafka topics at deploy time use the old field names
     * (company_id/filial_id). @JsonAlias on tenantId/extraTenantId lets those
     * legacy-shaped payloads still deserialize into the renamed fields instead
     * of silently landing as null.
     */
    @Test
    void legacyCompanyIdAliasDeserializesToTenantId() throws Exception {
        RequestMessage msg = new ObjectMapper()
                .readValue("{\"company_id\":100,\"request_id\":7}", RequestMessage.class);

        assertEquals(100L, msg.getTenantId());
        assertEquals(7L, msg.getRequestId());
    }

    @Test
    void legacyFilialIdAliasDeserializesToExtraTenantId() throws Exception {
        RequestMessage msg = new ObjectMapper()
                .readValue("{\"company_id\":100,\"filial_id\":5,\"request_id\":7}", RequestMessage.class);

        assertEquals(5L, msg.getExtraTenantId());
    }
}
