package uz.greenwhite.gateway.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reporting policy shared by every "runs on a timer, fails on every tick" path — see
 * SAFETY-RULES 42. Extracted so the policy is decided once instead of being re-derived (and
 * re-forgotten) at each call site: the gateway has three of them, and each one that gets it
 * wrong costs megabytes an hour while a dependency is down.
 */
class RepeatingFailureLogTest {

    @Test
    void theFirstFailureIsAlwaysWorthReporting() {
        RepeatingFailureLog log = new RepeatingFailureLog(100);

        assertThat(log.noteFailure()).isTrue();
        assertThat(log.consecutive()).isEqualTo(1);
    }

    @Test
    void immediatelyRepeatedFailuresAreNotReported() {
        RepeatingFailureLog log = new RepeatingFailureLog(100);
        log.noteFailure();

        for (int i = 0; i < 98; i++) {
            assertThat(log.noteFailure()).isFalse();
        }
    }

    @Test
    void aStillBrokenDependencyIsRestatedEveryNthOccurrence() {
        // Silence forever would be worse than noise: an outage lasting hours must leave a trail.
        RepeatingFailureLog log = new RepeatingFailureLog(100);

        int reported = 0;
        for (int i = 0; i < 1_000; i++) {
            if (log.noteFailure()) reported++;
        }

        // The first occurrence, plus the 100th, 200th ... 1000th: 1 + 10 = 11 lines for a
        // thousand ticks. That is the trail; the other 989 would have added nothing.
        assertThat(reported).isEqualTo(11);
        assertThat(log.consecutive()).isEqualTo(1_000);
    }

    @Test
    void successReportsHowLongTheOutageLasted() {
        RepeatingFailureLog log = new RepeatingFailureLog(100);
        log.noteFailure();
        log.noteFailure();
        log.noteFailure();

        assertThat(log.noteSuccess()).isEqualTo(3);
    }

    @Test
    void successOnAHealthyPathSaysNothing() {
        RepeatingFailureLog log = new RepeatingFailureLog(100);

        // Zero means "there was no outage to close" — the caller must not log a recovery line
        // on every single healthy tick.
        assertThat(log.noteSuccess()).isZero();
    }

    @Test
    void aNewOutageAfterRecoveryReportsItsFirstFailureAgain() {
        RepeatingFailureLog log = new RepeatingFailureLog(100);
        log.noteFailure();
        log.noteSuccess();

        assertThat(log.noteFailure()).isTrue();
        assertThat(log.consecutive()).isEqualTo(1);
    }

    @Test
    void repeatEveryMustBePositive() {
        // A zero or negative interval would make the modulo either divide by zero or report
        // nothing at all — fail at construction, not silently at 03:00 during an outage.
        assertThat(java.util.stream.Stream.of(0, -1)
                .map(n -> {
                    try {
                        new RepeatingFailureLog(n);
                        return "accepted";
                    } catch (IllegalArgumentException e) {
                        return "rejected";
                    }
                }))
                .containsOnly("rejected");
    }
}
