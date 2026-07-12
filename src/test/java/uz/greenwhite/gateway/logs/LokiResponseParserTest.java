package uz.greenwhite.gateway.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LokiResponseParserTest {

    private final LokiResponseParser parser = new LokiResponseParser(new ObjectMapper());

    @Test
    void parsesStreamsValuesNewestFirst() {
        String json = """
            {"data":{"result":[
              {"stream":{"level":"ERROR","job":"qosid-jar"},
               "values":[["1700000001000000000","older line"],["1700000002000000000","newer line"]]}
            ]}}""";
        List<LogEntry> entries = parser.parse(json);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).line()).isEqualTo("newer line"); // newest first
        assertThat(entries.get(0).level()).isEqualTo("ERROR");
        assertThat(entries.get(0).labels()).containsEntry("job", "qosid-jar");
        assertThat(entries.get(0).timestamp()).startsWith("2023-11-14T"); // ns -> ISO
    }

    @Test
    void parsedFieldHoldsDlqJsonWhenLineIsJson() {
        String json = """
            {"data":{"result":[
              {"stream":{"stream":"dlq"},
               "values":[["1700000002000000000","{\\"failureReason\\":\\"timeout\\",\\"tenantId\\":100}"]]}
            ]}}""";
        List<LogEntry> entries = parser.parse(json);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).parsed()).isNotNull();
    }

    @Test
    void sortsByRawNanosNotIsoString() {
        // Same second, mixed sub-second precision: Instant.toString() drops a zero fraction,
        // so ".000" renders "...:20Z" and ".500" renders "...:20.500Z". As strings,
        // 'Z' > '.' puts the OLDER whole-second entry first; the raw-nanos sort must not.
        String json = """
            {"data":{"result":[
              {"stream":{},
               "values":[["1700000000000000000","older whole-second"],["1700000000500000000","newer 500ms"]]}
            ]}}""";
        List<LogEntry> entries = parser.parse(json);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).line()).isEqualTo("newer 500ms");
        assertThat(entries.get(1).line()).isEqualTo("older whole-second");
    }

    @Test
    void parseLabelValuesReturnsData() {
        assertThat(parser.parseLabelValues("{\"data\":[\"redis\",\"redpanda\"]}"))
                .containsExactly("redis", "redpanda");
    }
}
