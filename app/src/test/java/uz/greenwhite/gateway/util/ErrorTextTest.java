package uz.greenwhite.gateway.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One-line, bounded rendering of a Throwable for log messages.
 *
 * <p>Written after a demo box produced a 4.7 GB log file in three days. Two habits did it:
 * logging {@code e.getMessage()} for exceptions that carry none (a {@code TimeoutException}
 * printed the literal line "Error monitoring consumer lag: null" 4 257 times in one day), and
 * logging an upstream error body verbatim — a single 404 from a Tomcat/Cloudflare origin is
 * ~2 KB of HTML, and it was being written twice per failed pull.
 */
class ErrorTextTest {

    @Test
    void rendersClassAndMessage() {
        assertThat(ErrorText.brief(new IllegalStateException("boom")))
                .isEqualTo("IllegalStateException: boom");
    }

    @Test
    void messagelessExceptionStillIdentifiesItself() {
        // The whole point: "null" told the operator nothing about 4 257 identical lines.
        String text = ErrorText.brief(new TimeoutException());
        assertThat(text).isEqualTo("TimeoutException");
        assertThat(text).doesNotContain("null");
    }

    @Test
    void blankMessageIsTreatedAsAbsent() {
        assertThat(ErrorText.brief(new IllegalStateException("   ")))
                .isEqualTo("IllegalStateException");
    }

    @Test
    void longMessageIsTruncatedWithAMarker() {
        String body = "<html>" + "x".repeat(5_000) + "</html>";
        String text = ErrorText.brief(new RuntimeException(body));
        assertThat(text.length()).isLessThan(400);
        assertThat(text).endsWith("…");
        assertThat(text).startsWith("RuntimeException: <html>");
    }

    @Test
    void newlinesCollapseSoOneFailureStaysOneLogLine() {
        String text = ErrorText.brief(new RuntimeException("first\nsecond\r\nthird"));
        assertThat(text).isEqualTo("RuntimeException: first second third");
    }

    @Test
    void nullThrowableIsNotAnExcuseToThrowFromALogStatement() {
        assertThat(ErrorText.brief(null)).isEqualTo("unknown error");
    }
}
