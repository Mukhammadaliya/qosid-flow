package uz.greenwhite.gateway.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Parses Prometheus /api/v1/query (vector) and /query_range (matrix) JSON. */
@RequiredArgsConstructor
public class PrometheusResponseParser {

    private final ObjectMapper objectMapper;

    /** Instant vector -> the first sample's value, or null if empty/NaN/unparseable. */
    public Double parseScalar(String json) {
        try {
            JsonNode result = objectMapper.readTree(json).path("data").path("result");
            if (!result.isArray() || result.isEmpty()) return null;
            JsonNode value = result.get(0).path("value");
            if (!value.isArray() || value.size() < 2) return null;
            double d = Double.parseDouble(value.get(1).asText());
            return Double.isNaN(d) ? null : d;
        } catch (Exception e) {
            return null;
        }
    }

    /** Range matrix -> the first series' [t, v] points (empty on error/absent). */
    public List<StatPoint> parseRange(String json) {
        List<StatPoint> out = new ArrayList<>();
        try {
            JsonNode result = objectMapper.readTree(json).path("data").path("result");
            if (!result.isArray() || result.isEmpty()) return out;
            for (JsonNode value : result.get(0).path("values")) {
                double t = value.get(0).asDouble();
                double v = Double.parseDouble(value.get(1).asText());
                if (!Double.isNaN(v)) out.add(new StatPoint(t, v));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }
}
