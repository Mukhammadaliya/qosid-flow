package uz.greenwhite.gateway.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusResponseParserTest {

    private final PrometheusResponseParser parser = new PrometheusResponseParser(new ObjectMapper());

    @Test
    void parseScalarReadsInstantVectorValue() {
        // Prometheus /query "vector" result: data.result[0].value = [ <ts>, "<value>" ]
        String json = "{\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{},\"value\":[1700000000,\"42.5\"]}]}}";
        assertThat(parser.parseScalar(json)).isEqualTo(42.5);
    }

    @Test
    void parseScalarReturnsNullWhenEmpty() {
        assertThat(parser.parseScalar("{\"data\":{\"resultType\":\"vector\",\"result\":[]}}")).isNull();
    }

    @Test
    void parseRangeReadsMatrixValues() {
        String json = "{\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{},"
                + "\"values\":[[1700000000,\"1\"],[1700000060,\"2\"]]}]}}";
        List<StatPoint> pts = parser.parseRange(json);
        assertThat(pts).hasSize(2);
        assertThat(pts.get(0).t()).isEqualTo(1700000000.0);
        assertThat(pts.get(1).v()).isEqualTo(2.0);
    }
}
