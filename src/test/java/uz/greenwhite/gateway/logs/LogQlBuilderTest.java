package uz.greenwhite.gateway.logs;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LogQlBuilderTest {

    @Test
    void dlqSelectorWithTextFilter() {
        assertThat(LogQlBuilder.build("dlq", "timeout", null, null))
                .isEqualTo("{stream=\"dlq\"} |= \"timeout\"");
    }

    @Test
    void jarSelectorFiltersByLevelLabel() {
        assertThat(LogQlBuilder.build("jar", null, null, "ERROR"))
                .isEqualTo("{job=\"qosid-jar\",level=\"ERROR\"}");
    }

    @Test
    void dockerSelectorByContainerWithLevelLineFilter() {
        // Docker container log levels are not reliably labeled, so level is a
        // case-insensitive line filter, not a label.
        assertThat(LogQlBuilder.build("docker", null, "redis", "ERROR"))
                .isEqualTo("{container=~\"redis\"} |~ \"(?i)error\"");
    }

    @Test
    void unknownSourceThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LogQlBuilder.build("bogus", null, null, null));
    }
}
